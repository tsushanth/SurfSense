import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Contract: every 4xx the backend sends has a stable `code` (English `error` text is kept), and every
// code the Android app maps to a localized message is one the backend really sends.
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

const server = read('server.js');
const auth = read('middleware/auth.js');
const linking = read('linkingFunctions.js');

function clientErrorBodies(src) {
    // res.status(4xx).json({ ... }) and rate-limit `message: { ... }` objects
    const out = [];
    for (const m of src.matchAll(/status\(\s*(4\d\d)\s*\)\.json\(\{([^}]*)\}/g)) out.push(m[2]);
    for (const m of src.matchAll(/message:\s*\{([^}]*error:[^}]*)\}/g)) out.push(m[1]);
    return out;
}

describe('backend error codes', () => {
    it('every 4xx JSON body has an error text and an UPPER_SNAKE code', () => {
        const bodies = [...clientErrorBodies(server), ...clientErrorBodies(auth)];
        expect(bodies.length).toBeGreaterThanOrEqual(25);
        for (const b of bodies) {
            expect(b).toMatch(/error:\s*'[^']+'/);
            expect(b).toMatch(/code:\s*'[A-Z][A-Z_]+'/);
        }
    });

    it('LinkingError carries a code and every throw site passes one', () => {
        expect(linking).toMatch(/this\.code = code/);
        const throws = [...linking.matchAll(/new LinkingError\(([^)]*)\)/g)].map((m) => m[1]);
        expect(throws.length).toBeGreaterThanOrEqual(3);
        for (const t of throws) expect(t).toMatch(/'[A-Z][A-Z_]+'\s*$/);
    });

    it('the complete-linking route forwards the LinkingError code', () => {
        expect(server).toMatch(/error: error\.message, code: error\.code/);
    });

    it('every code the Android client maps exists in the backend', () => {
        const kt = fs.readFileSync(
            path.join(root, '..', 'android', 'app', 'src', 'main', 'java', 'com', 'kreativekoala', 'surfsense', 'net', 'ApiFailure.kt'),
            'utf8'
        );
        const mapped = [...kt.matchAll(/"([A-Z][A-Z_]+)"\s+to\s+R\.string\./g)].map((m) => m[1]);
        expect(mapped.length).toBeGreaterThanOrEqual(5);
        const backend = new Set(
            [...(server + auth + linking).matchAll(/'([A-Z][A-Z_]+)'/g)].map((m) => m[1])
        );
        for (const c of mapped) expect(backend.has(c), `${c} is mapped in the app but never sent by the backend`).toBe(true);
    });
});
