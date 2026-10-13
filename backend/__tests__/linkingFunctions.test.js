import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock supabase and logger before importing
// Chainable fake: each from(table) pops a scripted result (or falls back to {}).
const h = vi.hoisted(() => {
    const state = { results: {}, calls: [] };
    const builder = (table) => {
        const b = {};
        const result = () => {
            const q = state.results[table];
            return Array.isArray(q) ? (q.length ? q.shift() : {}) : (q ?? {});
        };
        for (const m of ['select', 'eq', 'in', 'lte', 'lt', 'upsert', 'insert', 'delete', 'single']) {
            b[m] = (...args) => { state.calls.push({ table, m, args }); return b; };
        }
        b.then = (res, rej) => Promise.resolve(result()).then(res, rej);
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
