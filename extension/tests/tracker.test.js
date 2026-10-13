import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createTracker, domainOf, MAX_GAP_MS } from '../lib/tracker.js';
import { localDate } from '../lib/dates.js';

export function fakeStorage(initial = {}) {
  const data = structuredClone(initial);
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

function setup(start = at(2026, 6, 10, 12, 0)) {
  const storage = fakeStorage();
  const clock = { t: start };
  const now = () => clock.t;
  const tracker = createTracker({ storage, now });
  return { storage, clock, tracker, now };
}

test('domainOf table', () => {
  const cases = [
    ['https://example.com/a?b=1', 'example.com'],
    ['http://example.com', 'example.com'],
    ['https://www.Example.COM/x', 'example.com'],
    ['https://www.www.example.com', 'www.example.com'],
    ['https://sub.example.com:8080/p', 'sub.example.com'],
    ['chrome://newtab', null],
    ['about:blank', null],
    ['chrome-extension://abcdef/popup.html', null],
    ['file:///x', null],
    ['data:text/html,hi', null],
    ['', null],
    [null, null],
    [undefined, null],
    [42, null],
    ['garbage', null],
    ['http://', null],
  ];
  for (const [input, want] of cases) assert.equal(domainOf(input), want, String(input));
});

test('default clock is evaluated at call time', async () => {
  const storage = fakeStorage();
  const tracker = createTracker({ storage });
  const real = Date.now;
  try {
    Date.now = () => 1_000_000;
    await tracker.activate(1, 'https://a.com');
    Date.now = () => 1_000_000 + 30_000;
    assert.deepEqual(await tracker.getToday(), { 'a.com': 30 });
  } finally {
    Date.now = real;
  }
});

test('activate A, +90s, activate B banks A and opens B', async () => {
  const { clock, tracker, storage } = setup();
  await tracker.activate(1, 'https://a.com/x');
  clock.t += 90_000;
  await tracker.activate(2, 'https://www.b.com/y');
  const { days, dirty } = await tracker.snapshot();
  const day = localDate(clock.t);
  assert.deepEqual(days[day], { 'a.com': 90 });
  assert.ok(dirty[day] >= 1);
  assert.deepEqual(storage.data.session, { tabId: 2, domain: 'b.com', lastMs: clock.t, carryMs: 0 });
});

test('activate on a non-trackable url closes the session', async () => {
  const { clock, tracker, storage } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 10_000;
  await tracker.activate(2, 'chrome://newtab');
  assert.equal(storage.data.session, null);
  clock.t += 60_000;
  await tracker.tick();
  assert.deepEqual((await tracker.snapshot()).days[localDate(clock.t)], { 'a.com': 10 });
});

test('activate same tab twice loses nothing and does not double count', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 20_000;
  await tracker.activate(1, 'https://a.com');
  clock.t += 20_000;
  await tracker.activate(1, 'https://a.com');
  clock.t += 5_000;
  assert.deepEqual(await tracker.getToday(), { 'a.com': 45 });
});

test('navigate on active tab switches domain; non-active tab is ignored', async () => {
  const { clock, tracker, storage } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 30_000;
  await tracker.navigate(2, 'https://other.com');
  assert.equal(storage.data.session.domain, 'a.com');
  assert.equal(storage.data.session.tabId, 1);
  clock.t += 30_000;
  await tracker.navigate(1, 'https://c.com');
  assert.equal(storage.data.session.domain, 'c.com');
  assert.deepEqual((await tracker.snapshot()).days[localDate(clock.t)], { 'a.com': 60 });
});

test('navigate with no session starts one; navigate to untrackable ends it', async () => {
  const { clock, tracker, storage } = setup();
  await tracker.navigate(5, 'https://a.com');
  assert.equal(storage.data.session.tabId, 5);
  clock.t += 5_000;
  await tracker.navigate(5, 'about:blank');
  assert.equal(storage.data.session, null);
  assert.deepEqual((await tracker.snapshot()).days[localDate(clock.t)], { 'a.com': 5 });
});

test('pause banks then stops counting', async () => {
  const { clock, tracker, storage } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 40_000;
  await tracker.pause();
  assert.equal(storage.data.session, null);
  clock.t += 100_000;
  await tracker.tick();
  assert.deepEqual((await tracker.snapshot()).days[localDate(clock.t)], { 'a.com': 40 });
});

test('tick with no session is a no-op', async () => {
  const { tracker } = setup();
  await tracker.tick();
  await tracker.pause();
  assert.deepEqual(await tracker.snapshot(), { days: {}, dirty: {} });
});

test('tick banks without closing; two ticks 60s apart = 120s', async () => {
  const { clock, tracker, storage } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 60_000;
  await tracker.tick();
  clock.t += 60_000;
  await tracker.tick();
  assert.deepEqual((await tracker.snapshot()).days[localDate(clock.t)], { 'a.com': 120 });
  assert.equal(storage.data.session.tabId, 1);
});

test('1500ms steps lose no sub-second remainders', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  for (let i = 0; i < 200; i++) {
    clock.t += 1500;
    await tracker.tick();
  }
  assert.deepEqual((await tracker.snapshot()).days[localDate(clock.t)], { 'a.com': 300 });
});

test('many ticks equal one long banked interval', async () => {
  const a = setup();
  const b = setup();
  await a.tracker.activate(1, 'https://a.com');
  await b.tracker.activate(1, 'https://a.com');
  for (let i = 0; i < 100; i++) {
    a.clock.t += 1337;
    await a.tracker.tick();
  }
  b.clock.t += 133_700;
  await b.tracker.tick();
  assert.deepEqual((await a.tracker.snapshot()).days, (await b.tracker.snapshot()).days);
  assert.equal((await b.tracker.snapshot()).days[localDate(b.clock.t)]['a.com'], 133);
});

test('pause banks the sub-second remainder only by dropping it at close', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 1700;
  await tracker.tick();
  clock.t += 600; // carry 700 + 600 = 1300 -> one more second
  await tracker.pause();
  assert.deepEqual((await tracker.snapshot()).days[localDate(clock.t)], { 'a.com': 2 });
});

test('gap cap: 5 hours asleep counts only MAX_GAP_MS', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 5 * 3600_000;
  await tracker.tick();
  assert.equal(MAX_GAP_MS, 150_000);
  const first = localDate(at(2026, 6, 10, 12, 0));
  assert.deepEqual((await tracker.snapshot()).days[first], { 'a.com': 150 });
  // and the session continues from now
  clock.t += 10_000;
  await tracker.tick();
  assert.equal((await tracker.snapshot()).days[first]['a.com'], 160);
});

test('clock going backwards never produces negative or phantom counts', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 10_000;
  await tracker.tick();
  const before = await tracker.snapshot();
  clock.t -= 3_600_000;
  await tracker.tick();
  assert.deepEqual(await tracker.snapshot(), before);
  clock.t += 5_000; // resumes counting from the corrected clock
  await tracker.tick();
  const after = await tracker.snapshot();
  const total = Object.values(after.days).reduce((s, m) => s + (m['a.com'] || 0), 0);
  assert.equal(total, 15);
  for (const m of Object.values(after.days)) for (const v of Object.values(m)) assert.ok(v >= 0);
});

test('midnight split: 23:59:00 -> 00:01:30 gives 60s and 90s, both dirty', async () => {
  const { clock, tracker } = setup(at(2026, 6, 10, 23, 59));
  await tracker.activate(1, 'https://a.com');
  clock.t = at(2026, 6, 11, 0, 1, 30);
  await tracker.tick();
  const { days, dirty } = await tracker.snapshot();
  assert.deepEqual(days['2026-06-10'], { 'a.com': 60 });
  assert.deepEqual(days['2026-06-11'], { 'a.com': 90 });
  assert.ok(Number.isInteger(dirty['2026-06-10']) && Number.isInteger(dirty['2026-06-11']));
  assert.notEqual(dirty['2026-06-10'], dirty['2026-06-11']);
});

test('tick exactly at midnight puts everything on the old day', async () => {
  const { clock, tracker } = setup(at(2026, 6, 10, 23, 59));
  await tracker.activate(1, 'https://a.com');
  clock.t = at(2026, 6, 11, 0, 0);
  await tracker.tick();
  let s = await tracker.snapshot();
  assert.deepEqual(s.days, { '2026-06-10': { 'a.com': 60 } });
  clock.t += 30_000;
  await tracker.tick();
  s = await tracker.snapshot();
  assert.deepEqual(s.days['2026-06-11'], { 'a.com': 30 });
});

test('session open for days: only the counted (capped) seconds are split by day', async () => {
  const { clock, tracker } = setup(at(2026, 6, 10, 23, 58));
  await tracker.activate(1, 'https://a.com');
  clock.t = at(2026, 6, 13, 9, 0);
  await tracker.tick();
  const { days } = await tracker.snapshot();
  // 150s counted from 23:58:00 -> 00:00:30
  assert.deepEqual(days['2026-06-10'], { 'a.com': 120 });
  assert.deepEqual(days['2026-06-11'], { 'a.com': 30 });
  assert.equal(days['2026-06-13'], undefined);
});

test('session state survives a restart', async () => {
  const { clock, tracker, storage, now } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 45_000;
  const reborn = createTracker({ storage, now });
  clock.t += 15_000;
  await reborn.tick();
  assert.deepEqual((await reborn.snapshot()).days[localDate(clock.t)], { 'a.com': 60 });
});

test('markFlushed: stale revision keeps dirty, current clears it', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 10_000;
  await tracker.tick();
  const day = localDate(clock.t);
  const rev1 = (await tracker.snapshot()).dirty[day];
  clock.t += 10_000;
  await tracker.tick();
  const rev2 = (await tracker.snapshot()).dirty[day];
  assert.ok(rev2 > rev1);
  await tracker.markFlushed(day, rev1);
  assert.equal((await tracker.snapshot()).dirty[day], rev2);
  await tracker.markFlushed(day, rev2);
  assert.equal((await tracker.snapshot()).dirty[day], undefined);
  assert.deepEqual((await tracker.snapshot()).days[day], { 'a.com': 20 });
});

test('prune removes only clean days older than 3 days', async () => {
  const today = at(2026, 6, 20, 12, 0);
  const storage = fakeStorage({
    days: {
      '2026-06-10': { 'old.com': 5 },
      '2026-06-12': { 'olddirty.com': 5 },
      '2026-06-16': { 'edge.com': 5 }, // exactly 4 days ago: pruned
      '2026-06-17': { 'keep3.com': 5 }, // 3 days ago: kept
      '2026-06-20': { 'today.com': 5 },
    },
    dirty: { '2026-06-12': 4, '2026-06-20': 9 },
    rev: 9,
  });
  const tracker = createTracker({ storage, now: () => today });
  await tracker.markFlushed('2026-06-20', 9);
  const { days, dirty } = await tracker.snapshot();
  assert.deepEqual(Object.keys(days).sort(), ['2026-06-12', '2026-06-17', '2026-06-20']);
  assert.deepEqual(dirty, { '2026-06-12': 4 });
});

test('getToday includes live un-ticked time and is idempotent', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 25_000;
  assert.deepEqual(await tracker.getToday(), { 'a.com': 25 });
  assert.deepEqual(await tracker.getToday(), { 'a.com': 25 });
  clock.t += 5_000;
  assert.deepEqual(await tracker.getToday(), { 'a.com': 30 });
});

test('getToday with nothing tracked is empty', async () => {
  const { tracker } = setup();
  assert.deepEqual(await tracker.getToday(), {});
});

test('snapshot returns deep copies', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 10_000;
  await tracker.tick();
  const s = await tracker.snapshot();
  const day = localDate(clock.t);
  s.days[day]['a.com'] = 9999;
  s.dirty[day] = 9999;
  const again = await tracker.snapshot();
  assert.equal(again.days[day]['a.com'], 10);
  assert.notEqual(again.dirty[day], 9999);
});

test('operations are serialized and a failed one does not poison the chain', async () => {
  const base = fakeStorage();
  let failNext = false;
  let inFlight = 0;
  let maxInFlight = 0;
  const storage = {
    async get(k) {
      inFlight++; maxInFlight = Math.max(maxInFlight, inFlight);
      await new Promise((r) => setTimeout(r, 1));
      inFlight--;
      if (failNext) { failNext = false; throw new Error('boom'); }
      return base.get(k);
    },
    set: (o) => base.set(o),
    remove: (k) => base.remove(k),
  };
  const clock = { t: at(2026, 6, 10, 12) };
  const tracker = createTracker({ storage, now: () => clock.t });
  const p = [
    tracker.activate(1, 'https://a.com'),
    tracker.tick(),
    tracker.getToday(),
    tracker.snapshot(),
    tracker.pause(),
  ];
  await Promise.all(p);
  assert.equal(maxInFlight, 1);
  failNext = true;
  await assert.rejects(tracker.tick(), /boom/);
  await tracker.activate(1, 'https://a.com');
  clock.t += 7_000;
  assert.deepEqual(await tracker.getToday(), { 'a.com': 7 });
});

test('concurrent activates never interleave', async () => {
  const { clock, tracker } = setup();
  await tracker.activate(1, 'https://a.com');
  clock.t += 10_000;
  await Promise.all([tracker.activate(2, 'https://b.com'), tracker.activate(3, 'https://c.com'), tracker.tick()]);
  const s = await tracker.snapshot();
  assert.deepEqual(s.days[localDate(clock.t)], { 'a.com': 10 });
});

test('never records paths, queries or titles', async () => {
  const { clock, tracker, storage } = setup();
  await tracker.activate(1, 'https://user:pw@www.a.com/secret/path?token=abc#frag');
  clock.t += 5_000;
  await tracker.tick();
  const blob = JSON.stringify(storage.data);
  for (const bad of ['secret', 'token', 'frag', 'pw', 'path']) assert.ok(!blob.includes(bad), bad);
});
