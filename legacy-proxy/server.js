// Forwards the requests of Android builds older than 10.0.0 to the live backend.
// Those builds have the old Cloud Run address compiled in and cannot be told a new one, so this runs as a Cloud Run
// service with the same name in the same project and region, which brings that address back. No secrets, no storage.
import http from 'node:http';
import { pathToFileURL } from 'node:url';

export const DEFAULT_UPSTREAM = 'https://usage-tracker-backend.fly.dev';
const MAX_BODY_BYTES = 1_000_000;
const UPSTREAM_TIMEOUT_MS = 25_000;

// Exactly what the old app calls (ApiService.kt of versionCode 8), plus /health.
const ROUTES = [
    ['GET', /^\/health$/],
    ['POST', /^\/register-client$/],
    ['GET', /^\/client\/[^/]+$/],
    ['POST', /^\/get-category-mapping$/],
    ['POST', /^\/submit-category-summary$/],
    ['GET', /^\/get-summary-history$/],
    ['GET', /^\/get-linked-clients$/],
    ['POST', /^\/initiate-linking$/],
    ['POST', /^\/complete-linking$/],
    ['POST', /^\/unlink-device$/],
];

const send = (res, status, body, headers = {}) => {
    res.writeHead(status, { 'content-type': 'application/json', ...headers });
    res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
};

async function readBody(req) {
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) return null;
        chunks.push(chunk);
    }
    return Buffer.concat(chunks);
}

export function createProxy(upstream = DEFAULT_UPSTREAM) {
    const base = new URL(upstream);
    return http.createServer(async (req, res) => {
        try {
            const target = new URL(req.url, base);
            // A request line like "//host/path" must not choose the destination.
            if (target.origin !== base.origin || !ROUTES.some(([m, p]) => m === req.method && p.test(target.pathname))) {
                return send(res, 404, { error: 'Not found' });
            }
            const body = req.method === 'POST' ? await readBody(req) : undefined;
            if (body === null) return send(res, 413, { error: 'Request too large' });

            const headers = {};
            for (const name of ['authorization', 'content-type', 'accept']) {
                if (req.headers[name]) headers[name] = req.headers[name];
            }
            const answer = await fetch(target, {
                method: req.method,
                headers,
                body,
                redirect: 'manual',
                signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
            });
            const out = {};
            const type = answer.headers.get('content-type');
            const retry = answer.headers.get('retry-after');
            if (type) out['content-type'] = type;
            if (retry) out['retry-after'] = retry;
            send(res, answer.status, Buffer.from(await answer.arrayBuffer()), out);
        } catch (err) {
            // Method and error name only: the path can hold a device id.
            console.error(`upstream failed: ${req.method} ${err?.name}`);
            if (!res.headersSent) send(res, 502, { error: 'Upstream unavailable' });
        }
    });
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
    const port = Number(process.env.PORT) || 8080;
    createProxy(process.env.UPSTREAM || DEFAULT_UPSTREAM).listen(port, () => console.log(`legacy proxy on ${port}`));
}
