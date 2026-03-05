import { describe, it, expect, vi } from 'vitest';

// Mock supabase and logger before importing
vi.mock('../supabase.js', () => ({
    supabase: {
        from: () => ({
            select: () => ({ eq: () => ({ single: () => ({}) }) }),
            upsert: () => ({}),
            delete: () => ({ eq: () => ({}) }),
        }),
    }
}));

vi.mock('../logger.js', () => ({
    logger: { info: vi.fn(), error: vi.fn(), warn: vi.fn() }
}));

const { generateLinkingCode } = await import('../linkingFunctions.js');

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
