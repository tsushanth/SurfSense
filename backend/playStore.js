const PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
const MAX_PACKAGE_LENGTH = 255;
const CATEGORY_RE = /"applicationCategory":"([A-Z_]+)"/;

const PLAY_TO_OURS = {
    SOCIAL: 'Social Media',
    COMMUNICATION: 'Social Media',
    DATING: 'Social Media',
    ENTERTAINMENT: 'Entertainment',
    MUSIC_AND_AUDIO: 'Entertainment',
    VIDEO_PLAYERS: 'Entertainment',
    COMICS: 'Entertainment',
    SPORTS: 'Entertainment',
    PRODUCTIVITY: 'Work/Productivity',
    BUSINESS: 'Work/Productivity',
    SHOPPING: 'Shopping',
    EDUCATION: 'Education',
    BOOKS_AND_REFERENCE: 'Education',
    NEWS_AND_MAGAZINES: 'News',
    FINANCE: 'Finance',
    HEALTH_AND_FITNESS: 'Health',
    MEDICAL: 'Health',
    TRAVEL_AND_LOCAL: 'Travel',
    MAPS_AND_NAVIGATION: 'Travel',
    FOOD_AND_DRINK: 'Food',
};

export function isValidPackageName(p) {
    return typeof p === 'string' && p.length <= MAX_PACKAGE_LENGTH && PACKAGE_RE.test(p);
}

export function parsePlayCategory(html) {
    if (typeof html !== 'string') return null;
    const m = CATEGORY_RE.exec(html);
    return m ? m[1] : null;
}

export function mapPlayCategory(raw) {
    if (!raw) return 'Other';
    if (raw.startsWith('GAME')) return 'Entertainment';
    return PLAY_TO_OURS[raw] || 'Other';
}

/**
 * Look up an Android package's category from its public Play Store page.
 * Only ever requests play.google.com with a validated package name and never
 * follows redirects. Result statuses:
 *   ok         category found
 *   not_found  Play returned HTTP 404 (cache as Other)
 *   error      transient problem (do NOT cache): unexpected page (200 without a
 *              category), redirects, 429/5xx, timeouts, network errors
 *   invalid    not a package name; no request made (do NOT cache)
 */
export async function fetchPlayCategory(pkg, { fetchImpl = fetch, timeoutMs = 4000 } = {}) {
    if (!isValidPackageName(pkg)) return { status: 'invalid' };

    const url = `https://play.google.com/store/apps/details?id=${pkg}&hl=en&gl=us`;
    try {
        const res = await fetchImpl(url, {
            redirect: 'manual',
            signal: AbortSignal.timeout(timeoutMs),
            headers: { 'User-Agent': 'Mozilla/5.0', 'Accept-Language': 'en-US,en;q=0.9' },
        });

        if (res.status === 404) return { status: 'not_found' };
        if (res.status !== 200) return { status: 'error' };

        const raw = parsePlayCategory(await res.text());
        if (!raw) return { status: 'error' };
        return { status: 'ok', category: mapPlayCategory(raw) };
    } catch {
        return { status: 'error' };
    }
}
