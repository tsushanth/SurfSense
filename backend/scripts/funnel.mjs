// Read-only activation funnel from the production database. Prints counts only.
//   SUPABASE_URL=... SUPABASE_SERVICE_KEY=... node scripts/funnel.mjs [--cutoff 2026-10-13] [--exclude-prefix abcd1234,...] [--json]
// Selects only ids, type, name and dates; nothing is written and no rows are printed.
import { createClient } from '@supabase/supabase-js';
import { computeFunnel, formatFunnel } from '../funnel.js';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
    const i = args.indexOf(`--${name}`);
    return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const cutoff = opt('cutoff', '2026-10-13');
const excludePrefixes = opt('exclude-prefix', '').split(',').map((s) => s.trim()).filter(Boolean);

const { SUPABASE_URL, SUPABASE_SERVICE_KEY } = process.env;
if (!SUPABASE_URL || !SUPABASE_SERVICE_KEY) {
    console.error('Set SUPABASE_URL and SUPABASE_SERVICE_KEY');
    process.exit(1);
}
const db = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);

async function all(table, columns, order) {
    const rows = [];
    for (let from = 0; ; from += 1000) {
        const { data, error } = await db.from(table).select(columns).order(order).range(from, from + 999);
        if (error) throw new Error(`${table}: ${error.message}`);
        rows.push(...data);
        if (data.length < 1000) return rows;
    }
}

const clients = await all('extension_clients', 'client_id, client_type, client_name, created_at', 'client_id');
const summaries = await all('daily_summaries', 'user_id, day', 'id');
const links = await all('client_links', 'client_a, client_b', 'id');
const result = computeFunnel({ clients, summaries, links, now: new Date(), cutoff, excludePrefixes });
console.log(args.includes('--json') ? JSON.stringify(result, null, 2) : formatFunnel(result));
