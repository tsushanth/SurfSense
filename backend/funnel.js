// Aggregate activation funnel computed from rows the backend already stores.
// Pure functions only: no I/O, no personal data. Inputs are client ids and dates;
// output is counts and percentages.
//
// Steps (per client_type):
//   registered   a client row exists (app opened once and reached the server)
//   firstSync    at least one daily_summaries row (usage access granted and a sync worked;
//                for the extension: browsing was summarised)
//   twoDays      summaries on 2 or more distinct days
//   returned7d   a summary on a day >= 7 days after registration (only counted for
//                clients old enough to have had the chance)
//   linked       the client appears in a device pairing
// Reinstalls and "clear data" create a new client id, so `registered` is an upper bound on people.

const DAY_MS = 24 * 60 * 60 * 1000;
export const STEPS = ['registered', 'firstSync', 'twoDays', 'returned7d', 'linked'];

export function isExcluded(client, excludePrefixes = []) {
    const id = String(client.client_id ?? '');
    const name = String(client.client_name ?? '');
    if (name === 'smoke-test' || id.startsWith('smoke-')) return true;
    return excludePrefixes.some((p) => p && id.startsWith(p));
}

function dayStart(value) {
    // 'YYYY-MM-DD' or ISO timestamp -> UTC midnight ms
    const d = new Date(String(value).length === 10 ? `${value}T00:00:00Z` : value);
    return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
}

export function pct(n, d) {
    return d > 0 ? Math.round((1000 * n) / d) / 10 : null;
}

function emptyBucket() {
    return { registered: 0, firstSync: 0, twoDays: 0, returned7dEligible: 0, returned7d: 0, linked: 0 };
}

/**
 * @param {{clients: Array, summaries: Array, links: Array, now: Date, cutoff: string,
 *          excludePrefixes?: string[]}} input
 * clients: {client_id, client_type, client_name, created_at}; summaries: {user_id, day};
 * links: {client_a, client_b}. cutoff: 'YYYY-MM-DD'; clients created before it go to `before`.
 */
export function computeFunnel({ clients, summaries, links, now, cutoff, excludePrefixes = [] }) {
    const daysByUser = new Map();
    for (const s of summaries) {
        if (!daysByUser.has(s.user_id)) daysByUser.set(s.user_id, new Set());
        daysByUser.get(s.user_id).add(String(s.day).slice(0, 10));
    }
    const linked = new Set();
    for (const l of links) { linked.add(l.client_a); linked.add(l.client_b); }

    const cutoffMs = dayStart(cutoff);
    const nowMs = now.getTime();
    const out = {};
    let excluded = 0;

    for (const c of clients) {
        if (isExcluded(c, excludePrefixes)) { excluded += 1; continue; }
        const type = c.client_type || 'UNKNOWN';
        const period = new Date(c.created_at).getTime() < cutoffMs ? 'before' : 'after';
        out[type] ??= { before: emptyBucket(), after: emptyBucket() };
        const b = out[type][period];
        b.registered += 1;
        const days = daysByUser.get(c.client_id);
        if (days && days.size >= 1) b.firstSync += 1;
        if (days && days.size >= 2) b.twoDays += 1;
        const createdMs = dayStart(c.created_at);
        if (nowMs - createdMs >= 8 * DAY_MS) {
            b.returned7dEligible += 1;
            if (days && [...days].some((d) => dayStart(d) - createdMs >= 7 * DAY_MS)) b.returned7d += 1;
        }
        if (linked.has(c.client_id)) b.linked += 1;
    }

    const rates = (b) => ({
        ...b,
        firstSyncPct: pct(b.firstSync, b.registered),
        twoDaysPctOfFirstSync: pct(b.twoDays, b.firstSync),
        returned7dPctOfEligible: pct(b.returned7d, b.returned7dEligible),
        linkedPctOfRegistered: pct(b.linked, b.registered),
    });
    const result = { cutoff, excludedClients: excluded, byType: {} };
    for (const [type, v] of Object.entries(out)) {
        result.byType[type] = { before: rates(v.before), after: rates(v.after) };
    }
    return result;
}

export function formatFunnel(result) {
    const lines = [`Funnel (cutoff ${result.cutoff}; ${result.excludedClients} test/excluded clients removed)`];
    for (const [type, v] of Object.entries(result.byType)) {
        for (const period of ['before', 'after']) {
            const b = v[period];
            lines.push(
                `${type} ${period}: registered ${b.registered} | first sync ${b.firstSync} (${b.firstSyncPct ?? 'n/a'}%)` +
                ` | 2+ days ${b.twoDays} (${b.twoDaysPctOfFirstSync ?? 'n/a'}% of first sync)` +
                ` | returned after 7d ${b.returned7d}/${b.returned7dEligible} (${b.returned7dPctOfEligible ?? 'n/a'}%)` +
                ` | linked ${b.linked} (${b.linkedPctOfRegistered ?? 'n/a'}%)`
            );
        }
    }
    return lines.join('\n');
}
