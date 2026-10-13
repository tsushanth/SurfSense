import { describe, it, expect, vi } from 'vitest';
import { isValidPackageName, parsePlayCategory, mapPlayCategory, fetchPlayCategory } from '../playStore.js';

describe('isValidPackageName', () => {
    it('accepts real package names', () => {
        for (const ok of ['com.instagram.android', 'com.Slack', 'a.b', 'org.mozilla.firefox_beta', 'com.x1.y_2']) {
            expect(isValidPackageName(ok)).toBe(true);
        }
    });

    it('rejects hostile or malformed names', () => {
        const bad = [
            '', 'com', 'com.', '.com.x', 'com..x', 'com.x/../y', 'com.x?id=1', 'com.x#f',
            'com.x@evil.com', 'http://x.y', 'com.x y', '1com.x', 'com.x\n', 'com.é.x',
            'a'.repeat(300) + '.b', null, undefined, 42,
        ];
        for (const b of bad) expect(isValidPackageName(b)).toBe(false);
    });
});

describe('parsePlayCategory', () => {
    it('extracts the JSON-LD applicationCategory', () => {
        expect(parsePlayCategory('x,"applicationCategory":"SOCIAL","y"')).toBe('SOCIAL');
        expect(parsePlayCategory('"applicationCategory":"GAME_PUZZLE"')).toBe('GAME_PUZZLE');
    });

    it('returns null when absent or not an uppercase id', () => {
        expect(parsePlayCategory('<html></html>')).toBeNull();
        expect(parsePlayCategory('"applicationCategory":"social"')).toBeNull();
        expect(parsePlayCategory('')).toBeNull();
    });
});

describe('mapPlayCategory', () => {
    it.each([
        ['SOCIAL', 'Social Media'], ['COMMUNICATION', 'Social Media'], ['DATING', 'Social Media'],
        ['ENTERTAINMENT', 'Entertainment'], ['MUSIC_AND_AUDIO', 'Entertainment'],
        ['VIDEO_PLAYERS', 'Entertainment'], ['SPORTS', 'Entertainment'], ['GAME_PUZZLE', 'Entertainment'],
        ['PRODUCTIVITY', 'Work/Productivity'], ['BUSINESS', 'Work/Productivity'],
        ['SHOPPING', 'Shopping'], ['EDUCATION', 'Education'], ['BOOKS_AND_REFERENCE', 'Education'],
        ['NEWS_AND_MAGAZINES', 'News'], ['FINANCE', 'Finance'],
        ['HEALTH_AND_FITNESS', 'Health'], ['MEDICAL', 'Health'],
        ['TRAVEL_AND_LOCAL', 'Travel'], ['MAPS_AND_NAVIGATION', 'Travel'],
        ['FOOD_AND_DRINK', 'Food'],
        ['TOOLS', 'Other'], ['WEATHER', 'Other'], ['SOMETHING_NEW', 'Other'], [null, 'Other'],
    ])('%s -> %s', (raw, expected) => {
        expect(mapPlayCategory(raw)).toBe(expected);
    });
});

function fakeResponse(status, body = '') {
    return { status, ok: status >= 200 && status < 300, text: async () => body };
}

describe('fetchPlayCategory', () => {
    it('returns ok with the mapped category on a 200', async () => {
        const fetchImpl = vi.fn(async () => fakeResponse(200, '..."applicationCategory":"SOCIAL"...'));
        const r = await fetchPlayCategory('com.instagram.android', { fetchImpl });
        expect(r).toEqual({ status: 'ok', category: 'Social Media' });
    });

    it('builds a fixed-host URL, does not follow redirects, and sets a timeout signal', async () => {
        const fetchImpl = vi.fn(async () => fakeResponse(200, '"applicationCategory":"FINANCE"'));
        await fetchPlayCategory('com.bank.app', { fetchImpl });
        const [url, opts] = fetchImpl.mock.calls[0];
        expect(url).toBe('https://play.google.com/store/apps/details?id=com.bank.app&hl=en&gl=us');
        expect(opts.redirect).toBe('manual');
        expect(opts.signal).toBeInstanceOf(AbortSignal);
    });

    it('returns not_found only on HTTP 404', async () => {
        expect(await fetchPlayCategory('com.nope.x', { fetchImpl: async () => fakeResponse(404) })).toEqual({ status: 'not_found' });
    });

    it('returns error for 429, 5xx, redirects, a 200 page with no category, and thrown errors (transient)', async () => {
        for (const status of [429, 500, 503, 301, 302]) {
            expect(await fetchPlayCategory('com.a.b', { fetchImpl: async () => fakeResponse(status) })).toEqual({ status: 'error' });
        }
        expect(await fetchPlayCategory('com.a.b', { fetchImpl: async () => fakeResponse(200, '<html/>') })).toEqual({ status: 'error' });
        expect(await fetchPlayCategory('com.a.b', { fetchImpl: async () => { throw new Error('boom'); } })).toEqual({ status: 'error' });
    });

    it('returns invalid and never calls fetch for hostile names', async () => {
        const fetchImpl = vi.fn();
        for (const bad of ['com.x/../y', 'com.x?id=1', 'com.x@evil.com', '', 'a'.repeat(300) + '.b']) {
            expect(await fetchPlayCategory(bad, { fetchImpl })).toEqual({ status: 'invalid' });
        }
        expect(fetchImpl).not.toHaveBeenCalled();
    });
});
