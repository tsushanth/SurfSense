import { describe, it, expect, vi } from 'vitest';
import { createCategorizer } from '../categorize.js';

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
