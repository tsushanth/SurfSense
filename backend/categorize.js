/**
 * Categorization service. Dependencies are injected so it can be unit-tested.
 *
 *   domains:  local only (static map, labeled list, regexes), else Other. Never touches the DB.
 *   packages: local, then DB cache, then Play Store lookup, else Other.
 *             Only definitive Play answers are cached: `ok`, and `not_found`
 *             (which means ONLY an HTTP 404 from Play). `error` (including a 200
 *             page with no category) and `invalid` are never cached.
 */
export function createCategorizer({ cache, local, play, logger }) {
    async function cacheGet(key) {
        try {
            return await cache.get(key);
        } catch (err) {
            logger.warn({ key, err: err.message }, 'category cache read failed');
            return null;
        }
    }

    async function cacheSet(key, category) {
        try {
            await cache.set(key, category);
        } catch (err) {
            logger.warn({ key, err: err.message }, 'category cache write failed');
        }
    }

    return async function categorize(input, isPackage = false) {
        if (typeof input !== 'string') return 'Other';
        const key = input.trim();
        if (key === '' || key === '/') return 'Other';

        const localCategory = local(key, isPackage);
        if (localCategory) return localCategory;

        if (!isPackage) return 'Other';

        const cached = await cacheGet(key);
        if (cached) return cached;

        let result;
        try {
            result = await play(key);
        } catch (err) {
            logger.warn({ key, err: err.message }, 'play lookup threw');
            return 'Other';
        }

        if (result.status === 'ok') {
            await cacheSet(key, result.category);
            return result.category;
        }
        if (result.status === 'not_found') {
            await cacheSet(key, 'Other');
            return 'Other';
        }
        return 'Other';
    };
}
