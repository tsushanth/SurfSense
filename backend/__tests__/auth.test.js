import { describe, it, expect, vi } from 'vitest';

// Each test sets lookup.result to what the API key query resolves with
const lookup = vi.hoisted(() => ({ result: {} }));

// Mock supabase before importing auth (auth.js imports supabase.js transitively)
vi.mock('../supabase.js', () => ({
    supabase: {
        from: () => ({ select: () => ({ eq: () => ({ single: async () => lookup.result }) }) }),
    }
}));

vi.mock('../logger.js', () => ({
    logger: { info: vi.fn(), error: vi.fn(), warn: vi.fn() }
}));

vi.mock('../failureReporter.js', () => ({ reportFailure: vi.fn() }));

const { hashApiKey, generateApiKey, authenticateClient } = await import('../middleware/auth.js');
const { logger } = await import('../logger.js');
const { reportFailure } = await import('../failureReporter.js');

describe('authenticateClient', () => {
    async function authenticate(result) {
        lookup.result = result;
        const req = { headers: { authorization: `Bearer ${'a'.repeat(64)}` } };
        const res = { status: vi.fn().mockReturnThis(), json: vi.fn() };
        const next = vi.fn();
        await authenticateClient(req, res, next);
        return { req, res, next };
    }

    it('passes a known key through with the client attached', async () => {
        const { req, res, next } = await authenticate({
            data: { client_id: 'client-1', client_type: 'android' }, error: null,
        });
        expect(next).toHaveBeenCalledOnce();
        expect(res.status).not.toHaveBeenCalled();
        expect(req).toMatchObject({ clientId: 'client-1', clientType: 'android' });
    });

    it('answers 401 when the lookup finds no row', async () => {
        for (const error of [{ code: 'PGRST116' }, null]) {
            const { res, next } = await authenticate({ data: null, error });
            expect(res.status).toHaveBeenCalledWith(401);
            expect(res.json).toHaveBeenCalledWith({ error: 'Invalid API key', code: 'AUTH_INVALID_KEY' });
            expect(next).not.toHaveBeenCalled();
        }
    });

    // Regression: a 401 makes the Android app drop its key and register as a new identity
    it('answers 503, not 401, when the lookup itself fails', async () => {
        const { res, next } = await authenticate({
            data: null, error: { code: '57014', message: 'canceling statement due to statement timeout' },
        });
        expect(res.status).toHaveBeenCalledWith(503);
        expect(res.json).toHaveBeenCalledWith({ error: 'Authentication service unavailable' });
        expect(next).not.toHaveBeenCalled();
        expect(reportFailure).toHaveBeenCalledWith('auth-middleware-db', new Error('API key lookup failed'));
        expect(logger.error).toHaveBeenCalledWith({ code: '57014' }, 'API key lookup failed');
    });
});

describe('generateApiKey', () => {
    it('returns a string', () => {
        const key = generateApiKey();
        expect(typeof key).toBe('string');
    });

    it('generates 64-character hex string (32 bytes)', () => {
        const key = generateApiKey();
        expect(key).toMatch(/^[a-f0-9]{64}$/);
    });

    it('generates unique keys on each call', () => {
        const keys = new Set();
        for (let i = 0; i < 100; i++) {
            keys.add(generateApiKey());
        }
        expect(keys.size).toBe(100);
    });
});

describe('hashApiKey', () => {
    it('returns a string', () => {
        const hash = hashApiKey('test-key');
        expect(typeof hash).toBe('string');
    });

    it('returns consistent SHA-256 hex digest', () => {
        const hash1 = hashApiKey('my-api-key');
        const hash2 = hashApiKey('my-api-key');
        expect(hash1).toBe(hash2);
        expect(hash1).toMatch(/^[a-f0-9]{64}$/);
    });

    it('produces different hashes for different inputs', () => {
        const hash1 = hashApiKey('key-1');
        const hash2 = hashApiKey('key-2');
        expect(hash1).not.toBe(hash2);
    });

    it('produces a valid SHA-256 hash', () => {
        const hash = hashApiKey('test');
        expect(hash).toBe('9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08');
    });
});
