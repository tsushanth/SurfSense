// Network + identity for the extension. No browser extension APIs here: storage and fetch are
// injected so this runs in the service worker, the popup and Node tests.
import { chunk } from './dates.js';

export const BACKEND_BASE = 'https://usage-tracker-backend.fly.dev';
const CLIENT_TYPE = 'CHROME_EXTENSION';
const CATEGORY_BATCH = 100;

export class ApiError extends Error {
  constructor(status, serverMessage = null) {
    super(serverMessage || `Request failed (${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.serverMessage = serverMessage;
  }
}

export function messageFor(error) {
  if (!(error instanceof ApiError)) {
    if (error instanceof TypeError) return 'No connection. Check your internet and try again.';
    return 'Something went wrong.';
  }
  const s = error.status;
  if (s === 0) return 'No connection. Check your internet and try again.';
  if (s === 401 || s === 403) return "This device isn't recognized by the server. Open the extension again to reconnect.";
  if (s === 429) return 'Too many requests. Try again in a minute.';
  if (s >= 500) return 'The server is having trouble. Try again soon.';
  if (s >= 400 && error.serverMessage) return error.serverMessage;
  return 'Something went wrong.';
}

async function readJson(res) {
  try { return await res.json(); } catch { return null; }
}

export function createApi({
  storage,
  fetchImpl,
  base = BACKEND_BASE,
  newId = () => crypto.randomUUID(),
  clientName = () => 'Chrome Extension',
}) {
  async function ownId() {
    const { extensionId } = await storage.get('extensionId');
    return extensionId;
  }

  // Serializes all registration work so concurrent callers never register twice.
  let lock = Promise.resolve();
  function withLock(fn) {
    const run = lock.then(fn, fn);
    lock = run.catch(() => {});
    return run;
  }

  // fetch is resolved at call time so tests/scenarios can swap globalThis.fetch.
  function send(path, init) {
    const f = fetchImpl || globalThis.fetch;
    return f(base + path, init);
  }

  async function registerOnce(id) {
    let res;
    try {
      res = await send('/register-client', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ clientId: id, clientType: CLIENT_TYPE, clientName: clientName() }),
      });
    } catch {
      return { outcome: 'fail' };
    }
    if (!res.ok) return { outcome: 'fail' };
    const body = await readJson(res);
    if (body && typeof body.apiKey === 'string' && body.apiKey) return { outcome: 'ok', apiKey: body.apiKey };
    // 2xx without a key: the server already knows this id and will never reissue its key.
    return { outcome: res.status === 200 && body ? 'burned' : 'fail' };
  }

  // Must be called inside the lock.
  async function registerLocked() {
    const { apiKey } = await storage.get('apiKey');
    if (apiKey) return true;
    for (let attempt = 0; attempt < 2; attempt++) {
      let { extensionId } = await storage.get('extensionId');
      if (!extensionId) {
        extensionId = newId();
        await storage.set({ extensionId }); // persisted before the request
      }
      const r = await registerOnce(extensionId);
      if (r.outcome === 'ok') {
        await storage.set({ apiKey: r.apiKey });
        return true;
      }
      if (r.outcome !== 'burned') return false;
      await storage.remove('extensionId'); // burned id: next loop makes a fresh one
    }
    return false;
  }

  async function ensureRegistered() {
    const { apiKey } = await storage.get('apiKey');
    if (apiKey) return true;
    return withLock(registerLocked);
  }

  // After a 401: returns the key to retry with, or null if re-registration failed.
  function recoverAuth(usedKey) {
    return withLock(async () => {
      const { apiKey: stored } = await storage.get('apiKey');
      if (stored && stored !== usedKey) return stored; // someone already recovered
      await storage.remove('apiKey');
      if (!(await registerLocked())) return null;
      return (await storage.get('apiKey')).apiKey || null;
    });
  }

  // path and body are functions of the current identity, re-evaluated on retry
  // because 401 recovery may have replaced the id.
  async function attempt(pathOf, method, bodyOf, key) {
    const id = await ownId();
    const path = pathOf(id);
    const body = bodyOf ? bodyOf(id) : undefined;
    const init = { method, headers: { Authorization: `Bearer ${key}` } };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    let res;
    try {
      res = await send(path, init);
    } catch {
      throw new ApiError(0, null);
    }
    return res;
  }

  async function request(pathOf, { method = 'POST', body } = {}) {
    if (!(await ensureRegistered())) throw new ApiError(0, null);
    const usedKey = (await storage.get('apiKey')).apiKey;
    let res = await attempt(pathOf, method, body, usedKey);
    if (res.status === 401) {
      const key = await recoverAuth(usedKey);
      if (!key) throw new ApiError(0, null);
      res = await attempt(pathOf, method, body, key);
    }
    const data = await readJson(res);
    if (!res.ok) {
      const msg = data && typeof data.error === 'string' ? data.error : null;
      throw new ApiError(res.status, msg);
    }
    return data;
  }

  return {
    ensureRegistered,

    async getIdentity() {
      return { extensionId: (await ownId()) || null };
    },

    async categorize(domains) {
      const result = {};
      for (const batch of chunk(domains, CATEGORY_BATCH)) {
        const map = await request(() => '/get-category-mapping', { body: () => ({ domains: batch }) });
        for (const d of batch) {
          const c = map && typeof map === 'object' ? map[d] : undefined;
          result[d] = typeof c === 'string' && c ? c : 'Other';
        }
      }
      return result;
    },

    async submitSummary(day, summary) {
      await request(() => '/submit-category-summary', {
        body: (id) => ({ userId: id, timestamp: day, categorySummary: summary }),
      });
    },

    async getSummaryHistory(userId, day) {
      const data = await request(
        () => `/get-summary-history?userId=${encodeURIComponent(userId)}&day=${encodeURIComponent(day)}`,
        { method: 'GET' },
      );
      return Array.isArray(data) ? data : [];
    },

    async initiateLinking() {
      const data = await request(() => '/initiate-linking', { body: (id) => ({ clientId: id }) });
      if (!data || !data.code) throw new ApiError(500, null);
      return String(data.code);
    },

    async completeLinking(code) {
      const data = await request(() => '/complete-linking', { body: (id) => ({ clientId: id, code }) });
      return data && data.linkedWith ? { linkedWith: data.linkedWith } : {};
    },

    async unlinkDevice(otherClientId) {
      await request(() => '/unlink-device', { body: (id) => ({ clientIdA: id, clientIdB: otherClientId }) });
    },

    async getLinkedClients() {
      const data = await request(
        (id) => `/get-linked-clients?clientId=${encodeURIComponent(id)}`,
        { method: 'GET' },
      );
      return (data && data.data && Array.isArray(data.data.clients)) ? data.data.clients : [];
    },
  };
}
