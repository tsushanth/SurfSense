import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApi, ApiError, messageFor, BACKEND_BASE } from '../lib/api.js';

function fakeStorage(initial = {}) {
  const data = { ...initial };
  return {
    data,
    async get(keys) {
      const list = Array.isArray(keys) ? keys : [keys];
      const out = {};
      for (const k of list) if (k in data) out[k] = data[k];
      return out;
    },
    async set(obj) { Object.assign(data, obj); },
    async remove(keys) { for (const k of Array.isArray(keys) ? keys : [keys]) delete data[k]; },
  };
}

const json = (status, body) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

// handler(call) => response | throws. Records every call.
function fakeFetch(handler) {
  const calls = [];
  const fn = async (url, opts = {}) => {
    const call = {
      url,
      method: opts.method || 'GET',
      headers: opts.headers || {},
      body: opts.body ? JSON.parse(opts.body) : undefined,
    };
    calls.push(call);
    return handler(call, calls.length);
  };
  fn.calls = calls;
  return fn;
}

const makeIds = () => { let n = 0; return () => `id-${++n}`; };

function setup({ handler, storage = fakeStorage() } = {}) {
  const fetchImpl = fakeFetch(handler);
  const api = createApi({ storage, fetchImpl, newId: makeIds(), clientName: () => 'My Chrome' });
  return { api, storage, fetchImpl, calls: fetchImpl.calls };
}

const registers = (calls) => calls.filter((c) => c.url.endsWith('/register-client'));

test('BACKEND_BASE has no trailing slash', () => {
  assert.equal(BACKEND_BASE, 'https://usage-tracker-backend.fly.dev');
});

test('first registration stores id and key and sends CHROME_EXTENSION', async () => {
  const { api, storage, calls } = setup({ handler: () => json(201, { apiKey: 'K1' }) });
  assert.equal(await api.ensureRegistered(), true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, `${BACKEND_BASE}/register-client`);
  assert.equal(calls[0].method, 'POST');
  assert.deepEqual(calls[0].body, { clientId: 'id-1', clientType: 'CHROME_EXTENSION', clientName: 'My Chrome' });
  assert.equal(storage.data.extensionId, 'id-1');
  assert.equal(storage.data.apiKey, 'K1');
});

test('id is persisted before the registration request is sent', async () => {
  const storage = fakeStorage();
  let seen;
  const { api } = setup({ storage, handler: () => { seen = storage.data.extensionId; return json(201, { apiKey: 'K' }); } });
  await api.ensureRegistered();
  assert.equal(seen, 'id-1');
});

test('already registered: no network', async () => {
  const { api, calls } = setup({ storage: fakeStorage({ extensionId: 'x', apiKey: 'K' }), handler: () => { throw new Error('no'); } });
  assert.equal(await api.ensureRegistered(), true);
  assert.equal(calls.length, 0);
});

test('offline first call returns false, keeps id, next call registers with same id', async () => {
  let offline = true;
  const { api, storage, calls } = setup({
    handler: () => { if (offline) throw new TypeError('Failed to fetch'); return json(201, { apiKey: 'K' }); },
  });
  assert.equal(await api.ensureRegistered(), false);
  assert.equal(storage.data.extensionId, 'id-1');
  assert.equal(storage.data.apiKey, undefined);
  offline = false;
  assert.equal(await api.ensureRegistered(), true);
  assert.equal(registers(calls).length, 2);
  assert.equal(calls[1].body.clientId, 'id-1');
  assert.equal(storage.data.apiKey, 'K');
});

test('non-2xx registration returns false and keeps the id', async () => {
  const { api, storage } = setup({ handler: () => json(503, { error: 'down' }) });
  assert.equal(await api.ensureRegistered(), false);
  assert.equal(storage.data.extensionId, 'id-1');
});

test('registration with a non-JSON body returns false without losing the id', async () => {
  const bad = { ok: true, status: 201, json: async () => { throw new SyntaxError('bad json'); } };
  const { api, storage } = setup({ handler: () => bad });
  assert.equal(await api.ensureRegistered(), false);
  assert.equal(storage.data.extensionId, 'id-1');
  assert.equal(storage.data.apiKey, undefined);
});

test('200 without apiKey burns the id: new id, second registration', async () => {
  const { api, storage, calls } = setup({
    handler: (c, n) => (n === 1 ? json(200, { success: true }) : json(201, { apiKey: 'K2' })),
  });
  assert.equal(await api.ensureRegistered(), true);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].body.clientId, 'id-1');
  assert.equal(calls[1].body.clientId, 'id-2');
  assert.equal(storage.data.extensionId, 'id-2');
  assert.equal(storage.data.apiKey, 'K2');
});

test('200 without apiKey twice gives up (one retry only)', async () => {
  const { api, calls } = setup({ handler: () => json(200, {}) });
  assert.equal(await api.ensureRegistered(), false);
  assert.equal(calls.length, 2);
});

test('two concurrent ensureRegistered calls make ONE register request', async () => {
  const { api, calls } = setup({ handler: async () => { await new Promise((r) => setTimeout(r, 10)); return json(201, { apiKey: 'K' }); } });
  const [a, b, c] = await Promise.all([api.ensureRegistered(), api.ensureRegistered(), api.ensureRegistered()]);
  assert.deepEqual([a, b, c], [true, true, true]);
  assert.equal(registers(calls).length, 1);
});

test('authenticated call sends bearer header and registers first when needed', async () => {
  const { api, calls } = setup({
    handler: (c) => (c.url.endsWith('/register-client') ? json(201, { apiKey: 'K' }) : json(200, { success: true, code: '123456' })),
  });
  assert.equal(await api.initiateLinking(), '123456');
  assert.equal(calls.length, 2);
  assert.equal(calls[1].headers.Authorization, 'Bearer K');
  assert.deepEqual(calls[1].body, { clientId: 'id-1' });
});

test('authenticated call while offline throws ApiError(0) and sends nothing else', async () => {
  const { api, calls } = setup({ handler: () => { throw new TypeError('Failed to fetch'); } });
  await assert.rejects(api.initiateLinking(), (e) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 0);
    assert.equal(messageFor(e), 'No connection. Check your internet and try again.');
    return true;
  });
  assert.equal(calls.length, 1);
  assert.ok(calls[0].url.endsWith('/register-client'));
});

test('network error on an authenticated request becomes ApiError(0)', async () => {
  const { api } = setup({
    storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }),
    handler: () => { throw new TypeError('Failed to fetch'); },
  });
  await assert.rejects(api.getLinkedClients(), (e) => e instanceof ApiError && e.status === 0 && e.serverMessage === null);
});

test('stale key: 401 -> one re-register with the SAME id -> retried with new bearer -> success', async () => {
  const storage = fakeStorage({ extensionId: 'me', apiKey: 'OLD' });
  const { api, calls } = setup({
    storage,
    handler: (c) => {
      if (c.url.endsWith('/register-client')) return json(201, { apiKey: 'NEW' });
      return c.headers.Authorization === 'Bearer NEW' ? json(200, { success: true, code: '654321' }) : json(401, { error: 'bad key' });
    },
  });
  assert.equal(await api.initiateLinking(), '654321');
  assert.equal(calls.length, 3);
  assert.equal(calls[0].headers.Authorization, 'Bearer OLD');
  assert.equal(calls[1].body.clientId, 'me');
  assert.equal(calls[2].headers.Authorization, 'Bearer NEW');
  assert.equal(storage.data.apiKey, 'NEW');
  assert.equal(storage.data.extensionId, 'me');
});

test('401 where the backend knows the id (200 w/o key): new id, then retry succeeds', async () => {
  const storage = fakeStorage({ extensionId: 'me', apiKey: 'OLD' });
  let regs = 0;
  const { api, calls } = setup({
    storage,
    handler: (c) => {
      if (c.url.endsWith('/register-client')) return ++regs === 1 ? json(200, { success: true }) : json(201, { apiKey: 'NEW' });
      return c.headers.Authorization === 'Bearer NEW' ? json(200, { success: true, data: { count: 0, clients: [] } }) : json(401, {});
    },
  });
  assert.deepEqual(await api.getLinkedClients(), []);
  assert.equal(storage.data.extensionId, 'id-1');
  const lastGet = calls[calls.length - 1];
  assert.ok(lastGet.url.includes('clientId=id-1'));
});

test('retried call also 401 -> ApiError(401), no third request to the endpoint', async () => {
  const { api, calls } = setup({
    storage: fakeStorage({ extensionId: 'me', apiKey: 'OLD' }),
    handler: (c) => (c.url.endsWith('/register-client') ? json(201, { apiKey: 'NEW' }) : json(401, { error: 'nope' })),
  });
  await assert.rejects(api.initiateLinking(), (e) => e instanceof ApiError && e.status === 401);
  assert.equal(calls.filter((c) => c.url.endsWith('/initiate-linking')).length, 2);
  assert.equal(registers(calls).length, 1);
});

test('401 when another caller already refreshed the key: no register, retry with stored key', async () => {
  const storage = fakeStorage({ extensionId: 'me', apiKey: 'OLD' });
  const { api, calls } = setup({
    storage,
    handler: (c) => {
      if (c.headers.Authorization === 'Bearer OLD') {
        storage.data.apiKey = 'FRESH'; // someone else recovered meanwhile
        return json(401, {});
      }
      return json(200, { success: true, code: '111111' });
    },
  });
  assert.equal(await api.initiateLinking(), '111111');
  assert.equal(registers(calls).length, 0);
  assert.equal(calls[1].headers.Authorization, 'Bearer FRESH');
});

test('concurrent authenticated calls all hitting 401 cause exactly ONE re-registration', async () => {
  const storage = fakeStorage({ extensionId: 'me', apiKey: 'OLD' });
  const { api, calls } = setup({
    storage,
    handler: async (c) => {
      await new Promise((r) => setTimeout(r, 5));
      if (c.url.endsWith('/register-client')) return json(201, { apiKey: 'NEW' });
      return c.headers.Authorization === 'Bearer NEW' ? json(200, { success: true, data: { count: 0, clients: [] } }) : json(401, {});
    },
  });
  const results = await Promise.all([api.getLinkedClients(), api.getLinkedClients(), api.getLinkedClients(), api.getLinkedClients()]);
  assert.equal(results.length, 4);
  assert.equal(registers(calls).length, 1);
  assert.equal(storage.data.extensionId, 'me');
});

test('401 recovery while offline throws ApiError(0) and keeps the id', async () => {
  const storage = fakeStorage({ extensionId: 'me', apiKey: 'OLD' });
  const { api } = setup({
    storage,
    handler: (c) => { if (c.url.endsWith('/register-client')) throw new TypeError('offline'); return json(401, {}); },
  });
  await assert.rejects(api.initiateLinking(), (e) => e instanceof ApiError && e.status === 0);
  assert.equal(storage.data.extensionId, 'me');
});

test('401 on /register-client is not retried and returns false', async () => {
  const { api, calls } = setup({ handler: () => json(401, { error: 'x' }) });
  assert.equal(await api.ensureRegistered(), false);
  assert.equal(calls.length, 1);
});

test('categorize with 250 domains makes three requests of 100/100/50', async () => {
  const domains = Array.from({ length: 250 }, (_, i) => `d${i}.com`);
  const { api, calls } = setup({
    storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }),
    handler: (c) => json(200, Object.fromEntries(c.body.domains.map((d) => [d, 'News']))),
  });
  const out = await api.categorize(domains);
  assert.deepEqual(calls.map((c) => c.body.domains.length), [100, 100, 50]);
  assert.ok(calls.every((c) => c.url === `${BACKEND_BASE}/get-category-mapping`));
  assert.equal(Object.keys(out).length, 250);
  assert.ok(domains.every((d) => out[d] === 'News'));
});

test('categorize fills domains missing from the answer with Other, never Uncategorized', async () => {
  const { api } = setup({
    storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }),
    handler: () => json(200, { 'a.com': 'Shopping' }),
  });
  assert.deepEqual(await api.categorize(['a.com', 'b.com']), { 'a.com': 'Shopping', 'b.com': 'Other' });
});

test('categorize of an empty list sends nothing', async () => {
  const { api, calls } = setup({ handler: () => json(200, {}) });
  assert.deepEqual(await api.categorize([]), {});
  assert.equal(calls.length, 0);
});

test('categorize throws ApiError when a batch is rejected', async () => {
  const { api } = setup({
    storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }),
    handler: () => json(400, { error: 'Too many domains' }),
  });
  await assert.rejects(api.categorize(['a.com']), (e) => e instanceof ApiError && e.status === 400 && e.serverMessage === 'Too many domains');
});

test('submitSummary posts {userId, timestamp: day, categorySummary}', async () => {
  const { api, calls } = setup({ storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }), handler: () => json(200, { success: true }) });
  await api.submitSummary('2026-10-12', { News: 120, Other: 30 });
  assert.equal(calls[0].url, `${BACKEND_BASE}/submit-category-summary`);
  assert.equal(calls[0].method, 'POST');
  assert.deepEqual(calls[0].body, { userId: 'me', timestamp: '2026-10-12', categorySummary: { News: 120, Other: 30 } });
});

test('getSummaryHistory builds the exact query and returns the array', async () => {
  const rows = [{ timestamp: 1, userId: 'other', summary: { News: 5 } }];
  const { api, calls } = setup({ storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }), handler: () => json(200, rows) });
  assert.deepEqual(await api.getSummaryHistory('other', '2026-10-12'), rows);
  assert.equal(calls[0].method, 'GET');
  assert.equal(calls[0].url, `${BACKEND_BASE}/get-summary-history?userId=other&day=2026-10-12`);
  assert.equal(calls[0].body, undefined);
});

test('getSummaryHistory returns [] for a non-array body', async () => {
  const { api } = setup({ storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }), handler: () => json(200, {}) });
  assert.deepEqual(await api.getSummaryHistory('o', '2026-10-12'), []);
});

test('completeLinking sends own id and code, returns linkedWith', async () => {
  const { api, calls } = setup({ storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }), handler: () => json(200, { success: true, linkedWith: 'phone' }) });
  assert.deepEqual(await api.completeLinking('123456'), { linkedWith: 'phone' });
  assert.equal(calls[0].url, `${BACKEND_BASE}/complete-linking`);
  assert.deepEqual(calls[0].body, { clientId: 'me', code: '123456' });
});

test('completeLinking 400 exposes the server message', async () => {
  const { api } = setup({
    storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }),
    handler: () => json(400, { success: false, error: 'Invalid or expired code' }),
  });
  await assert.rejects(api.completeLinking('000000'), (e) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 400);
    assert.equal(e.serverMessage, 'Invalid or expired code');
    assert.equal(messageFor(e), 'Invalid or expired code');
    return true;
  });
});

test('unlinkDevice sends {clientIdA: own, clientIdB: other}', async () => {
  const { api, calls } = setup({ storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }), handler: () => json(200, { success: true }) });
  await api.unlinkDevice('phone');
  assert.equal(calls[0].url, `${BACKEND_BASE}/unlink-device`);
  assert.deepEqual(calls[0].body, { clientIdA: 'me', clientIdB: 'phone' });
});

test('getLinkedClients passes clientId and unwraps data.clients', async () => {
  const clients = [{ clientId: 'p', clientName: 'Pixel', clientType: 'ANDROID' }];
  const { api, calls } = setup({
    storage: fakeStorage({ extensionId: 'me', apiKey: 'K' }),
    handler: () => json(200, { success: true, data: { count: 1, clients } }),
  });
  assert.deepEqual(await api.getLinkedClients(), clients);
  assert.equal(calls[0].url, `${BACKEND_BASE}/get-linked-clients?clientId=me`);
});

test('getIdentity returns the stored id or null without any network', async () => {
  const a = setup({ handler: () => { throw new Error('no'); } });
  assert.deepEqual(await a.api.getIdentity(), { extensionId: null });
  const b = setup({ storage: fakeStorage({ extensionId: 'me' }), handler: () => { throw new Error('no'); } });
  assert.deepEqual(await b.api.getIdentity(), { extensionId: 'me' });
  assert.equal(a.calls.length + b.calls.length, 0);
});

test('fetch is resolved lazily from globalThis when fetchImpl is not supplied', async () => {
  const original = globalThis.fetch;
  const seen = [];
  try {
    const api = createApi({ storage: fakeStorage(), newId: makeIds(), clientName: () => 'c' });
    globalThis.fetch = async (url) => { seen.push(url); return json(201, { apiKey: 'K' }); };
    assert.equal(await api.ensureRegistered(), true);
    assert.deepEqual(seen, [`${BACKEND_BASE}/register-client`]);
  } finally {
    globalThis.fetch = original;
  }
});

test('messageFor table', () => {
  assert.equal(messageFor(new ApiError(0, null)), 'No connection. Check your internet and try again.');
  assert.equal(messageFor(new TypeError('Failed to fetch')), 'No connection. Check your internet and try again.');
  const unrecognized = "This device isn't recognized by the server. Open the extension again to reconnect.";
  assert.equal(messageFor(new ApiError(401, null)), unrecognized);
  assert.equal(messageFor(new ApiError(403, 'clientId does not match')), unrecognized);
  assert.equal(messageFor(new ApiError(429, 'slow down')), 'Too many requests. Try again in a minute.');
  assert.equal(messageFor(new ApiError(503, null)), 'The server is having trouble. Try again soon.');
  assert.equal(messageFor(new ApiError(400, null)), 'Something went wrong.');
  assert.equal(messageFor(new ApiError(404, 'Not here')), 'Not here');
  assert.equal(messageFor(new Error('weird')), 'Something went wrong.');
});
