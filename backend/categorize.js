/**
 * Categorization service. Dependencies are injected so it can be unit-tested.
 *
 *   domains:  `local(key, false)` only (static map, labeled list, regexes), else
 *             Other. Never touches the DB.
 *   packages: 1. `curatedPackage` (exact static map; defaults to `local(key, true)`)
 *             2. DB cache
 *             3. Play Store lookup:
 *                  ok         -> that category, cached
 *                  not_found  -> Other, cached (ONLY an HTTP 404 from Play); regexes
 *                                are NOT consulted
 *                  error      -> `patternPackage` regex result, else Other; NEVER cached
 *                                (also used when the play call throws)
 *                  invalid    -> Other, never cached; regexes not consulted
 *             Regexes are a fallback for Play being unavailable, not a first guess:
 *             substrings like "eat" in "kreativekoala" mislabel too many packages.
 */
export function createCategorizer({ cache, local, curatedPackage, patternPackage, play, logger }) {
    const curated = curatedPackage || ((k) => local(k, true));
    const pattern = patternPackage || (() => null);

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

        if (!isPackage) return local(key, false) || 'Other';

        const curatedCategory = curated(key);
        if (curatedCategory) return curatedCategory;

        const cached = await cacheGet(key);
        if (cached) return cached;

        let result;
        try {
            result = await play(key);
        } catch (err) {
            logger.warn({ key, err: err.message }, 'play lookup threw');
            return pattern(key) || 'Other';
        }

        if (result.status === 'ok') {
            await cacheSet(key, result.category);
            return result.category;
        }
        if (result.status === 'not_found') {
            await cacheSet(key, 'Other');
            return 'Other';
        }
        if (result.status === 'error') return pattern(key) || 'Other';
        return 'Other';
    };
}
