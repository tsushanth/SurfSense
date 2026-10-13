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
