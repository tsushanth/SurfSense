# SurfSense Backend Revival Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the SurfSense backend back to life on Fly.io + a free Supabase project, with an LLM-free categorizer and closed authorization gaps, verified by a live smoke test.

**Architecture:** The existing Express 5 + supabase-js backend in `backend/` is kept. Categorization becomes: in-memory labeled-domain list and regexes for domains; static map, regexes, a DB cache and a Play Store category lookup for Android packages; "Other" otherwise. A small `createCategorizer` service with injected dependencies makes this unit-testable. Route authorization checks are extracted to pure functions. The app is deployed to Fly (scale-to-zero) against a dedicated Free-plan Supabase project.

**Tech Stack:** Node (current LTS) ESM, Express 5, @supabase/supabase-js, vitest, pino, Fly.io (`flyctl`), Supabase CLI.

**Spec:** `docs/superpowers/specs/2026-10-12-surfsense-revival-and-redesign-design.md` (Parts 1 and the Categorizer section; Parts 2 to 5 get their own plans after this backend is live and verified).

## Global Constraints

- Category names, exact spelling: `Social Media`, `Entertainment`, `Work/Productivity`, `Shopping`, `Education`, `News`, `Finance`, `Health`, `Travel`, `Food`, `Other`.
- No LLM and no LLM API key anywhere. OpenAI (dependency, env var, README mention) is removed.
- API paths, methods and response shapes stay unchanged, except the new `403` responses on the authorization gaps in Task 4. (A later iOS release only flips the base URL.)
- Fly app name `usage-tracker-backend`, region `iad`, `min_machines_running = 0`, `auto_stop_machines = "stop"`, 512 MB shared CPU.
- Supabase: org `cpjqdstaljfkqeggtupk` (Free plan), project ref `rxwtuegccdhygbxvwzvo`, URL `https://rxwtuegccdhygbxvwzvo.supabase.co`. Secrets set on Fly: `SUPABASE_URL`, `SUPABASE_SERVICE_KEY` only.
- Never write a secret, key or `.env` into the repo or print one to the terminal. Fly secrets are set via stdin import. Never overwrite an existing Fly secret.
- Play Store fetches go only to `https://play.google.com/store/apps/details` with a regex-validated package name and `redirect: 'manual'` (no SSRF surface).
- The working tree has ~15 unrelated uncommitted changes (iOS, Android, extension). Stage by explicit path only. Never `git add -A`, `git add .` or `git commit -a`.
- Commit messages end with: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Tests: `cd backend && npm test` (vitest). The `Can't find lefthook in PATH` line printed on commit is harmless.

## Review Focus

Failure modes the spec implies but no task would otherwise exercise; each is pinned by a test in the named task.

1. **Hostile package names** (`com.x/../../y`, `com.x?id=1`, `com.x@evil.com`, `com.x#`, spaces, unicode, 300-char names) must never reach `fetch`. Task 2.
2. **Transient Play failures** (429, 5xx, timeout, redirect, network error) must return `Other` but must NOT be written to the cache, or one bad minute poisons a package forever. Tasks 2 and 3.
3. **Odd domain forms**: uppercase, `www.`/`m.`, trailing dot, port, full URL with path, IP addresses, empty labels (`a..com`), single label (`localhost`). Must resolve to a sensible category or `Other` and never throw. Task 1.
4. **Cross-device authorization**: a client must not read another unlinked client's history, unlink two other clients, list another client's links, or complete linking as someone else. Tasks 4 and 7.
5. **Cold start**: the first request after the Fly machine scaled to zero must still succeed (clients use a 30 s timeout). Task 7 measures it and retries.

---

### Task 1: Labeled-domain tier

**Files:**
- Create: `backend/domainList.js`
- Create: `backend/__tests__/domainList.test.js`
- Modify: `backend/categorizer.js` (`categorizeLocally`, imports)
- Modify: `backend/__tests__/categorizer.test.js` (add cases)

**Interfaces:**
- Produces: `candidateDomains(input: string): string[]`, `createLabeledLookup(map: Record<string,string>): (input: string) => string | null`, `lookupLabeled(input: string): string | null` (lazy-loads `data/domain-categories.json`).
- Consumes: `backend/data/domain-categories.json` (committed; ~12.5k `domain -> category`).

- [ ] **Step 1: Write the failing tests**

Create `backend/__tests__/domainList.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { candidateDomains, createLabeledLookup, lookupLabeled } from '../domainList.js';

describe('candidateDomains', () => {
    it('lowercases and returns the host then its parents down to two labels', () => {
        expect(candidateDomains('WWW.Docs.Example.co.uk')).toEqual([
            'www.docs.example.co.uk', 'docs.example.co.uk', 'example.co.uk', 'co.uk',
        ]);
    });

    it('strips protocol, port, path, query, fragment and trailing dot', () => {
        expect(candidateDomains('https://foo.com:8080/a/b?x=1#y')).toEqual(['foo.com']);
        expect(candidateDomains('foo.com.')).toEqual(['foo.com']);
    });

    it('returns [] for empty, whitespace, non-string, single label, IPs and malformed hosts', () => {
        for (const bad of ['', '   ', null, undefined, 42, 'localhost', '1.2.3.4', 'a..com', '../etc', 'bad host.com', 'a_b.com', '/']) {
            expect(candidateDomains(bad)).toEqual([]);
        }
    });
});

describe('createLabeledLookup', () => {
    const lookup = createLabeledLookup({ 'example.com': 'News', 'docs.example.com': 'Work/Productivity' });

    it('matches the exact host first, then parents', () => {
        expect(lookup('docs.example.com')).toBe('Work/Productivity');
        expect(lookup('www.example.com')).toBe('News');
        expect(lookup('a.b.example.com')).toBe('News');
    });

    it('returns null when nothing matches or input is bad', () => {
        expect(lookup('unknown.org')).toBeNull();
        expect(lookup('')).toBeNull();
    });
});

describe('lookupLabeled (shipped data file)', () => {
    it('loads the committed list and finds a well-known domain', () => {
        expect(lookupLabeled('www.bbc.com')).toBe('News');
    });
});
```

Add to `backend/__tests__/categorizer.test.js` (inside a new `describe`, at the end of the file):

```js
describe('categorizeLocally - labeled list outranks regexes', () => {
    it('uses the labeled list before the regex tier', () => {
        // "postman.com" matches the News regex (/post/) but is a developer tool
        expect(categorizeLocally('postman.com')).not.toBe('News');
    });

    it('handles www. and subdomains via the list', () => {
        expect(categorizeLocally('www.bbc.com')).toBe('News');
    });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd ~/Documents/GitHub/SurfSense/backend && npm test -- domainList categorizer`
Expected: FAIL (`Cannot find module '../domainList.js'`).

- [ ] **Step 3: Check the premise of the postman test**

Run: `node -e "const d=JSON.parse(require('fs').readFileSync('data/domain-categories.json','utf8')); console.log(d['postman.com'], d['bbc.com'])"`
Expected: both defined. If `postman.com` prints `undefined`, replace it in the test with any domain from the list whose name also matches a regex, found by: `node -e "const d=JSON.parse(require('fs').readFileSync('data/domain-categories.json','utf8')); const re=/post|times|daily|eat|game/; console.log(Object.entries(d).filter(([k])=>re.test(k)).slice(0,10))"`, and assert the list's own label.

- [ ] **Step 4: Implement `backend/domainList.js`**

```js
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const DATA_PATH = path.join(
    path.dirname(fileURLToPath(import.meta.url)),
    'data',
    'domain-categories.json'
);

const HOST_RE = /^[a-z0-9.-]+$/;
const IPV4_RE = /^\d{1,3}(\.\d{1,3}){3}$/;

/**
 * Turn arbitrary input into candidate lookup keys: the host itself, then each
 * parent domain down to two labels. Returns [] for anything that is not a
 * plausible hostname.
 */
export function candidateDomains(input) {
    if (typeof input !== 'string') return [];

    let host = input.trim().toLowerCase();
    host = host.replace(/^[a-z][a-z0-9+.-]*:\/\//, '');
    host = host.split(/[/?#]/)[0];
    host = host.replace(/:\d+$/, '');
    host = host.replace(/\.$/, '');

    if (!host || !HOST_RE.test(host) || IPV4_RE.test(host)) return [];

    const labels = host.split('.');
    if (labels.length < 2 || labels.some(l => l === '')) return [];

    const candidates = [];
    for (let i = 0; i <= labels.length - 2; i++) {
        candidates.push(labels.slice(i).join('.'));
    }
    return candidates;
}

export function createLabeledLookup(map) {
    return function lookup(input) {
        for (const candidate of candidateDomains(input)) {
            const category = map[candidate];
            if (category) return category;
        }
        return null;
    };
}

let defaultLookup = null;

export function lookupLabeled(input) {
    if (!defaultLookup) {
        defaultLookup = createLabeledLookup(JSON.parse(readFileSync(DATA_PATH, 'utf8')));
    }
    return defaultLookup(input);
}
```

- [ ] **Step 5: Wire into `categorizeLocally` in `backend/categorizer.js`**

Add `import { lookupLabeled } from './domainList.js';` at the top of the file (after the header comment). Replace the body of `categorizeLocally` with:

```js
function categorizeLocally(input, isPackage = false) {
    // 1. Curated static database (exact, hand-maintained)
    const staticResult = lookupStatic(input, isPackage);
    if (staticResult) return staticResult;

    // 2. Pre-labeled popular domains. Checked BEFORE the regexes, which
    //    mislabel names like /post|times|daily/.
    if (!isPackage) {
        const labeled = lookupLabeled(input);
        if (labeled) return labeled;
    }

    // 3. Pattern matching
    const patternResult = matchByPattern(input, isPackage);
    if (patternResult) return patternResult;

    return null;
}
```

Update the header comment at the top of the file to: tiers are static map, labeled list (domains), patterns; Play Store lookup and "Other" are handled by `categorize.js`.

- [ ] **Step 6: Run to verify pass**

Run: `npm test`
Expected: all suites PASS (existing categorizer tests included). If an existing expectation fails because the list now labels a domain differently from a regex, update that test to the list's label only after eyeballing that the list's label is right.

- [ ] **Step 7: Commit**

```bash
cd ~/Documents/GitHub/SurfSense
git add backend/domainList.js backend/categorizer.js backend/__tests__/domainList.test.js backend/__tests__/categorizer.test.js
git commit -m "backend: check pre-labeled domain list before regex patterns" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Play Store category lookup

**Files:**
- Create: `backend/playStore.js`
- Create: `backend/__tests__/playStore.test.js`

**Interfaces:**
- Produces: `isValidPackageName(p: unknown): boolean`, `parsePlayCategory(html: string): string | null`, `mapPlayCategory(raw: string | null): string`, `fetchPlayCategory(pkg: string, opts?: { fetchImpl?: typeof fetch, timeoutMs?: number }): Promise<{ status: 'ok', category: string } | { status: 'not_found' } | { status: 'error' } | { status: 'invalid' }>`.
- Verified 2026-10-12 against the live site: the page JSON-LD contains `"applicationCategory":"SOCIAL"` (instagram), `"MUSIC_AND_AUDIO"` (spotify), `"TRAVEL_AND_LOCAL"` (maps); a nonexistent package returns HTTP 404. Game category IDs are assumed to be `GAME_*` (not verified).

- [ ] **Step 1: Write the failing tests**

Create `backend/__tests__/playStore.test.js`:

```js
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

    it('returns not_found on 404 and on a 200 page with no category', async () => {
        expect(await fetchPlayCategory('com.nope.x', { fetchImpl: async () => fakeResponse(404) })).toEqual({ status: 'not_found' });
        expect(await fetchPlayCategory('com.nope.x', { fetchImpl: async () => fakeResponse(200, '<html/>') })).toEqual({ status: 'not_found' });
    });

    it('returns error for 429, 5xx, redirects and thrown errors (transient)', async () => {
        for (const status of [429, 500, 503, 301, 302]) {
            expect(await fetchPlayCategory('com.a.b', { fetchImpl: async () => fakeResponse(status) })).toEqual({ status: 'error' });
        }
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
```

- [ ] **Step 2: Run to verify failure**

Run: `cd ~/Documents/GitHub/SurfSense/backend && npm test -- playStore`
Expected: FAIL (`Cannot find module '../playStore.js'`).

- [ ] **Step 3: Implement `backend/playStore.js`**

```js
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
 *   not_found  Play has no such app (cache as Other)
 *   error      transient problem (do NOT cache)
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
        if (!raw) return { status: 'not_found' };
        return { status: 'ok', category: mapPlayCategory(raw) };
    } catch {
        return { status: 'error' };
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `npm test -- playStore`
Expected: PASS.

- [ ] **Step 5: One live check (read-only, not part of the test suite)**

Run:
```bash
node -e "import('./playStore.js').then(async m => { for (const p of ['com.instagram.android','com.spotify.music','com.nonexistent.pkgxyz']) console.log(p, await m.fetchPlayCategory(p)); })"
```
Expected: `ok Social Media`, `ok Entertainment`, `not_found`.

- [ ] **Step 6: Commit**

```bash
cd ~/Documents/GitHub/SurfSense
git add backend/playStore.js backend/__tests__/playStore.test.js
git commit -m "backend: add Play Store category lookup for Android packages" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Categorizer service, wiring, OpenAI removal

**Files:**
- Create: `backend/categorize.js`
- Create: `backend/__tests__/categorize.test.js`
- Modify: `backend/server.js` (imports, remove OpenAI client and `VALID_CATEGORIES`, replace `categorize()`, limiter, comments)
- Modify: `backend/package.json`, `backend/package-lock.json` (remove `openai`)
- Modify: `backend/.env.example`, `backend/README.md`
- Modify: `docs/superpowers/specs/2026-10-12-surfsense-revival-and-redesign-design.md` (lookup-order sentence)

**Interfaces:**
- Consumes: `categorizeLocally(input, isPackage): string | null` (Task 1), `fetchPlayCategory(pkg)` (Task 2).
- Produces: `createCategorizer({ cache, local, play, logger }): (input: unknown, isPackage?: boolean) => Promise<string>` where `cache = { get(key): Promise<string|null>, set(key, category): Promise<void> }`.
- Behavior change: the DB cache is used only for Android packages (Play results). Domains resolve in memory and never touch the DB.

- [ ] **Step 1: Write the failing tests**

Create `backend/__tests__/categorize.test.js`:

```js
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
```

- [ ] **Step 2: Run to verify failure**

Run: `cd ~/Documents/GitHub/SurfSense/backend && npm test -- categorize.test`
Expected: FAIL (`Cannot find module '../categorize.js'`).

- [ ] **Step 3: Implement `backend/categorize.js`**

```js
/**
 * Categorization service. Dependencies are injected so it can be unit-tested.
 *
 *   domains:  local only (static map, labeled list, regexes), else Other. Never touches the DB.
 *   packages: local, then DB cache, then Play Store lookup, else Other.
 *             Only definitive Play answers (ok / not_found) are cached.
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
```

- [ ] **Step 4: Run to verify pass**

Run: `npm test -- categorize.test`
Expected: PASS.

- [ ] **Step 5: Wire into `server.js`**

In `backend/server.js`:
1. Delete `import { OpenAI } from 'openai';` and the block `// --- OpenAI client ---` / `const openai = new OpenAI(...)`.
2. Delete the `VALID_CATEGORIES` array and the whole `async function categorize(input, isPackage = false) { ... }` function (the `// --- Categorization with retry ---` section).
3. Change the import `import { categorizeLocally } from './categorizer.js';` and add below it:

```js
import { fetchPlayCategory } from './playStore.js';
import { createCategorizer } from './categorize.js';
```

4. Where `categorize` used to be defined (after `isValidClientId`), add:

```js
// --- Categorization (no LLM): see categorize.js ---
const playLimit = pLimit(4); // cap concurrent Play Store fetches across all requests

const categorize = createCategorizer({
    cache: {
        async get(key) {
            const { data, error } = await supabase
                .from('domain_categories')
                .select('category')
                .eq('domain', key)
                .maybeSingle();
            if (error) throw error;
            return data?.category ?? null;
        },
        async set(key, category) {
            const { error } = await supabase
                .from('domain_categories')
                .upsert({ domain: key, category }, { onConflict: 'domain' });
            if (error) throw error;
        },
    },
    local: categorizeLocally,
    play: (pkg) => playLimit(() => fetchPlayCategory(pkg)),
    logger,
});
```

5. Rename `aiLimiter` to `categorizeLimiter`, set `max: 30`, message `'Rate limit exceeded for category lookups'`, and update its use in the `/get-category-mapping` route. Change the comment `// Process all items with concurrency limit (max 5 concurrent OpenAI calls)` to `(max 5 at once per request)`. Change the `trust proxy` comment to say the app runs behind Fly's proxy.

- [ ] **Step 6: Remove OpenAI from dependencies and docs**

Run: `npm uninstall openai`
Edit `.env.example`: delete the `OPENAI_API_KEY` block and its comment. Edit `README.md`: remove the OpenAI row from the env table; change the first paragraph's "(via local patterns + OpenAI fallback)" to "(local lists and patterns, plus a Play Store lookup for Android packages)"; replace the whole `## Deployment` section with:

```markdown
## Deployment

Runs on Fly.io as `usage-tracker-backend` (see `fly.toml`). Secrets (`SUPABASE_URL`, `SUPABASE_SERVICE_KEY`) live only on Fly.

```bash
flyctl secrets list -a usage-tracker-backend
flyctl deploy
SMOKE_BASE_URL=https://usage-tracker-backend.fly.dev node scripts/smoke.mjs
```
```

Edit the spec's sentence "Lookup order in code: cache, then the pre-labeled list" to: "Lookup order in code: domains resolve in memory (static map, then the pre-labeled list, then regexes, else Other) and never touch the DB; Android packages resolve static map, regexes, then the DB cache, then the Play Store category (only definitive answers cached), else Other." (Keep the rest of that paragraph.)

- [ ] **Step 7: Verify nothing references OpenAI and the suite is green**

Run:
```bash
grep -rn -i "openai" --exclude-dir=node_modules --exclude=package-lock.json . ; echo "grep exit $? (1 = none found)"
npm test
node --check server.js
```
Expected: grep finds nothing (exit 1); tests PASS; syntax check clean.

- [ ] **Step 8: Commit**

```bash
cd ~/Documents/GitHub/SurfSense
git add backend/categorize.js backend/__tests__/categorize.test.js backend/server.js backend/package.json backend/package-lock.json backend/.env.example backend/README.md docs/superpowers/specs/2026-10-12-surfsense-revival-and-redesign-design.md
git commit -m "backend: LLM-free categorizer service, remove OpenAI" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Close authorization gaps

**Files:**
- Create: `backend/authz.js`
- Create: `backend/__tests__/authz.test.js`
- Modify: `backend/server.js` (routes `/get-summary-history`, `/unlink-device`, `/get-linked-clients`, `/complete-linking`)

**Interfaces:**
- Produces: `canReadSummary(requesterId: string, targetId: string, linkedIds: string[]): boolean`, `isPartyToLink(requesterId: string, a: string, b: string): boolean`.
- Consumes: `getLinkedClients(clientId): Promise<{clientId, clientName, clientType}[]>` (existing, `linkingFunctions.js`).
- Found by reading the routes: today any authenticated client can read any client's summaries (`// For now, allow if authenticated`), unlink any two clients, list any client's links, and complete linking as any `clientId`. The shipped clients only call these with their own ID, or with a linked device's ID for history, so the checks below do not break them. Failure shape stays `{ success:false, error }` for link routes and `{ error }` for history.

- [ ] **Step 1: Write the failing tests**

Create `backend/__tests__/authz.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { canReadSummary, isPartyToLink } from '../authz.js';

describe('canReadSummary', () => {
    it('allows reading your own summary', () => {
        expect(canReadSummary('me-12345', 'me-12345', [])).toBe(true);
    });

    it('allows reading a linked device', () => {
        expect(canReadSummary('me-12345', 'phone-123', ['phone-123', 'tab-1234'])).toBe(true);
    });

    it('denies reading an unlinked device', () => {
        expect(canReadSummary('me-12345', 'stranger-1', ['phone-123'])).toBe(false);
        expect(canReadSummary('me-12345', 'stranger-1', [])).toBe(false);
    });

    it('denies non-string or empty ids', () => {
        expect(canReadSummary('me-12345', '', [''])).toBe(false);
        expect(canReadSummary('me-12345', undefined, [])).toBe(false);
        expect(canReadSummary(undefined, undefined, [])).toBe(false);
    });
});

describe('isPartyToLink', () => {
    it('allows when the requester is either side', () => {
        expect(isPartyToLink('a-123456', 'a-123456', 'b-123456')).toBe(true);
        expect(isPartyToLink('b-123456', 'a-123456', 'b-123456')).toBe(true);
    });

    it('denies when the requester is neither side', () => {
        expect(isPartyToLink('c-123456', 'a-123456', 'b-123456')).toBe(false);
    });

    it('denies missing ids', () => {
        expect(isPartyToLink(undefined, undefined, undefined)).toBe(false);
        expect(isPartyToLink('a-123456', undefined, undefined)).toBe(false);
    });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd ~/Documents/GitHub/SurfSense/backend && npm test -- authz`
Expected: FAIL (`Cannot find module '../authz.js'`).

- [ ] **Step 3: Implement `backend/authz.js`**

```js
const isId = (v) => typeof v === 'string' && v.length > 0;

/** A client may read its own summary, or a linked device's. */
export function canReadSummary(requesterId, targetId, linkedIds) {
    if (!isId(requesterId) || !isId(targetId)) return false;
    if (requesterId === targetId) return true;
    return Array.isArray(linkedIds) && linkedIds.includes(targetId);
}

/** A client may only unlink a pair it belongs to. */
export function isPartyToLink(requesterId, a, b) {
    if (!isId(requesterId) || !isId(a) || !isId(b)) return false;
    return requesterId === a || requesterId === b;
}
```

- [ ] **Step 4: Run to verify pass**

Run: `npm test -- authz`
Expected: PASS.

- [ ] **Step 5: Apply the checks in `server.js`**

Add `import { canReadSummary, isPartyToLink } from './authz.js';` next to the other local imports.

Replace the body of `/get-summary-history` from the comment `// Users can query their own data or linked devices' data` through the end of the handler with:

```js
    try {
        if (userId !== req.clientId) {
            const linked = await getLinkedClients(req.clientId);
            if (!canReadSummary(req.clientId, userId, linked.map(c => c.clientId))) {
                return res.status(403).json({ error: 'Not authorized to read this device' });
            }
        }

        const { data, error } = await supabase
            .from('daily_summaries')
            .select('timestamp, user_id, summary')
            .eq('user_id', userId)
            .eq('day', day)
            .single();

        if (error && error.code !== 'PGRST116') throw error;

        if (!data) {
            return res.json([]);
        }

        res.json([{
            timestamp: data.timestamp,
            userId: data.user_id,
            summary: data.summary
        }]);
    } catch (err) {
        logger.error({ err: err.message, userId, day }, 'Failed to fetch summary history');
        res.status(500).json({ error: 'Failed to retrieve summary data' });
    }
```

In `/unlink-device`, after the existing 400 validation and before `await unlinkClients(...)`, add:

```js
        if (!isPartyToLink(req.clientId, clientIdA, clientIdB)) {
            return res.status(403).json({ success: false, error: 'Not authorized to unlink these devices' });
        }
```

In `/get-linked-clients`, after the 400 validation and before `getLinkedClients(clientId)`, add:

```js
        if (clientId !== req.clientId) {
            return res.status(403).json({ success: false, error: 'clientId does not match authenticated client' });
        }
```

In `/complete-linking`, after the 400 validation of `resolvedClientId` and before the code check, add:

```js
        if (resolvedClientId !== req.clientId) {
            return res.status(403).json({ success: false, error: 'clientId does not match authenticated client' });
        }
```

- [ ] **Step 6: Verify**

Run: `node --check server.js && npm test`
Expected: clean syntax, all suites PASS. (The live negative checks run in Task 7.)

- [ ] **Step 7: Commit**

```bash
cd ~/Documents/GitHub/SurfSense
git add backend/authz.js backend/__tests__/authz.test.js backend/server.js
git commit -m "backend: enforce ownership/link checks on history, unlink, linked-clients, complete-linking" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Database on the free Supabase project

**Files:**
- Create: `backend/schema-extras.sql`
- Modify: `backend/schema.sql` (no change expected; applied as-is)

**Interfaces:**
- Produces: tables `extension_clients`, `client_links`, `linking_requests`, `domain_categories`, `daily_summaries`, `usage_logs`, RPC `upsert_usage_log`, cleanup functions, RLS enabled on all six tables, pg_cron jobs.
- Consumes: Supabase project `rxwtuegccdhygbxvwzvo` (created by the user). The Supabase MCP tool is bound to the other (Pro) org and cannot see it; use the CLI or the dashboard SQL editor.
- Why extras: `schema.sql` enables RLS only on `daily_summaries` and `usage_logs`. The other four tables are left open to the anon key. With RLS enabled and no policy, only the service key (used by the backend) can access them.

- [ ] **Step 1: Write `backend/schema-extras.sql`**

```sql
-- Run AFTER schema.sql.

-- 1. Lock the remaining tables to the service role (no policies = anon/authenticated get nothing).
ALTER TABLE extension_clients ENABLE ROW LEVEL SECURITY;
ALTER TABLE client_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE linking_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE domain_categories ENABLE ROW LEVEL SECURITY;

-- 2. Scheduled cleanup. If CREATE EXTENSION errors, enable pg_cron in
--    Dashboard > Database > Extensions, then re-run from the SELECT lines.
CREATE EXTENSION IF NOT EXISTS pg_cron;
SELECT cron.schedule('cleanup-expired-codes', '*/15 * * * *', 'SELECT cleanup_expired_linking_codes()');
SELECT cron.schedule('cleanup-inactive-links', '0 3 * * *', 'SELECT cleanup_inactive_links()');
```

- [ ] **Step 2: Commit the SQL file**

```bash
cd ~/Documents/GitHub/SurfSense
git add backend/schema-extras.sql
git commit -m "backend: add RLS lockdown and cron cleanup SQL" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 3: Apply both SQL files (user checkpoint)**

The CLI's `db push` needs the database password the user chose at project creation, which the assistant does not have. Ask the user to do this once in the dashboard: open `https://supabase.com/dashboard/project/rxwtuegccdhygbxvwzvo/sql/new`, paste the full contents of `backend/schema.sql`, click Run; then paste `backend/schema-extras.sql`, click Run. (If the user prefers, they can give the DB password and the assistant can run `supabase db push`-style apply via `psql`; do not store the password anywhere.)

- [ ] **Step 4: Verify tables, RLS and access (no key printed)**

Run:
```bash
cd ~/Documents/GitHub/SurfSense
KEY=$(supabase projects api-keys --project-ref rxwtuegccdhygbxvwzvo -o json 2>/dev/null | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const k=JSON.parse(s).find(x=>x.name==="service_role");process.stdout.write(k?k.api_key:"")})')
test -n "$KEY" && echo "service key fetched (not shown)"
for t in extension_clients client_links linking_requests domain_categories daily_summaries usage_logs; do
  printf "%s: " $t; curl -s -o /dev/null -w "%{http_code}\n" "https://rxwtuegccdhygbxvwzvo.supabase.co/rest/v1/$t?limit=1" -H "apikey: $KEY" -H "Authorization: Bearer $KEY"
done
unset KEY
```
Expected: `service key fetched (not shown)` and `200` for all six tables. If the key line is missing, run the same `supabase projects api-keys ... -o json` and inspect only the key names (not values): the project may expose a newer `secret` key instead of `service_role`; use that, and confirm in Task 7 that the backend works with it.

- [ ] **Step 5: Verify RLS is enforced for the anon role**

Run:
```bash
ANON=$(supabase projects api-keys --project-ref rxwtuegccdhygbxvwzvo -o json 2>/dev/null | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const k=JSON.parse(s).find(x=>x.name==="anon");process.stdout.write(k?k.api_key:"")})')
curl -s "https://rxwtuegccdhygbxvwzvo.supabase.co/rest/v1/extension_clients?limit=1" -H "apikey: $ANON" -H "Authorization: Bearer $ANON"; echo
unset ANON
```
Expected: `[]` (RLS on, no policy, so the anon role sees nothing). Seeing rows would mean RLS is off: re-run `schema-extras.sql` step 1.

---

### Task 6: Container, Fly config, deploy

**Files:**
- Modify: `backend/Dockerfile`
- Create: `backend/.dockerignore`
- Create: `backend/fly.toml`
- Modify: `backend/server.js` (`/health`)

**Interfaces:**
- Produces: `GET /health` returns `200 { status: 'ok', db: 'ok' }` or `503 { status: 'degraded', db: 'error' }`. The DB read counts as activity on the Free Supabase project.
- Found while reading: the current Dockerfile does not set `NODE_ENV`, and `logger.js` loads the dev-only `pino-pretty` transport unless `NODE_ENV=production`, so the production image would crash at boot. The image also uses end-of-life Node 18 on Alpine.

- [ ] **Step 1: Replace `backend/Dockerfile`**

```dockerfile
FROM node:24-slim

ENV NODE_ENV=production
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends tini \
    && rm -rf /var/lib/apt/lists/*

COPY package*.json ./
RUN npm ci --omit=dev

COPY --chown=node:node . .

USER node
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "server.js"]
```

Create `backend/.dockerignore`:

```
node_modules
.env
.git
__tests__
scripts
*.md
```

- [ ] **Step 2: Make `/health` check the DB**

Replace the `/health` route in `server.js` with:

```js
app.get('/health', async (req, res) => {
    try {
        const { error } = await supabase.from('domain_categories').select('domain').limit(1);
        if (error) throw error;
        res.json({ status: 'ok', db: 'ok' });
    } catch (err) {
        logger.error({ err: err.message }, 'Health check: database unreachable');
        res.status(503).json({ status: 'degraded', db: 'error' });
    }
});
```

- [ ] **Step 3: Create `backend/fly.toml`**

```toml
app = "usage-tracker-backend"
primary_region = "iad"

[build]
  dockerfile = "Dockerfile"

[env]
  NODE_ENV = "production"
  PORT = "8080"

[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = "stop"
  auto_start_machines = true
  min_machines_running = 0

[[vm]]
  cpu_kind = "shared"
  cpus = 1
  memory = "512mb"
```

- [ ] **Step 4: Prove the image boots (local, no real secrets)**

Run:
```bash
cd ~/Documents/GitHub/SurfSense/backend
docker build -t surfsense-backend:test . 2>&1 | tail -5
docker run --rm -d --name ss-test -p 18080:8080 -e SUPABASE_URL=https://example.invalid -e SUPABASE_SERVICE_KEY=dummy surfsense-backend:test
sleep 4; curl -s -m 5 -w " http=%{http_code}\n" localhost:18080/health; docker logs ss-test 2>&1 | tail -3; docker rm -f ss-test >/dev/null
```
Expected: image builds; `/health` answers `{"status":"degraded","db":"error"} http=503` (server is up, DB intentionally unreachable); logs show JSON lines, no `pino-pretty` error. If `node:24-slim` does not exist, change the tag to `node:22-slim` and note it in the commit.

- [ ] **Step 5: Run the test suite, then commit**

```bash
npm test
cd ~/Documents/GitHub/SurfSense
git add backend/Dockerfile backend/.dockerignore backend/fly.toml backend/server.js
git commit -m "backend: Node LTS slim image, NODE_ENV=production, fly.toml, DB-checking /health" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Create the Fly app and confirm the name**

Run:
```bash
flyctl auth whoami
flyctl apps list | grep -i usage-tracker || echo "no existing usage-tracker app (expected)"
flyctl apps create usage-tracker-backend
```
Expected: app created. If the name is taken globally, STOP and ask the user: the fallback is `surfsense-api`, which changes the base URL every client uses (the client plans read it from one constant, so it is cheap, but the user decides).

- [ ] **Step 7: Set secrets (user checkpoint, then run)**

Tell the user: "I'm about to set `SUPABASE_URL` and `SUPABASE_SERVICE_KEY` on the new Fly app from the SurfSense project; no existing secret is touched." Proceed on their go-ahead:

```bash
KEY=$(supabase projects api-keys --project-ref rxwtuegccdhygbxvwzvo -o json 2>/dev/null | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const k=JSON.parse(s).find(x=>x.name==="service_role");process.stdout.write(k?k.api_key:"")})')
test -n "$KEY" || { echo "no service_role key found"; exit 1; }
printf 'SUPABASE_URL=https://rxwtuegccdhygbxvwzvo.supabase.co\nSUPABASE_SERVICE_KEY=%s\n' "$KEY" | flyctl secrets import -a usage-tracker-backend
unset KEY
flyctl secrets list -a usage-tracker-backend
```
Expected: the list shows exactly `SUPABASE_URL` and `SUPABASE_SERVICE_KEY` (names and digests only).

- [ ] **Step 8: Deploy**

Confirm `backend/` has no uncommitted changes (`git status --short -- backend` is empty), then:

```bash
cd ~/Documents/GitHub/SurfSense/backend
flyctl deploy -a usage-tracker-backend --remote-only
curl -s -m 30 -w " http=%{http_code}\n" https://usage-tracker-backend.fly.dev/health
```
Expected: `{"status":"ok","db":"ok"} http=200`. If it is 503: `flyctl logs -a usage-tracker-backend` and check the key type (see Task 5, step 4 note).

---

### Task 7: Live smoke test

**Files:**
- Create: `backend/scripts/smoke.mjs`

**Interfaces:**
- Consumes: the deployed API (all routes). Env: `SMOKE_BASE_URL` (default `https://usage-tracker-backend.fly.dev`).
- Produces: exit code 0 and a PASS/FAIL line per check. Test rows are registered with `clientName: 'smoke-test'` so they can be purged: `delete from extension_clients where client_name = 'smoke-test';` (cascades to links and summaries).

- [ ] **Step 1: Write `backend/scripts/smoke.mjs`**

```js
const BASE = process.env.SMOKE_BASE_URL || 'https://usage-tracker-backend.fly.dev';
const stamp = Date.now();
const ids = { a: `smoke-a-${stamp}`, b: `smoke-b-${stamp}`, c: `smoke-c-${stamp}` };
const keys = {};
let failed = 0;

async function call(method, path, { key, body } = {}) {
    const res = await fetch(BASE + path, {
        method,
        headers: {
            'Content-Type': 'application/json',
            ...(key ? { Authorization: `Bearer ${key}` } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(30000),
    });
    let json = null;
    try { json = await res.json(); } catch { /* non-JSON body */ }
    return { status: res.status, json };
}

function check(name, cond, detail = '') {
    console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${cond ? '' : '  ' + detail}`);
    if (!cond) failed++;
}

// Cold start: the machine may be stopped. Retry /health for up to ~60s.
const t0 = Date.now();
let health;
for (let i = 0; i < 6; i++) {
    try { health = await call('GET', '/health'); if (health.status === 200) break; } catch { /* retry */ }
    await new Promise(r => setTimeout(r, 5000));
}
check('health (cold start ok)', health?.status === 200 && health.json?.db === 'ok', JSON.stringify(health));
console.log(`      first successful /health after ${Date.now() - t0} ms`);

for (const k of ['a', 'b', 'c']) {
    const r = await call('POST', '/register-client', { body: { clientId: ids[k], clientType: 'UNKNOWN', clientName: 'smoke-test' } });
    keys[k] = r.json?.apiKey;
    check(`register ${k}`, r.status === 201 && !!keys[k], JSON.stringify(r));
}
const again = await call('POST', '/register-client', { body: { clientId: ids.a, clientName: 'smoke-test' } });
check('re-register returns existing, no new key', again.status === 200 && !again.json?.apiKey);

const init = await call('POST', '/initiate-linking', { key: keys.a, body: { clientId: ids.a } });
check('A gets 6-digit code', init.status === 200 && /^\d{6}$/.test(init.json?.code || ''), JSON.stringify(init));

const stolen = await call('POST', '/complete-linking', { key: keys.c, body: { clientId: ids.b, code: init.json?.code } });
check('C cannot complete linking as B (403)', stolen.status === 403, JSON.stringify(stolen));

const done = await call('POST', '/complete-linking', { key: keys.b, body: { clientId: ids.b, code: init.json?.code } });
check('B completes linking with A', done.status === 200 && done.json?.success === true && done.json?.linkedWith === ids.a, JSON.stringify(done));

const links = await call('GET', `/get-linked-clients?clientId=${ids.a}`, { key: keys.a });
check('A sees B linked', links.json?.data?.clients?.some(c => c.clientId === ids.b), JSON.stringify(links));

const otherLinks = await call('GET', `/get-linked-clients?clientId=${ids.a}`, { key: keys.c });
check('C cannot list A\'s links (403)', otherLinks.status === 403);

const now = new Date();
const day = now.toISOString().split('T')[0];
const submit = await call('POST', '/submit-category-summary', { key: keys.b, body: { userId: ids.b, timestamp: now.toISOString(), categorySummary: { 'Social Media': 120, News: 45 } } });
check('B submits summary', submit.status === 200 && submit.json?.status === 'success', JSON.stringify(submit));

const own = await call('GET', `/get-summary-history?userId=${ids.b}&day=${day}`, { key: keys.b });
check('B reads own summary', own.status === 200 && own.json?.[0]?.summary?.['Social Media'] === 120, JSON.stringify(own));

const linked = await call('GET', `/get-summary-history?userId=${ids.b}&day=${day}`, { key: keys.a });
check('A (linked) reads B\'s summary', linked.status === 200 && linked.json?.length === 1, JSON.stringify(linked));

const stranger = await call('GET', `/get-summary-history?userId=${ids.b}&day=${day}`, { key: keys.c });
check('C (unlinked) cannot read B\'s summary (403)', stranger.status === 403, JSON.stringify(stranger));

const strangerUnlink = await call('POST', '/unlink-device', { key: keys.c, body: { clientIdA: ids.a, clientIdB: ids.b } });
check('C cannot unlink A and B (403)', strangerUnlink.status === 403, JSON.stringify(strangerUnlink));

const cats = await call('POST', '/get-category-mapping', { key: keys.a, body: { domains: ['facebook.com', 'WWW.BBC.COM', 'localhost', 'https://x.example/path'], packages: ['com.instagram.android', 'com.x/../y'] } });
check('categories: known domains', cats.json?.['facebook.com'] === 'Social Media' && cats.json?.['WWW.BBC.COM'] === 'News', JSON.stringify(cats));
check('categories: odd domains -> Other', cats.json?.['localhost'] === 'Other' && cats.json?.['https://x.example/path'] === 'Other', JSON.stringify(cats));
check('categories: Play package + hostile package', cats.json?.['com.instagram.android'] === 'Social Media' && cats.json?.['com.x/../y'] === 'Other', JSON.stringify(cats));

const unlink = await call('POST', '/unlink-device', { key: keys.a, body: { clientIdA: ids.a, clientIdB: ids.b } });
check('A unlinks B', unlink.status === 200 && unlink.json?.success === true, JSON.stringify(unlink));

const after = await call('GET', `/get-summary-history?userId=${ids.b}&day=${day}`, { key: keys.a });
check('after unlink A can no longer read B (403)', after.status === 403, JSON.stringify(after));

console.log(failed === 0 ? '\nALL CHECKS PASSED' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
```

- [ ] **Step 2: Run against the deployed backend**

Run: `cd ~/Documents/GitHub/SurfSense/backend && node scripts/smoke.mjs`
Expected: every line `PASS`, ends with `ALL CHECKS PASSED`, exit 0. Note the printed cold-start time for the client plans (they must use a timeout above it).

- [ ] **Step 3: If anything fails**

Use `flyctl logs -a usage-tracker-backend` and fix the cause with a test first (superpowers:systematic-debugging). Do not weaken a check to make it pass.

- [ ] **Step 4: Prove scale-to-zero really works**

Run:
```bash
flyctl status -a usage-tracker-backend
sleep 330   # wait for the idle auto-stop
flyctl status -a usage-tracker-backend
node scripts/smoke.mjs 2>&1 | sed -n 1,3p
```
Expected: the machine shows `stopped` after the wait, and the first smoke line still passes, with the cold-start time printed. Record that number.

- [ ] **Step 5: Commit**

```bash
cd ~/Documents/GitHub/SurfSense
git add backend/scripts/smoke.mjs
git commit -m "backend: add live smoke test incl. authorization negatives and cold start" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Keep-alive, ownership notes, push

**Files:**
- Create: `.github/workflows/keepalive.yml`
- Modify: `~/Documents/GitHub/CLAUDE.md` (Fly mapping row)
- Memory: update a project note

**Interfaces:**
- Consumes: the public `/health` endpoint (touches the DB).

- [ ] **Step 1: Keep-alive workflow**

Create `.github/workflows/keepalive.yml`:

```yaml
name: keepalive
on:
  schedule:
    - cron: '17 6 */2 * *'   # every 2 days
  workflow_dispatch: {}
jobs:
  ping:
    runs-on: ubuntu-latest
    steps:
      - name: Ping backend (wakes Fly and touches the free Supabase project)
        run: curl --fail --silent --show-error --retry 5 --retry-delay 15 --max-time 60 https://usage-tracker-backend.fly.dev/health
```

Known limit: GitHub disables scheduled workflows after 60 days without repo activity; re-enable by pushing any commit or running the workflow manually. Real user traffic also counts as activity.

- [ ] **Step 2: Update the shared Fly mapping (user decision 2026-10-12)**

In `~/Documents/GitHub/CLAUDE.md`, change the row
`| \`usage-tracker-backend\` | \`usage-tracker-backend/\` |`
to
`| \`usage-tracker-backend\` | \`SurfSense/backend/\` |`.
Leave the old `~/Documents/GitHub/usage-tracker-backend/` directory in place (it is the stale Firebase-era variant and contains an old `.env`); tell the user it can be archived or deleted when they choose. Do not delete it.

- [ ] **Step 3: Secrets scan, then push**

Run:
```bash
cd ~/Documents/GitHub/SurfSense
git status --short | head -20      # only the unrelated iOS/Android/extension changes may remain
git log --oneline origin/HEAD..HEAD | cat
git push origin HEAD
```
Expected: the pre-push hook passes. If it blocks, read the message and fix the cause; never bypass with `SKIP_SECRETS_SCAN=1` without the user's explicit permission. If `.github/workflows` is rejected for a missing token scope, run `gh auth refresh -s workflow` (see the user's `gh auth setup-git` note) and retry.

- [ ] **Step 4: Record the new state in memory**

Write/update a project memory note: backend live at `https://usage-tracker-backend.fly.dev`, Free Supabase org `cpjqdstaljfkqeggtupk` / project `rxwtuegccdhygbxvwzvo` (no backups, pause risk, keep-alive workflow), categorizer is LLM-free, smoke script location, cold-start time measured, sibling `usage-tracker-backend/` dir is stale.

- [ ] **Step 5: Hand off to the client plans**

Report the verified base URL, cold-start time and smoke results. The next plans (separate documents): Android client fixes + features + redesign + release; extension client fixes + features + redesign + release. They start with Plan 2's step "centralize the base URL".

---

## Self-review

**Spec coverage.** Part 1 (database, Fly, Dockerfile LTS, secrets, tests, smoke, `/health`): Tasks 5, 6, 7. Categorizer section (no LLM, list before regexes, `www.`/parent lookup, Play Store, OpenAI removal, transient-not-cached): Tasks 1 to 3. Decisions (scale-to-zero, Fly mapping update): Tasks 6, 8. Free-plan caveats (pause, no backups): Task 8 keep-alive and notes. Gap noted: "Log how many lookups end as Other and what users override" and "one-tap override" belong to the client/feature plans (they need client UI and an API for overrides); the categorizer only needs to log. A log line for Other-resolved packages is not added here to avoid noise; add it with the override feature. Parts 2 to 5 (clients, features, redesign, release) are intentionally separate plans.

**Placeholder scan.** No TBD/TODO; every code step has code. Step 3 of Task 1 has a data-dependent fallback that gives the exact command to run.

**Type consistency.** `createCategorizer({cache, local, play, logger})` matches the wiring in Task 3 step 5; `fetchPlayCategory` statuses (`ok|not_found|error|invalid`) match the branches in `categorize.js`; `getLinkedClients` returns `{clientId,...}` objects, mapped with `.map(c => c.clientId)` in Task 4; `canReadSummary`/`isPartyToLink` names match between `authz.js`, tests and `server.js`.

**Review Focus.** 1: Task 2 tests; 2: Tasks 2 and 3 tests; 3: Task 1 tests and Task 7 odd-domain checks; 4: Task 4 tests and Task 7 negatives; 5: Task 7 steps 2 and 4.
