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
    try {
      return await flushDirty({ api, tracker });
    } finally {
      try { await storage.set({ lastFlushMs: Date.now() }); } catch { /* ignore */ }
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
  const { lastFlushMs } = await storage.get('lastFlushMs');
  const due = !Number.isFinite(lastFlushMs) || now - lastFlushMs >= FLUSH_INTERVAL_MS;
  const today = localDate(now);
  if (due || days.some((d) => d !== today)) await flushNow();
}

// ---------- lifecycle ----------
async function startUp() {
  await platformReady;
  const existing = await chrome.alarms.get(ALARM);
  if (!existing) chrome.alarms.create(ALARM, { periodInMinutes: 1 });
  const { lastFlushMs } = await storage.get('lastFlushMs');
  if (!Number.isFinite(lastFlushMs)) await storage.set({ lastFlushMs: Date.now() });
  try { await api.ensureRegistered(); } catch { /* retried on the next API call */ }
  try {
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (tab) await tracker.activate(tab.id, tab.url);
  } catch { /* no window yet */ }
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
  const tab = await getTab(tabId);
  if (tab) await tracker.activate(tabId, tab.url);
})().catch(() => {}));

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (!tab || !tab.active) return undefined;
  if (changeInfo.status !== 'complete' && !changeInfo.url) return undefined;
  return tracker.navigate(tabId, tab.url).catch(() => {});
});

chrome.tabs.onRemoved.addListener((tabId) =>
  // navigate() ignores tabs other than the running one; a null URL closes that session.
  tracker.navigate(tabId, null).catch(() => {}));

chrome.windows.onFocusChanged.addListener((windowId) => (async () => {
  if (windowId === chrome.windows.WINDOW_ID_NONE) {
    await tracker.pause();
    return;
  }
  const [tab] = await chrome.tabs.query({ active: true, windowId });
  if (tab) await tracker.activate(tab.id, tab.url);
})().catch(() => {}));

// ---------- messages from the popup ----------
const handlers = {
  [ACTIONS.MANUAL_UPDATE]: async () => {
    await platformReady;
    const { failed } = await flushNow();
    return { failed: failed.length };
  },
  [ACTIONS.GET_TODAY]: async () => ({ today: await tracker.getToday() }),
  [ACTIONS.GENERATE_LINKING_CODE]: async () => { await platformReady; return { code: await api.initiateLinking() }; },
  [ACTIONS.COMPLETE_LINKING]: async (m) => { await platformReady; return api.completeLinking(String(m.code ?? '')); },
  [ACTIONS.UNLINK_DEVICE]: async (m) => { await platformReady; await api.unlinkDevice(String(m.deviceId ?? '')); return {}; },
  [ACTIONS.GET_LINKED_DEVICES]: async () => { await platformReady; return { devices: await api.getLinkedClients() }; },
  [ACTIONS.GET_SUMMARY]: async (m) => {
    await platformReady;
    const day = String(m.day ?? '');
    const list = await api.getSummaryHistory(String(m.userId ?? ''), day);
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
    .catch((e) => sendResponse({ success: false, error: messageFor(e) }));
  return true;
});
