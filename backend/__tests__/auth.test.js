import { describe, it, expect, vi } from 'vitest';

// Mock supabase before importing auth (auth.js imports supabase.js transitively)
vi.mock('../supabase.js', () => ({
    supabase: {
        from: () => ({ select: () => ({ eq: () => ({ single: () => ({}) }) }) }),
    }
}));

vi.mock('../logger.js', () => ({
    logger: { info: vi.fn(), error: vi.fn(), warn: vi.fn() }
}));

const { hashApiKey, generateApiKey } = await import('../middleware/auth.js');

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
