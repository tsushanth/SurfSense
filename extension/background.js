// MV3 service worker. The ONLY place that owns the api and tracker instances. All listeners are
// registered synchronously at the top level; state lives in chrome.storage.local so a worker
// restart loses nothing. The popup talks to this file through chrome.runtime.sendMessage.
import { createApi, messageFor } from './lib/api.js';
import { createTracker } from './lib/tracker.js';
import { flushDirty } from './lib/sync.js';
import { localDate } from './lib/dates.js';
import { ACTIONS } from './lib/messages.js';

const ALARM = 'tick';
const FLUSH_INTERVAL_MS = 5 * 60 * 1000;

// chrome.* is resolved at call time (never cached) so tests can swap the global.
const storage = {
  get: (keys) => chrome.storage.local.get(keys),
  set: (obj) => chrome.storage.local.set(obj),
  remove: (keys) => chrome.storage.local.remove(keys),
};

let platformName = 'chrome';
const platformReady = (async () => {
  try {
    const info = await chrome.runtime.getPlatformInfo();
    if (info && info.os && info.arch) platformName = `${info.os}-${info.arch}`;
  } catch { /* keep the fallback */ }
})();

const api = createApi({ storage, clientName: () => platformName });
const tracker = createTracker({ storage });

// ---------- flushing ----------
let flushChain = Promise.resolve();
function flushNow() {
  const run = flushChain.then(async () => {
    let ok = false;
    try {
      const result = await flushDirty({ api, tracker });
      ok = result.failed.length === 0;
      return result;
    } finally {
      try { await storage.set({ lastFlushMs: Date.now(), lastFlushOk: ok }); } catch { /* ignore */ }
    }
  });
  flushChain = run.catch(() => {});
  return run;
}

async function onTick() {
  await tracker.tick();
  const { dirty } = await tracker.snapshot();
  const days = Object.keys(dirty);
  if (days.length === 0) return;
  const now = Date.now();
  const { lastFlushMs, lastFlushOk } = await storage.get(['lastFlushMs', 'lastFlushOk']);
  const due = !Number.isFinite(lastFlushMs) || now - lastFlushMs >= FLUSH_INTERVAL_MS;
  // A finished day is sent at once, but only while the previous attempt did not fail:
  // after a failure everything waits for the regular 5-minute backoff.
  const pastDay = lastFlushOk !== false && days.some((d) => d !== localDate(now));
  if (due || pastDay) await flushNow();
}

// ---------- lifecycle ----------
// Idempotent and shared: one get-then-create per worker lifetime.
let alarmReady = null;
function ensureAlarm() {
  alarmReady ??= (async () => {
    const existing = await chrome.alarms.get(ALARM);
    if (!existing) chrome.alarms.create(ALARM, { periodInMinutes: 1 });
  })().catch(() => { alarmReady = null; });
  return alarmReady;
}
ensureAlarm();

// Bumped by every focus change / tab removal. A tab handler captures it before its first await and
// drops its tracker call if it changed meanwhile (a stale activate would otherwise start a session
// after the browser lost focus).
let focusEpoch = 0;

// Tracking only counts while the tab's window has the focus.
async function windowFocused(windowId) {
  try {
    const w = await chrome.windows.get(windowId);
    return !!(w && w.focused);
  } catch { return false; }
}

async function startUp() {
  const epoch = focusEpoch;
  await platformReady;
  await ensureAlarm();
  try { await chrome.storage.local.remove('timeData'); } catch { /* orphaned 3.0 key, best effort */ }
  const { lastFlushMs } = await storage.get('lastFlushMs');
  if (!Number.isFinite(lastFlushMs)) await storage.set({ lastFlushMs: Date.now() });
  try {
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (tab && await windowFocused(tab.windowId) && epoch === focusEpoch) await tracker.activate(tab.id, tab.url);
  } catch { /* no window yet */ }
  // After tracking is restored: a slow or offline backend must not delay it.
  try { await api.ensureRegistered(); } catch { /* retried on the next API call */ }
}

async function getTab(tabId) {
  try { return (await chrome.tabs.get(tabId)) || null; } catch { return null; }
}

chrome.runtime.onInstalled.addListener(() => startUp().catch(() => {}));
chrome.runtime.onStartup.addListener(() => startUp().catch(() => {}));

chrome.alarms.onAlarm.addListener((alarm) => {
  if (!alarm || alarm.name !== ALARM) return undefined;
  return onTick().catch(() => {});
});

// ---------- tab / window events ----------
chrome.tabs.onActivated.addListener(({ tabId }) => (async () => {
  const epoch = focusEpoch;
  const tab = await getTab(tabId);
  if (tab && await windowFocused(tab.windowId) && epoch === focusEpoch) await tracker.activate(tabId, tab.url);
})().catch(() => {}));

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => (async () => {
  const epoch = focusEpoch;
  if (!tab || !tab.active) return;
  if (changeInfo.status !== 'complete' && !changeInfo.url) return;
  if (!(await windowFocused(tab.windowId)) || epoch !== focusEpoch) return;
  await tracker.navigate(tabId, tab.url);
})().catch(() => {}));

chrome.tabs.onRemoved.addListener((tabId) => {
  focusEpoch++;
  // navigate() ignores tabs other than the running one; a null URL closes that session.
  return tracker.navigate(tabId, null).catch(() => {});
});

chrome.windows.onFocusChanged.addListener((windowId) => (async () => {
  const epoch = ++focusEpoch;
  if (windowId === chrome.windows.WINDOW_ID_NONE) {
    await tracker.pause();
    return;
  }
  const [tab] = await chrome.tabs.query({ active: true, windowId });
  if (epoch !== focusEpoch) return; // a newer focus change owns the state
  if (tab) await tracker.activate(tab.id, tab.url);
  else await tracker.pause(); // e.g. a devtools or popup window
})().catch(() => {}));

// ---------- messages from the popup ----------
const INVALID = Symbol('invalid');
const isId = (v) => typeof v === 'string' && v.length > 0;
const need = (ok) => { if (!ok) throw INVALID; };

const handlers = {
  [ACTIONS.MANUAL_UPDATE]: async () => {
    await platformReady;
    const { failed } = await flushNow();
    return { failed: failed.length };
  },
  [ACTIONS.GET_TODAY]: async () => ({ today: await tracker.getToday() }),
  [ACTIONS.GENERATE_LINKING_CODE]: async () => { await platformReady; return { code: await api.initiateLinking() }; },
  [ACTIONS.COMPLETE_LINKING]: async (m) => { need(typeof m.code === 'string' && /^\d{6}$/.test(m.code)); await platformReady; return api.completeLinking(m.code); },
  [ACTIONS.UNLINK_DEVICE]: async (m) => { need(isId(m.deviceId)); await platformReady; await api.unlinkDevice(m.deviceId); return {}; },
  [ACTIONS.GET_LINKED_DEVICES]: async () => { await platformReady; return { devices: await api.getLinkedClients() }; },
  [ACTIONS.GET_SUMMARY]: async (m) => {
    need(isId(m.userId) && typeof m.day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(m.day));
    await platformReady;
    const day = m.day;
    const list = await api.getSummaryHistory(m.userId, day);
    const hit = list.find((s) => s && typeof s.timestamp === 'string' && s.timestamp.startsWith(day));
    return { summary: (hit && hit.summary) || null };
  },
  [ACTIONS.GET_IDENTITY]: async () => {
    await platformReady;
    try { await api.ensureRegistered(); } catch { /* identity may still exist */ }
    return { extensionId: (await api.getIdentity()).extensionId };
  },
};

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  const handler = message && Object.hasOwn(handlers, message.action) ? handlers[message.action] : null;
  if (!handler) return false;
  // manualUpdateUsage must also bank the running session (flushDirty ticks first).
  Promise.resolve()
    .then(() => handler(message))
    .then((result) => sendResponse({ success: true, ...(result || {}) }))
    .catch((e) => sendResponse({ success: false, error: e === INVALID ? 'Invalid request.' : messageFor(e) }));
  return true;
});
