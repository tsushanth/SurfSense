// Persisted time tracker. All state lives in storage (chrome.storage.local in the worker, a fake in
// tests) so a killed MV3 service worker loses nothing. Storage and clock are injected.
import { localDate, startOfNextLocalDay } from './dates.js';

export const MAX_GAP_MS = 150000;

// Adds to an own key of a plain object. Hostnames such as `constructor` or `__proto__` must neither
// read inherited values nor touch the prototype, so reads use hasOwn and writes define the property.
function addOwn(obj, key, n) {
  const prev = Object.hasOwn(obj, key) ? obj[key] : 0;
  Object.defineProperty(obj, key, { value: prev + n, writable: true, enumerable: true, configurable: true });
}
const KEEP_DAYS = 3;
const KEYS = ['session', 'days', 'dirty', 'rev'];

// Hostname only (never path/query/title), lowercased, leading "www." removed, http(s) only.
export function domainOf(url) {
  if (typeof url !== 'string' || !url) return null;
  let u;
  try { u = new URL(url); } catch { return null; }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
  const host = u.hostname.toLowerCase().replace(/^www\./, '');
  return host || null;
}

export function createTracker({ storage, now = () => Date.now() }) {
  // Every public operation runs through this chain; a rejection is returned to its caller but
  // does not stop later operations.
  let chain = Promise.resolve();
  function serial(fn) {
    const run = chain.then(fn);
    chain = run.catch(() => {});
    return run;
  }

  async function load() {
    const s = await storage.get(KEYS);
    return {
      session: s.session ?? null,
      days: s.days ?? {},
      dirty: s.dirty ?? {},
      rev: Number.isInteger(s.rev) ? s.rev : 0,
    };
  }

  // Banks the running session into `st` (mutating it). Returns true when anything changed.
  function bank(st) {
    const sess = st.session;
    if (!sess) return false;
    const t = now();
    const elapsed = t - sess.lastMs;
    if (elapsed === 0) return false;
    if (elapsed < 0) {
      // Clock moved backwards: count nothing, resume from the corrected clock.
      st.session = { ...sess, lastMs: t };
      return true;
    }
    const counted = Math.min(elapsed, MAX_GAP_MS);
    const end = sess.lastMs + counted;
    let cursor = sess.lastMs;
    let carry = Number.isFinite(sess.carryMs) ? sess.carryMs : 0;
    while (cursor < end) {
      const segEnd = Math.min(startOfNextLocalDay(cursor), end);
      const total = (segEnd - cursor) + carry;
      const secs = Math.floor(total / 1000);
      carry = total - secs * 1000;
      if (secs > 0) {
        const day = localDate(cursor);
        const m = (st.days[day] ??= {});
        addOwn(m, sess.domain, secs);
        st.rev += 1;
        st.dirty[day] = st.rev;
      }
      cursor = segEnd;
    }
    st.session = { ...sess, lastMs: t, carryMs: carry };
    return true;
  }

  async function save(st) {
    await storage.set({ session: st.session, days: st.days, dirty: st.dirty, rev: st.rev });
  }

  function open(st, tabId, url) {
    const domain = domainOf(url);
    st.session = domain ? { tabId, domain, lastMs: now(), carryMs: 0 } : null;
  }

  return {
    activate: (tabId, url) => serial(async () => {
      const st = await load();
      bank(st);
      open(st, tabId, url);
      await save(st);
    }),

    navigate: (tabId, url) => serial(async () => {
      const st = await load();
      if (st.session && st.session.tabId !== tabId) return;
      bank(st);
      open(st, tabId, url);
      await save(st);
    }),

    pause: () => serial(async () => {
      const st = await load();
      bank(st);
      st.session = null;
      await save(st);
    }),

    tick: () => serial(async () => {
      const st = await load();
      if (bank(st)) await save(st);
    }),

    snapshot: () => serial(async () => {
      const st = await load();
      return structuredClone({ days: st.days, dirty: st.dirty });
    }),

    markFlushed: (day, rev) => serial(async () => {
      const st = await load();
      if (st.dirty[day] === rev) delete st.dirty[day];
      const d = new Date(now());
      const cutoff = localDate(new Date(d.getFullYear(), d.getMonth(), d.getDate() - KEEP_DAYS, 12).getTime());
      for (const k of Object.keys(st.days)) {
        if (k < cutoff && !(k in st.dirty)) delete st.days[k];
      }
      await save(st);
    }),

    getToday: () => serial(async () => {
      const st = await load();
      if (bank(st)) await save(st);
      return structuredClone(st.days[localDate(now())] ?? {});
    }),
  };
}
