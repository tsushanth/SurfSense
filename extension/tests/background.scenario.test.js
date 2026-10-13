// Scenario test: drives background.js (real api/tracker/sync modules) against a fake `chrome`
// and an in-memory fake backend that enforces the real contract. No real timers, no network.
import test from 'node:test';
import assert from 'node:assert/strict';
import { localDate } from '../lib/dates.js';
import { ACTIONS } from '../lib/messages.js';

const REAL_NOW = Date.now;
const REAL_FETCH = globalThis.fetch;
const MIN = 60000;
const YT = 'https://www.youtube.com/watch?v=x';

// ---------- fake backend ----------
function makeBackend() {
  const b = {
    clients: new Map(), // id -> {clientId, clientType, clientName}
    keys: new Map(),    // apiKey -> id
    summaries: new Map(), // `${userId}|${day}` -> summary
    codes: new Map(),   // code -> clientId
    links: [],          // [idA, idB]
    requests: [],
    failDays: new Set(), // days whose submit is rejected with 400
    submitAttempts: [], // day of every submit attempt
    offline: false,
    seq: 0,
    wipe() { this.clients.clear(); this.keys.clear(); this.summaries.clear(); this.codes.clear(); this.links = []; },
    count(path) { return this.requests.filter((r) => r.path === path).length; },
  };
  const MAP = { 'youtube.com': 'Entertainment', 'github.com': 'Work/Productivity' };
  const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

  b.fetch = async (url, init = {}) => {
    if (b.offline) throw new TypeError('fetch failed');
    const u = new URL(url);
    const method = init.method || 'GET';
    const path = u.pathname;
    b.requests.push({ method, path });
    const body = init.body ? JSON.parse(init.body) : {};

    if (path === '/register-client') {
      if (b.clients.has(body.clientId)) return json(200, { success: true }); // known id: no key
      const apiKey = `key-${++b.seq}-${Math.random().toString(36).slice(2)}`;
      b.clients.set(body.clientId, { clientId: body.clientId, clientType: body.clientType, clientName: body.clientName });
      b.keys.set(apiKey, body.clientId);
      return json(201, { success: true, apiKey });
    }

    const auth = (init.headers && init.headers.Authorization) || '';
    const key = auth.replace(/^Bearer /, '');
    if (!b.keys.has(key)) return json(401, { error: 'Unauthorized' });

    if (path === '/get-category-mapping') {
      if (!Array.isArray(body.domains) || body.domains.length > 100) return json(400, { error: 'Too many domains' });
      const out = {};
      for (const d of body.domains) if (MAP[d]) out[d] = MAP[d];
      return json(200, out);
    }
    if (path === '/submit-category-summary') {
      b.submitAttempts.push(body.timestamp);
      if (b.failDays.has(body.timestamp)) return json(400, { error: 'Rejected day' });
      if (!/^\d{4}-\d{2}-\d{2}$/.test(body.timestamp)) return json(400, { error: 'Bad timestamp' });
      b.summaries.set(`${body.userId}|${body.timestamp}`, body.categorySummary);
      return json(200, { success: true });
    }
    if (path === '/get-summary-history') {
      const userId = u.searchParams.get('userId');
      const day = u.searchParams.get('day');
      const s = b.summaries.get(`${userId}|${day}`);
      return json(200, s ? [{ timestamp: `${day}T00:00:00.000Z`, summary: s }] : []);
    }
    if (path === '/initiate-linking') {
      const code = String(100000 + b.codes.size + 1);
      b.codes.set(code, body.clientId);
      return json(200, { success: true, code });
    }
    if (path === '/complete-linking') {
      const other = b.codes.get(body.code);
      if (!other) return json(400, { success: false, error: 'Invalid or expired code' });
      b.codes.delete(body.code);
      b.links.push([body.clientId, other]);
      return json(200, { success: true, linkedWith: other });
    }
    if (path === '/get-linked-clients') {
      const me = u.searchParams.get('clientId');
      const clients = b.links.filter((l) => l.includes(me)).map((l) => b.clients.get(l[0] === me ? l[1] : l[0])).filter(Boolean);
      return json(200, { success: true, data: { clients } });
    }
    if (path === '/unlink-device') {
      b.links = b.links.filter((l) => !(l.includes(body.clientIdA) && l.includes(body.clientIdB)));
      return json(200, { success: true });
    }
    return json(404, { error: 'Not found' });
  };
  return b;
}

// ---------- fake chrome ----------
function makeChrome(clock) {
  const data = {};
  const ev = () => {
    const listeners = [];
    return { listeners, addListener: (f) => listeners.push(f), emit: (...a) => Promise.all(listeners.map((f) => f(...a))) };
  };
  const both = (cb, value) => { if (typeof cb === 'function') { cb(value); return undefined; } return Promise.resolve(value); };
  const pick = (keys) => {
    if (keys == null) return structuredClone(data);
    const list = typeof keys === 'string' ? [keys] : Array.isArray(keys) ? keys : Object.keys(keys);
    const out = {};
    for (const k of list) if (k in data) out[k] = structuredClone(data[k]);
    return out;
  };
  const env = {
    data, tabs: [], alarms: {}, createdAlarms: [], focusedWindow: 1,
    onAlarm: ev(), onActivated: ev(), onUpdated: ev(), onRemoved: ev(), onFocus: ev(),
    onInstalled: ev(), onStartup: ev(), onMessage: ev(),
  };
  env.chrome = {
    runtime: {
      id: 'test-ext',
      onInstalled: env.onInstalled, onStartup: env.onStartup, onMessage: env.onMessage,
      getPlatformInfo: (cb) => both(cb, { os: 'mac', arch: 'arm64' }),
      sendMessage: () => {},
    },
    storage: { local: {
      get: (keys, cb) => both(cb, pick(keys)),
      set: (obj, cb) => { Object.assign(data, structuredClone(obj)); return both(cb, undefined); },
      remove: (keys, cb) => { for (const k of [].concat(keys)) delete data[k]; return both(cb, undefined); },
    } },
    alarms: {
      get: (name, cb) => both(cb, env.alarms[name]),
      create: (name, info) => { env.alarms[name] = { name, ...info }; env.createdAlarms.push(name); },
      onAlarm: env.onAlarm,
    },
    tabs: {
      onActivated: env.onActivated, onUpdated: env.onUpdated, onRemoved: env.onRemoved,
      get: (id, cb) => {
        const t = env.tabs.find((x) => x.id === id);
        if (!t) return Promise.reject(new Error('No tab with id'));
        return both(cb, { ...t });
      },
      query: (q, cb) => both(cb, env.tabs.filter((t) => (q.active === undefined || t.active === q.active)
        && (q.windowId === undefined || t.windowId === q.windowId)).map((t) => ({ ...t }))),
    },
    windows: {
      WINDOW_ID_NONE: -1,
      onFocusChanged: env.onFocus,
      get: (id, cb) => both(cb, { id, focused: id === env.focusedWindow }),
    },
  };
  env.send = (message) => new Promise((resolve) => {
    let answered = false;
    const respond = (r) => { answered = true; resolve(r); };
    const results = env.onMessage.listeners.map((f) => f(message, {}, respond));
    if (!results.some((r) => r === true) && !answered) resolve(undefined);
  });
  env.tick = async (advanceMs = MIN) => { clock.now += advanceMs; await env.onAlarm.emit({ name: 'tick' }); };
  env.openTab = (id, url, windowId = 1) => {
    env.tabs.filter((t) => t.windowId === windowId).forEach((t) => { t.active = false; });
    env.tabs.push({ id, url, active: true, windowId });
    return env.onActivated.emit({ tabId: id, windowId });
  };
  env.focus = (windowId) => { env.focusedWindow = windowId; return env.onFocus.emit(windowId); };
  return env;
}

let importCounter = 0;
async function boot({ start, offline = false, setup } = {}) {
  const clock = { now: start ?? new Date(2026, 9, 13, 10, 0, 0).getTime() };
  Date.now = () => clock.now;
  const backend = makeBackend();
  backend.offline = offline;
  globalThis.fetch = backend.fetch;
  const env = makeChrome(clock);
  globalThis.chrome = env.chrome;
  if (setup) setup(env, backend);
  await import(`../background.js?case=${++importCounter}`);
  return { ...env, clock, backend, flushAll: () => env.send({ action: ACTIONS.MANUAL_UPDATE }) };
}

test.afterEach(() => {
  Date.now = REAL_NOW;
  globalThis.fetch = REAL_FETCH;
  delete globalThis.chrome;
});

const secondsOf = (backend, id, day) => backend.summaries.get(`${id}|${day}`);
const near = (actual, expected, tol = 2) => assert.ok(Math.abs(actual - expected) <= tol, `expected ${actual} within ${tol} of ${expected}`);

test('install registers once and creates the tick alarm; startup does not duplicate', async () => {
  const e = await boot();
  await e.onInstalled.emit({ reason: 'install' });
  assert.equal(e.backend.clients.size, 1);
  const [client] = [...e.backend.clients.values()];
  assert.equal(client.clientType, 'CHROME_EXTENSION');
  assert.equal(client.clientName, 'mac-arm64');
  assert.equal(e.data.extensionId, client.clientId);
  assert.ok(typeof e.data.apiKey === 'string' && e.data.apiKey.length > 0);
  assert.deepEqual(e.alarms.tick, { name: 'tick', periodInMinutes: 1 });
  await e.onStartup.emit();
  assert.equal(e.createdAlarms.length, 1);
  assert.equal(e.backend.count('/register-client'), 1);
});

test('youtube for 3 minutes is throttled, then flushed under the local date as Entertainment', async () => {
  const e = await boot();
  const day = localDate(e.clock.now);
  await e.onInstalled.emit({ reason: 'install' });
  await e.openTab(7, YT);
  for (let i = 0; i < 3; i++) await e.tick();
  assert.equal(e.backend.count('/submit-category-summary'), 0, 'less than 5 minutes since start: no flush yet');
  await e.focus(-1); // user leaves the browser at +3 min
  await e.tick(MIN); await e.tick(MIN); await e.tick(MIN); // now 6 minutes since install
  assert.equal(e.backend.count('/submit-category-summary'), 1);
  const id = e.data.extensionId;
  const s = secondsOf(e.backend, id, day);
  assert.deepEqual(Object.keys(s), ['Entertainment']);
  near(s.Entertainment, 180);
  assert.deepEqual([...e.backend.summaries.keys()], [`${id}|${day}`]);
  assert.ok(e.data.lastFlushMs > 0);
  // Flushed days are no longer dirty: further ticks send nothing.
  await e.tick(10 * MIN);
  assert.equal(e.backend.count('/submit-category-summary'), 1);
});

test('backend database reset: next flush re-registers with the same id and delivers the data', async () => {
  const e = await boot();
  const day = localDate(e.clock.now);
  await e.onInstalled.emit({ reason: 'install' });
  await e.openTab(7, YT);
  for (let i = 0; i < 5; i++) await e.tick();
  const id = e.data.extensionId;
  near(secondsOf(e.backend, id, day).Entertainment, 300);
  const oldKey = e.data.apiKey;

  e.backend.wipe();
  for (let i = 0; i < 5; i++) await e.tick();
  assert.equal(e.data.extensionId, id, 'identity is kept');
  assert.notEqual(e.data.apiKey, oldKey, 'a fresh key was issued');
  assert.equal(e.backend.clients.size, 1);
  assert.equal(e.backend.count('/register-client'), 2);
  near(secondsOf(e.backend, id, day).Entertainment, 600);
});

test('midnight: a session across local midnight lands in two dated summaries', async () => {
  const start = new Date(2026, 9, 13, 23, 58, 0).getTime();
  const e = await boot({ start });
  const d1 = localDate(start);
  const d2 = localDate(new Date(2026, 9, 14, 12, 0, 0).getTime());
  assert.notEqual(d1, d2);
  await e.onInstalled.emit({ reason: 'install' });
  await e.openTab(3, YT);
  for (let i = 0; i < 5; i++) await e.tick(); // 23:59, 00:00, 00:01, 00:02, 00:03
  const r = await e.flushAll();
  assert.equal(r.success, true);
  const id = e.data.extensionId;
  near(secondsOf(e.backend, id, d1).Entertainment, 120);
  near(secondsOf(e.backend, id, d2).Entertainment, 180);
  assert.equal(e.backend.summaries.size, 2);
});

test('messages: manual update, today totals, summary, identity, linking errors', async () => {
  const e = await boot();
  const day = localDate(e.clock.now);
  await e.onInstalled.emit({ reason: 'install' });
  await e.openTab(7, YT);
  await e.tick(2 * MIN);

  const today = await e.send({ action: ACTIONS.GET_TODAY });
  assert.equal(today.success, true);
  near(today.today['youtube.com'], 120);

  const manual = await e.send({ action: ACTIONS.MANUAL_UPDATE });
  assert.equal(manual.success, true);

  const ident = await e.send({ action: ACTIONS.GET_IDENTITY });
  assert.equal(ident.success, true);
  assert.equal(ident.extensionId, e.data.extensionId);

  const sum = await e.send({ action: ACTIONS.GET_SUMMARY, userId: ident.extensionId, day });
  assert.equal(sum.success, true);
  near(sum.summary.Entertainment, 120);
  const none = await e.send({ action: ACTIONS.GET_SUMMARY, userId: ident.extensionId, day: '2020-01-01' });
  assert.deepEqual(none, { success: true, summary: null });

  const bad = await e.send({ action: ACTIONS.COMPLETE_LINKING, code: '000000' });
  assert.deepEqual(bad, { success: false, error: 'Invalid or expired code' });

  const gen = await e.send({ action: ACTIONS.GENERATE_LINKING_CODE });
  assert.equal(gen.success, true);
  assert.match(gen.code, /^\d{6}$/);
  const devices = await e.send({ action: ACTIONS.GET_LINKED_DEVICES });
  assert.deepEqual(devices, { success: true, devices: [] });

  assert.equal(await e.send({ action: 'somethingElse' }), undefined, 'unknown actions are ignored');
});

test('offline: nothing throws, errors are reported, and the next online flush delivers', async () => {
  const e = await boot({ offline: true });
  const day = localDate(e.clock.now);
  await e.onInstalled.emit({ reason: 'install' });
  const id = e.data.extensionId;
  assert.ok(id, 'identity exists even though registration failed');
  assert.equal(e.data.apiKey, undefined);

  await e.openTab(7, YT);
  for (let i = 0; i < 6; i++) await e.tick();
  const manual = await e.send({ action: ACTIONS.MANUAL_UPDATE });
  assert.equal(manual.success, true);
  const links = await e.send({ action: ACTIONS.GET_LINKED_DEVICES });
  assert.equal(links.success, false);
  assert.equal(links.error, 'No connection. Check your internet and try again.');
  assert.equal(e.backend.summaries.size, 0);

  e.backend.offline = false;
  for (let i = 0; i < 5; i++) await e.tick();
  assert.equal(e.data.extensionId, id);
  assert.equal(e.backend.clients.size, 1);
  near(secondsOf(e.backend, id, day).Entertainment, 660);
});

test('closing the active tab stops counting; a closed tab does not break activation', async () => {
  const e = await boot();
  await e.onInstalled.emit({ reason: 'install' });
  await e.openTab(7, YT);
  await e.tick(MIN);
  e.tabs.length = 0;
  await e.onRemoved.emit(7, {});
  await e.tick(MIN); // nothing running
  await e.onActivated.emit({ tabId: 99, windowId: 1 }); // tab already gone: must not throw
  const today = await e.send({ action: ACTIONS.GET_TODAY });
  near(today.today['youtube.com'], 60);
});

// ---------- fix round 1 ----------
const GH = 'https://github.com/x';
const todayOf = async (e) => (await e.send({ action: ACTIONS.GET_TODAY })).today;

test('focus guard: updates in an unfocused window do not count after the browser loses focus', async () => {
  const e = await boot();
  await e.onInstalled.emit({});
  await e.openTab(7, YT);
  await e.tick(MIN);
  await e.focus(-1);
  await e.onUpdated.emit(7, { status: 'complete' }, { id: 7, active: true, windowId: 1, url: YT });
  await e.onUpdated.emit(8, { url: GH }, { id: 8, active: true, windowId: 2, url: GH });
  for (let i = 0; i < 5; i++) await e.tick(MIN);
  const t = await todayOf(e);
  near(t['youtube.com'], 60);
  assert.equal(t['github.com'], undefined);
});

test('focus guard: onActivated for a tab in an unfocused window starts no session', async () => {
  const e = await boot();
  await e.onInstalled.emit({});
  e.tabs.push({ id: 9, url: GH, active: true, windowId: 2 }); // window 1 is the focused one
  await e.onActivated.emit({ tabId: 9, windowId: 2 });
  for (let i = 0; i < 3; i++) await e.tick(MIN);
  assert.deepEqual(await todayOf(e), {});
});

test('focus guard: regaining focus resumes counting; focus on a window without an active tab pauses', async () => {
  const e = await boot();
  await e.onInstalled.emit({});
  await e.openTab(7, YT);
  await e.tick(MIN);
  await e.focus(-1);
  await e.tick(MIN);
  await e.focus(1);
  await e.tick(MIN); await e.tick(MIN);
  near((await todayOf(e))['youtube.com'], 180);
  await e.focus(3); // e.g. a devtools window: no active tab
  await e.tick(MIN); await e.tick(MIN);
  near((await todayOf(e))['youtube.com'], 180);
});

test('a permanently failing past day is retried with the 5-minute backoff, not every minute', async () => {
  const start = new Date(2026, 9, 13, 23, 58, 0).getTime();
  const e = await boot({ start });
  const d1 = localDate(start);
  e.backend.failDays.add(d1);
  await e.onInstalled.emit({});
  await e.openTab(3, YT);
  for (let i = 0; i < 14; i++) await e.tick(MIN); // 23:59 .. 00:12
  const attempts = e.backend.submitAttempts.filter((d) => d === d1).length;
  assert.ok(attempts >= 1 && attempts <= 3, `attempts for the failing day: ${attempts}`);
  assert.equal(e.data.lastFlushOk, false);
});

test('the previous day is flushed right after midnight even when the last flush was recent', async () => {
  const start = new Date(2026, 9, 13, 23, 50, 0).getTime();
  const e = await boot({ start });
  const d1 = localDate(start);
  const d2 = localDate(new Date(2026, 9, 14, 12, 0, 0).getTime());
  await e.onInstalled.emit({});
  await e.openTab(3, YT);
  for (let i = 0; i < 9; i++) await e.tick(MIN); // 23:51 .. 23:59 (flush at 23:55)
  e.clock.now += 30000; // 23:59:30
  assert.equal((await e.send({ action: ACTIONS.MANUAL_UPDATE })).success, true);
  const id = e.data.extensionId;
  near(secondsOf(e.backend, id, d1).Entertainment, 570);
  await e.tick(30000); // 00:00:00, last flush only 30 s ago
  near(secondsOf(e.backend, id, d1).Entertainment, 600);
  for (let i = 0; i < 3; i++) await e.tick(MIN); // 00:01 .. 00:03
  assert.equal(secondsOf(e.backend, id, d2), undefined, "today's day waits for the 5-minute rule");
  assert.equal(e.backend.submitAttempts.filter((d) => d === d2).length, 0);
});

test('startup restores the session before registration finishes', async () => {
  const e = await boot({ setup: (env, b) => {
    env.tabs.push({ id: 5, url: YT, active: true, windowId: 1 });
    b.fetch = () => new Promise(() => {}); // backend never answers
    globalThis.fetch = b.fetch;
  } });
  e.onInstalled.emit({}); // not awaited: registration hangs
  await new Promise((r) => setImmediate(r));
  e.clock.now += MIN;
  near((await todayOf(e))['youtube.com'], 60);
});

test('tick alarm is ensured on every worker wake and never duplicated', async () => {
  const e = await boot();
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(Object.keys(e.alarms), ['tick'], 'created at worker start, before any lifecycle event');
  await e.onInstalled.emit({}); await e.onStartup.emit();
  assert.deepEqual(Object.keys(e.alarms), ['tick']);
  assert.equal(e.createdAlarms.length, 1);
  const pre = await boot({ setup: (env) => { env.alarms.tick = { name: 'tick', periodInMinutes: 1 }; } });
  await new Promise((r) => setImmediate(r));
  assert.equal(pre.createdAlarms.length, 0);
});

test('message arguments are validated before the api is called', async () => {
  const e = await boot();
  await e.onInstalled.emit({});
  const before = e.backend.requests.length;
  const bad = { success: false, error: 'Invalid request.' };
  assert.deepEqual(await e.send({ action: ACTIONS.GET_SUMMARY, userId: '', day: '2026-10-13' }), bad);
  assert.deepEqual(await e.send({ action: ACTIONS.GET_SUMMARY, userId: 'u', day: '2026-10-13&x=1' }), bad);
  assert.deepEqual(await e.send({ action: ACTIONS.GET_SUMMARY, userId: 5, day: '2026-10-13' }), bad);
  assert.deepEqual(await e.send({ action: ACTIONS.COMPLETE_LINKING, code: '12345' }), bad);
  assert.deepEqual(await e.send({ action: ACTIONS.COMPLETE_LINKING, code: 'abcdef' }), bad);
  assert.deepEqual(await e.send({ action: ACTIONS.COMPLETE_LINKING }), bad);
  assert.deepEqual(await e.send({ action: ACTIONS.UNLINK_DEVICE, deviceId: '' }), bad);
  assert.deepEqual(await e.send({ action: ACTIONS.UNLINK_DEVICE }), bad);
  assert.equal(e.backend.requests.length, before, 'no backend call for invalid requests');
});

test('install/startup removes the orphaned 3.0 timeData key and nothing else', async () => {
  const e = await boot({ setup: (env) => {
    env.data.timeData = { 'a.com': 5 };
    env.data.keepMe = 1;
  } });
  await e.onInstalled.emit({});
  assert.equal('timeData' in e.data, false);
  assert.equal(e.data.keepMe, 1);
  assert.ok(e.data.extensionId && e.data.apiKey);
});
