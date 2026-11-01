import { describe, it, expect } from 'vitest';
import { computeFunnel, isExcluded, pct, formatFunnel } from '../funnel.js';

const now = new Date('2026-11-01T00:00:00Z');
const c = (id, created, type = 'ANDROID', name = 'Pixel') => ({ client_id: id, client_type: type, client_name: name, created_at: created });

describe('pct', () => {
    it('rounds to one decimal and returns null for an empty denominator', () => {
        expect(pct(1, 3)).toBe(33.3);
        expect(pct(0, 0)).toBeNull();
    });
});

describe('isExcluded', () => {
    it('drops smoke-test clients and listed id prefixes', () => {
        expect(isExcluded(c('smoke-a-1', '2026-10-20'))).toBe(true);
        expect(isExcluded(c('x1', '2026-10-20', 'ANDROID', 'smoke-test'))).toBe(true);
        expect(isExcluded(c('abcd1234zzzz', '2026-10-20'), ['abcd1234'])).toBe(true);
        expect(isExcluded(c('zzzz', '2026-10-20'), ['abcd1234'])).toBe(false);
    });
});

describe('computeFunnel', () => {
    const clients = [
        c('a-never', '2026-10-01'),                 // before, never synced
        c('b-once', '2026-10-05'),                  // before, one sync only
        c('c-return', '2026-10-05'),                // before, returned after 7d, linked
        c('d-new', '2026-10-20'),                   // after, synced day 0 and 1
        c('e-young', '2026-10-31'),                 // after, too young for 7d
        c('smoke-x', '2026-10-20'),                 // excluded
        c('f-ext', '2026-10-20', 'CHROME_EXTENSION'),
    ];
    const summaries = [
        { user_id: 'b-once', day: '2026-10-05' },
        { user_id: 'c-return', day: '2026-10-05' },
        { user_id: 'c-return', day: '2026-10-13' },
        { user_id: 'd-new', day: '2026-10-20' },
        { user_id: 'd-new', day: '2026-10-21' },
        { user_id: 'e-young', day: '2026-10-31' },
    ];
    const links = [{ client_a: 'c-return', client_b: 'f-ext' }];
    const r = computeFunnel({ clients, summaries, links, now, cutoff: '2026-10-13' });

    it('counts android steps before and after the cutoff', () => {
        expect(r.byType.ANDROID.before).toMatchObject({ registered: 3, firstSync: 2, twoDays: 1, returned7dEligible: 3, returned7d: 1, linked: 1 });
        expect(r.byType.ANDROID.after).toMatchObject({ registered: 2, firstSync: 2, twoDays: 1, returned7dEligible: 1, returned7d: 0 });
    });
    it('only counts 7-day return for clients old enough', () => {
        expect(r.byType.ANDROID.after.returned7dEligible).toBe(1); // e-young excluded
    });
    it('reports rates, separates client types and reports exclusions', () => {
        expect(r.byType.ANDROID.before.firstSyncPct).toBe(66.7);
        expect(r.byType.CHROME_EXTENSION.after.registered).toBe(1);
        expect(r.excludedClients).toBe(1);
    });
    it('formats text without any client id', () => {
        const text = formatFunnel(r);
        expect(text).toContain('ANDROID before: registered 3');
        expect(text).not.toMatch(/a-never|c-return|smoke/);
    });
    it('handles empty input', () => {
        expect(computeFunnel({ clients: [], summaries: [], links: [], now, cutoff: '2026-10-13' }).byType).toEqual({});
    });
});
