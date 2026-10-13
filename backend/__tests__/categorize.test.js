import { describe, it, expect, vi } from 'vitest';
import { createCategorizer } from '../categorize.js';
import { categorizeLocally, lookupStatic, matchByPattern } from '../categorizer.js';

function make({ cacheGet = null, local = () => null, play = async () => ({ status: 'error' }) } = {}) {
    const cache = { get: vi.fn(async () => cacheGet), set: vi.fn(async () => {}) };
    const playFn = vi.fn(play);
    const localFn = vi.fn(local);
    const categorize = createCategorizer({
        cache, local: localFn, play: playFn,
        logger: { warn: vi.fn(), info: vi.fn(), error: vi.fn() },
    });
    return { categorize, cache, play: playFn, local: localFn };
}

describe('createCategorizer', () => {
    it('returns Other for empty, slash, whitespace and non-string input without any lookups', async () => {
        const { categorize, cache, play, local } = make();
        for (const bad of ['', '/', '   ', null, undefined, 42]) {
            expect(await categorize(bad)).toBe('Other');
        }
        expect(cache.get).not.toHaveBeenCalled();
        expect(play).not.toHaveBeenCalled();
        expect(local).not.toHaveBeenCalled();
    });

    it('resolves a known domain locally with no DB or Play calls', async () => {
        const { categorize, cache, play } = make({ local: () => 'News' });
        expect(await categorize('bbc.com')).toBe('News');
        expect(cache.get).not.toHaveBeenCalled();
        expect(cache.set).not.toHaveBeenCalled();
        expect(play).not.toHaveBeenCalled();
    });

    it('returns Other for an unknown domain without caching or calling Play', async () => {
        const { categorize, cache, play } = make();
        expect(await categorize('totally-unknown.example')).toBe('Other');
        expect(cache.set).not.toHaveBeenCalled();
        expect(play).not.toHaveBeenCalled();
    });

    it('resolves a package locally before cache or Play', async () => {
        const { categorize, cache, play } = make({ local: () => 'Work/Productivity' });
        expect(await categorize('com.Slack', true)).toBe('Work/Productivity');
        expect(cache.get).not.toHaveBeenCalled();
        expect(play).not.toHaveBeenCalled();
    });

    it('uses the cache for a package before Play', async () => {
        const { categorize, play } = make({ cacheGet: 'Finance' });
        expect(await categorize('com.bank.app', true)).toBe('Finance');
        expect(play).not.toHaveBeenCalled();
    });

    it('looks up an uncached package on Play and caches the result', async () => {
        const { categorize, cache } = make({ play: async () => ({ status: 'ok', category: 'Social Media' }) });
        expect(await categorize('com.new.app', true)).toBe('Social Media');
        expect(cache.set).toHaveBeenCalledWith('com.new.app', 'Social Media');
    });

    it('caches Other when Play says the app does not exist', async () => {
        const { categorize, cache } = make({ play: async () => ({ status: 'not_found' }) });
        expect(await categorize('com.gone.app', true)).toBe('Other');
        expect(cache.set).toHaveBeenCalledWith('com.gone.app', 'Other');
    });

    it('does NOT cache transient or invalid Play results', async () => {
        for (const status of ['error', 'invalid']) {
            const { categorize, cache } = make({ play: async () => ({ status }) });
            expect(await categorize('com.flaky.app', true)).toBe('Other');
            expect(cache.set).not.toHaveBeenCalled();
        }
        const thrown = make({ play: async () => { throw new Error('boom'); } });
        expect(await thrown.categorize('com.flaky.app', true)).toBe('Other');
        expect(thrown.cache.set).not.toHaveBeenCalled();
    });

    it('survives cache read and write failures', async () => {
        const { categorize, cache } = make({ play: async () => ({ status: 'ok', category: 'Travel' }) });
        cache.get.mockRejectedValueOnce(new Error('db down'));
        cache.set.mockRejectedValueOnce(new Error('db down'));
        expect(await categorize('com.trip.app', true)).toBe('Travel');
    });
});

function makePlayFirst({ curated = () => null, cacheGet = null, pattern = () => null, play } = {}) {
    const cache = { get: vi.fn(async () => cacheGet), set: vi.fn(async () => {}) };
    const curatedPackage = vi.fn(curated);
    const patternPackage = vi.fn(pattern);
    const playFn = vi.fn(play);
    const categorize = createCategorizer({
        cache, local: () => null, curatedPackage, patternPackage, play: playFn,
        logger: { warn: vi.fn(), info: vi.fn(), error: vi.fn() },
    });
    return { categorize, cache, curatedPackage, patternPackage, play: playFn };
}

describe('createCategorizer Play-first package order', () => {
    it('curated package hit returns without cache or Play', async () => {
        const t = makePlayFirst({ curated: () => 'Social Media', play: async () => ({ status: 'ok', category: 'X' }) });
        expect(await t.categorize('com.a', true)).toBe('Social Media');
        expect(t.cache.get).not.toHaveBeenCalled();
        expect(t.play).not.toHaveBeenCalled();
        expect(t.patternPackage).not.toHaveBeenCalled();
    });

    it('cache hit returns without Play or patterns', async () => {
        const t = makePlayFirst({ cacheGet: 'Finance', pattern: () => 'Food', play: async () => ({ status: 'error' }) });
        expect(await t.categorize('com.a', true)).toBe('Finance');
        expect(t.play).not.toHaveBeenCalled();
        expect(t.patternPackage).not.toHaveBeenCalled();
    });

    it('Play ok returns the category and caches it', async () => {
        const t = makePlayFirst({ pattern: () => 'Food', play: async () => ({ status: 'ok', category: 'Travel' }) });
        expect(await t.categorize('com.a', true)).toBe('Travel');
        expect(t.cache.set).toHaveBeenCalledWith('com.a', 'Travel');
        expect(t.patternPackage).not.toHaveBeenCalled();
    });

    it('Play not_found gives Other, cached, and never consults patterns', async () => {
        const t = makePlayFirst({ pattern: () => 'Food', play: async () => ({ status: 'not_found' }) });
        expect(await t.categorize('com.a', true)).toBe('Other');
        expect(t.cache.set).toHaveBeenCalledWith('com.a', 'Other');
        expect(t.patternPackage).not.toHaveBeenCalled();
    });

    it('Play error falls back to the pattern result and does not cache it', async () => {
        const t = makePlayFirst({ pattern: () => 'Food', play: async () => ({ status: 'error' }) });
        expect(await t.categorize('com.a', true)).toBe('Food');
        expect(t.cache.set).not.toHaveBeenCalled();
    });

    it('Play error with no pattern match gives Other, not cached', async () => {
        const t = makePlayFirst({ play: async () => ({ status: 'error' }) });
        expect(await t.categorize('com.a', true)).toBe('Other');
        expect(t.cache.set).not.toHaveBeenCalled();
    });

    it('a throwing Play call behaves like error', async () => {
        const t = makePlayFirst({ pattern: () => 'Food', play: async () => { throw new Error('boom'); } });
        expect(await t.categorize('com.a', true)).toBe('Food');
        expect(t.cache.set).not.toHaveBeenCalled();
        const u = makePlayFirst({ play: async () => { throw new Error('boom'); } });
        expect(await u.categorize('com.a', true)).toBe('Other');
        expect(u.cache.set).not.toHaveBeenCalled();
    });

    it('Play invalid gives Other, no patterns, not cached', async () => {
        const t = makePlayFirst({ pattern: () => 'Food', play: async () => ({ status: 'invalid' }) });
        expect(await t.categorize('com.a', true)).toBe('Other');
        expect(t.patternPackage).not.toHaveBeenCalled();
        expect(t.cache.set).not.toHaveBeenCalled();
    });

    it('curatedPackage defaults to local(key, true)', async () => {
        const local = vi.fn((k, isPkg) => (isPkg ? 'News' : null));
        const cache = { get: vi.fn(async () => null), set: vi.fn(async () => {}) };
        const play = vi.fn(async () => ({ status: 'error' }));
        const categorize = createCategorizer({ cache, local, play, logger: { warn() {}, info() {}, error() {} } });
        expect(await categorize('com.a', true)).toBe('News');
        expect(local).toHaveBeenCalledWith('com.a', true);
        expect(play).not.toHaveBeenCalled();
    });
});

describe('createCategorizer integration with the real categorizer', () => {
    function real(playResult) {
        const cache = { get: vi.fn(async () => null), set: vi.fn(async () => {}) };
        const categorize = createCategorizer({
            cache,
            local: categorizeLocally,
            curatedPackage: (p) => lookupStatic(p, true),
            patternPackage: (p) => matchByPattern(p, true),
            play: async () => playResult,
            logger: { warn() {}, info() {}, error() {} },
        });
        return { categorize, cache };
    }

    it('own app resolves via Play, not the "eat" regex', async () => {
        const { categorize } = real({ status: 'ok', category: 'Work/Productivity' });
        expect(await categorize('com.kreativekoala.surfsense', true)).toBe('Work/Productivity');
    });

    it('system package not on Play is Other, not Work via "network"', async () => {
        const { categorize } = real({ status: 'not_found' });
        expect(await categorize('com.google.android.networkstack.tethering', true)).toBe('Other');
    });

    it('Play outage falls back to regex and does not cache', async () => {
        const { categorize, cache } = real({ status: 'error' });
        expect(await categorize('com.facebook.katana', true)).toBe('Social Media');
        expect(cache.set).not.toHaveBeenCalled();
    });
});
