import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Mock supabase and logger before importing
// Chainable fake: each from(table) pops a scripted result (or falls back to {}).
const h = vi.hoisted(() => {
    const state = { results: {}, calls: [] };
    const builder = (table) => {
        const b = {};
        const result = () => {
            const q = state.results[table];
            const r = Array.isArray(q) ? (q.length ? q.shift() : {}) : (q ?? {});
            if (typeof r === 'function') return r(); // scripted function may throw
            return r;
        };
        for (const m of ['select', 'eq', 'in', 'lte', 'lt', 'upsert', 'insert', 'delete', 'single', 'update']) {
            b[m] = (...args) => { state.calls.push({ table, m, args }); return b; };
        }
        b.then = (res, rej) => new Promise((ok) => ok(result())).then(res, rej);
        return b;
    };
    return { state, supabase: { from: (t) => { state.calls.push({ table: t, m: 'from' }); return builder(t); } } };
});

vi.mock('../supabase.js', () => ({ supabase: h.supabase }));

vi.mock('../logger.js', () => ({
    logger: { info: vi.fn(), error: vi.fn(), warn: vi.fn() }
}));

const { generateLinkingCode, getLinkedClients, completeLinking, LinkingError } = await import('../linkingFunctions.js');

describe('generateLinkingCode', () => {
    it('returns a string', () => {
        const code = generateLinkingCode();
        expect(typeof code).toBe('string');
    });

    it('returns exactly 6 digits', () => {
        const code = generateLinkingCode();
        expect(code).toMatch(/^\d{6}$/);
    });

    it('returns a number >= 100000 (no leading zeros)', () => {
        for (let i = 0; i < 50; i++) {
            const code = generateLinkingCode();
            const num = parseInt(code, 10);
            expect(num).toBeGreaterThanOrEqual(100000);
            expect(num).toBeLessThanOrEqual(999999);
        }
    });

    it('generates varying codes (not always the same)', () => {
        const codes = new Set();
        for (let i = 0; i < 20; i++) {
            codes.add(generateLinkingCode());
        }
        expect(codes.size).toBeGreaterThan(1);
    });
});

beforeEach(() => { h.state.results = {}; h.state.calls = []; });

describe('getLinkedClients', () => {
    it('throws when the first link query errors', async () => {
        h.state.results.client_links = [{ error: { message: 'db down' } }, { data: [] }];
        await expect(getLinkedClients('a')).rejects.toThrow('Failed to fetch linked clients: db down');
    });

    it('throws when the second link query errors', async () => {
        h.state.results.client_links = [{ data: [] }, { error: { message: 'db down 2' } }];
        await expect(getLinkedClients('a')).rejects.toThrow('Failed to fetch linked clients: db down 2');
    });

    it('throws when the clients query errors', async () => {
        h.state.results.client_links = [{ data: [{ client_b: 'b' }] }, { data: [] }];
        h.state.results.extension_clients = { error: { message: 'clients down' } };
        await expect(getLinkedClients('a')).rejects.toThrow('Failed to fetch linked clients: clients down');
    });

    it('returns mapped clients on the happy path', async () => {
        h.state.results.client_links = [{ data: [{ client_b: 'b' }] }, { data: [{ client_a: 'c' }] }];
        h.state.results.extension_clients = { data: [
            { client_id: 'b', client_name: 'Phone', client_type: 'IOS' },
            { client_id: 'c', client_name: null, client_type: null },
        ] };
        await expect(getLinkedClients('a')).resolves.toEqual([
            { clientId: 'b', clientName: 'Phone', clientType: 'IOS' },
            { clientId: 'c', clientName: 'Unknown', clientType: 'Unknown' },
        ]);
    });

    it('returns [] without querying extension_clients when there are no links', async () => {
        h.state.results.client_links = [{ data: [] }, { data: [] }];
        await expect(getLinkedClients('a')).resolves.toEqual([]);
        expect(h.state.calls.some(c => c.table === 'extension_clients')).toBe(false);
    });
});

describe('getLinkedClients link activity refresh', () => {
    const happyClients = { data: [{ client_id: 'b', client_name: 'Phone', client_type: 'IOS' }] };
    const expected = [{ clientId: 'b', clientName: 'Phone', clientType: 'IOS' }];

    it('refreshes last_activity_at for both link directions with eq filters', async () => {
        h.state.results.client_links = [{ data: [{ client_b: 'b' }] }, { data: [] }, {}, {}];
        h.state.results.extension_clients = happyClients;
        await getLinkedClients('me');
        const calls = h.state.calls.filter(c => c.table === 'client_links');
        const updates = calls.filter(c => c.m === 'update');
        expect(updates).toHaveLength(2);
        for (const u of updates) {
            expect(typeof u.args[0].last_activity_at).toBe('string');
            expect(new Date(u.args[0].last_activity_at).toISOString()).toBe(u.args[0].last_activity_at);
        }
        const afterUpdate = updates.map(u => calls[calls.indexOf(u) + 1]);
        expect(afterUpdate.map(c => c.m)).toEqual(['eq', 'eq']);
        expect(afterUpdate.map(c => c.args).sort((x, y) => x[0].localeCompare(y[0])))
            .toEqual([['client_a', 'me'], ['client_b', 'me']]);
    });

    it('makes no update when there are no links', async () => {
        h.state.results.client_links = [{ data: [] }, { data: [] }];
        await getLinkedClients('me');
        expect(h.state.calls.some(c => c.table === 'client_links' && c.m === 'update')).toBe(false);
    });

    it('still succeeds and warns when an update returns an error', async () => {
        const { logger } = await import('../logger.js');
        logger.warn.mockClear();
        h.state.results.client_links = [{ data: [{ client_b: 'b' }] }, { data: [] }, { error: { message: 'x' } }, {}];
        h.state.results.extension_clients = happyClients;
        await expect(getLinkedClients('me')).resolves.toEqual(expected);
        expect(logger.warn).toHaveBeenCalled();
        expect(JSON.stringify(logger.warn.mock.calls)).not.toContain('me"');
    });

    it('still succeeds and warns when an update throws', async () => {
        const { logger } = await import('../logger.js');
        logger.warn.mockClear();
        h.state.results.client_links = [{ data: [{ client_b: 'b' }] }, { data: [] }, () => { throw new Error('boom'); }, {}];
        h.state.results.extension_clients = happyClients;
        await expect(getLinkedClients('me')).resolves.toEqual(expected);
        expect(logger.warn).toHaveBeenCalled();
    });

    it('returns identical clients in the happy path', async () => {
        h.state.results.client_links = [{ data: [{ client_b: 'b' }] }, { data: [] }, {}, {}];
        h.state.results.extension_clients = happyClients;
        await expect(getLinkedClients('me')).resolves.toEqual(expected);
    });
});

describe('getLinkedClients link activity deadline', () => {
    const clients = { data: [{ client_id: 'b', client_name: 'Phone', client_type: 'IOS' }] };
    afterEach(() => { vi.useRealTimers(); });

    it('resolves with clients and warns when the update never settles', async () => {
        const { logger } = await import('../logger.js');
        logger.warn.mockClear();
        vi.useFakeTimers();
        h.state.results.client_links = [{ data: [{ client_b: 'b' }] }, { data: [] }, () => new Promise(() => {}), {}];
        h.state.results.extension_clients = clients;
        const p = getLinkedClients('me');
        await vi.advanceTimersByTimeAsync(3000);
        await expect(p).resolves.toEqual([{ clientId: 'b', clientName: 'Phone', clientType: 'IOS' }]);
        expect(logger.warn).toHaveBeenCalledTimes(1);
        expect(logger.warn).toHaveBeenCalledWith('Link activity refresh timed out');
    });

    it('does not produce an unhandled rejection when updates reject after the deadline', async () => {
        vi.useFakeTimers();
        const unhandled = vi.fn();
        process.on('unhandledRejection', unhandled);
        try {
            let rejectLate;
            const late = new Promise((_, rej) => { rejectLate = rej; });
            h.state.results.client_links = [{ data: [{ client_b: 'b' }] }, { data: [] }, () => late, {}];
            h.state.results.extension_clients = clients;
            const p = getLinkedClients('me');
            await vi.advanceTimersByTimeAsync(3000);
            await p;
            rejectLate(new Error('late failure'));
            vi.useRealTimers();
            await new Promise(r => setTimeout(r, 20));
            expect(unhandled).not.toHaveBeenCalled();
        } finally {
            process.off('unhandledRejection', unhandled);
        }
    });
});

describe('completeLinking', () => {
    it('rejects an unknown code with a 400 LinkingError', async () => {
        h.state.results.linking_requests = { error: { code: 'PGRST116', message: 'no rows' } };
        const err = await completeLinking('b-device', '123456').catch(e => e);
        expect(err).toBeInstanceOf(LinkingError);
        expect(err).toMatchObject({ name: 'LinkingError', status: 400, message: 'Invalid or expired code' });
    });

    it('treats a null row with no error as an unknown code (400 LinkingError)', async () => {
        h.state.results.linking_requests = { data: null };
        const err = await completeLinking('b-device', '123456').catch(e => e);
        expect(err).toBeInstanceOf(LinkingError);
        expect(err).toMatchObject({ status: 400, message: 'Invalid or expired code' });
    });

    it('does not map a transient DB lookup error to a 400 LinkingError', async () => {
        h.state.results.linking_requests = { error: { message: 'connection reset' } };
        const err = await completeLinking('b-device', '123456').catch(e => e);
        expect(err).not.toBeInstanceOf(LinkingError);
        expect(err).toBeInstanceOf(Error);
        expect(err.message).toContain('connection reset');
    });

    it('rejects an expired code with a 400 LinkingError and deletes the request', async () => {
        h.state.results.linking_requests = {
            data: { client_id: 'a-device', expires_at: new Date(Date.now() - 1000).toISOString() },
        };
        const err = await completeLinking('b-device', '123456').catch(e => e);
        expect(err).toBeInstanceOf(LinkingError);
        expect(err).toMatchObject({ status: 400, message: 'Code expired' });
        expect(h.state.calls.some(c => c.table === 'linking_requests' && c.m === 'delete')).toBe(true);
    });

    it('rejects self-linking with a 400 LinkingError and does not insert a link', async () => {
        h.state.results.linking_requests = {
            data: { client_id: 'same-device', expires_at: new Date(Date.now() + 60000).toISOString() },
        };
        const err = await completeLinking('same-device', '123456').catch(e => e);
        expect(err).toBeInstanceOf(LinkingError);
        expect(err).toMatchObject({ status: 400, message: 'Cannot link a device to itself' });
        expect(h.state.calls.some(c => c.table === 'client_links')).toBe(false);
        expect(h.state.calls.some(c => c.table === 'linking_requests' && c.m === 'delete')).toBe(false);
    });
});
