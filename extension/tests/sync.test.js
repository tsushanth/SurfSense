import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createTracker } from '../lib/tracker.js';
import { flushDirty } from '../lib/sync.js';
import { localDate } from '../lib/dates.js';

function fakeStorage() {
  const data = {};
  return {
    data,
    async get(keys) {
      const list = Array.isArray(keys) ? keys : [keys];
      const out = {};
      for (const k of list) if (k in data) out[k] = structuredClone(data[k]);
      return out;
    },
    async set(obj) { Object.assign(data, structuredClone(obj)); },
    async remove(keys) { for (const k of Array.isArray(keys) ? keys : [keys]) delete data[k]; },
  };
}

const at = (y, m, d, h = 0, mi = 0, s = 0) => new Date(y, m - 1, d, h, mi, s).getTime();

function fakeApi({ map = {}, failCategorizeFor = null, onSubmit = null } = {}) {
  const calls = { categorize: [], submit: [] };
  return {
    calls,
    async categorize(domains) {
      calls.categorize.push([...domains]);
      if (failCategorizeFor && failCategorizeFor(domains)) throw new Error('offline');
      const out = {};
      for (const d of domains) out[d] = map[d] || 'Other';
      return out;
    },
    async submitSummary(day, summary) {
      calls.submit.push({ day, summary });
      if (onSubmit) await onSubmit(day, summary);
    },
  };
}

function setup(start = at(2026, 6, 10, 12, 0)) {
  const clock = { t: start };
  const tracker = createTracker({ storage: fakeStorage(), now: () => clock.t });
  return { clock, tracker };
}

test('one dirty day: categorize, aggregate by category, submit once, clear', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 30_000;
  await tracker.activate(2, 'https://b.com');
  clock.t += 20_000;
  await tracker.activate(3, 'https://c.com');
  clock.t += 10_000;
  const api = fakeApi({ map: { 'a.com': 'News', 'b.com': 'News', 'c.com': 'Shopping' } });
  const res = await flushDirty({ api, tracker });
  const day = localDate(clock.t);
  assert.deepEqual(res, { flushed: [day], failed: [] });
  assert.equal(api.calls.submit.length, 1);
  assert.equal(api.calls.submit[0].day, day);
  assert.deepEqual(api.calls.submit[0].summary, { News: 50, Shopping: 10 });
  assert.deepEqual(api.calls.categorize[0].sort(), ['a.com', 'b.com', 'c.com']);
  assert.deepEqual((await tracker.snapshot()).dirty, {});
});

test('flush ticks first so live time is included', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 42_000;
  const api = fakeApi({ map: { 'a.com': 'Finance' } });
  await flushDirty({ api, tracker });
  assert.deepEqual(api.calls.submit[0].summary, { Finance: 42 });
});

test('nothing dirty: no requests', async () => {
  const { tracker } = setup();
  const api = fakeApi();
  assert.deepEqual(await flushDirty({ api, tracker }), { flushed: [], failed: [] });
  assert.equal(api.calls.categorize.length + api.calls.submit.length, 0);
});

test('two dirty days flush oldest first under their own dates', async () => {
  const { clock, tracker } = setup(at(2026, 6, 10, 23, 59));
  await tracker.activate(1, 'https://a.com');
  clock.t = at(2026, 6, 11, 0, 1, 30);
  const api = fakeApi({ map: { 'a.com': 'Education' } });
  const res = await flushDirty({ api, tracker });
  assert.deepEqual(res, { flushed: ['2026-06-10', '2026-06-11'], failed: [] });
  assert.deepEqual(api.calls.submit, [
    { day: '2026-06-10', summary: { Education: 60 } },
    { day: '2026-06-11', summary: { Education: 90 } },
  ]);
});

test('failure on day 1 keeps it dirty and still flushes day 2', async () => {
  const { clock, tracker } = setup(at(2026, 6, 10, 23, 59));
  await tracker.activate(1, 'https://a.com');
  clock.t = at(2026, 6, 11, 0, 1, 30);
  let n = 0;
  const api = fakeApi({ failCategorizeFor: () => n++ === 0 });
  const res = await flushDirty({ api, tracker });
  assert.deepEqual(res, { flushed: ['2026-06-11'], failed: ['2026-06-10'] });
  const { dirty } = await tracker.snapshot();
  assert.deepEqual(Object.keys(dirty), ['2026-06-10']);
  assert.equal(api.calls.submit.length, 1);
  // retry later succeeds
  const res2 = await flushDirty({ api, tracker });
  assert.deepEqual(res2, { flushed: ['2026-06-10'], failed: [] });
});

test('submit failure keeps day dirty and never throws', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 5_000;
  const api = fakeApi({ onSubmit: async () => { throw new Error('500'); } });
  const day = localDate(clock.t);
  const res = await flushDirty({ api, tracker });
  assert.deepEqual(res, { flushed: [], failed: [day] });
  assert.ok((await tracker.snapshot()).dirty[day] > 0);
});

test('a change arriving during the submit keeps the day dirty', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 10_000;
  const api = fakeApi({
    onSubmit: async () => {
      clock.t += 5_000;
      await tracker.tick();
    },
  });
  const day = localDate(clock.t);
  const res = await flushDirty({ api, tracker });
  assert.deepEqual(res.flushed, [day]);
  const s = await tracker.snapshot();
  assert.ok(s.dirty[day] > 0, 'day stays dirty');
  assert.equal(s.days[day]['a.com'], 15);
  // next flush sends the cumulative total and clears
  await flushDirty({ api: fakeApi(), tracker });
  assert.deepEqual((await tracker.snapshot()).dirty, {});
});

test('a day with an empty domain map makes no request but is cleared', async () => {
  const { tracker } = setup();
  const storage = fakeStorage();
  storage.data.days = { '2026-06-10': {} };
  storage.data.dirty = { '2026-06-10': 3 };
  storage.data.rev = 3;
  const t = createTracker({ storage, now: () => at(2026, 6, 10, 12) });
  const api = fakeApi();
  const res = await flushDirty({ api, tracker: t });
  assert.deepEqual(res, { flushed: ['2026-06-10'], failed: [] });
  assert.equal(api.calls.categorize.length + api.calls.submit.length, 0);
  assert.deepEqual((await t.snapshot()).dirty, {});
  void tracker;
});

test('unmapped domains end up under Other', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://known.com');
  clock.t += 10_000;
  await tracker.activate(2, 'https://mystery.example');
  clock.t += 7_000;
  const api = fakeApi({ map: { 'known.com': 'News' } });
  await flushDirty({ api, tracker });
  assert.deepEqual(api.calls.submit[0].summary, { News: 10, Other: 7 });
});

test('categories missing from the api result also fall back to Other', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 9_000;
  const api = { async categorize() { return {}; }, async submitSummary(d, s) { this.s = s; } };
  await flushDirty({ api, tracker });
  assert.deepEqual(api.s, { Other: 9 });
});
