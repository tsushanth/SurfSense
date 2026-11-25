import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createProxy } from './server.js';

const listen = (server) => new Promise((r) => server.listen(0, '127.0.0.1', () => r(`http://127.0.0.1:${server.address().port}`)));

async function withProxy(fn) {
    const seen = [];
    const upstream = http.createServer(async (req, res) => {
        let body = '';
        for await (const c of req) body += c;
        seen.push({ method: req.method, url: req.url, auth: req.headers.authorization, body });
        res.writeHead(req.url.startsWith('/get-linked-clients') ? 401 : 201, { 'content-type': 'application/json', 'retry-after': '7' });
        res.end('{"ok":true}');
    });
    const proxy = createProxy(await listen(upstream));
    const base = await listen(proxy);
    try { await fn(base, seen); } finally { proxy.close(); upstream.close(); }
}

test('forwards method, path, query, key and body, and returns status and body', () => withProxy(async (base, seen) => {
    const r = await fetch(`${base}/register-client`, { method: 'POST', headers: { authorization: 'Bearer k', 'content-type': 'application/json' }, body: '{"a":1}' });
    assert.equal(r.status, 201);
    assert.equal(r.headers.get('retry-after'), '7');
    assert.deepEqual(await r.json(), { ok: true });
    const q = await fetch(`${base}/get-linked-clients?clientId=abc`);
    assert.equal(q.status, 401);
    assert.deepEqual(seen, [
        { method: 'POST', url: '/register-client', auth: 'Bearer k', body: '{"a":1}' },
        { method: 'GET', url: '/get-linked-clients?clientId=abc', auth: undefined, body: '' },
    ]);
}));

test('everything else is refused without reaching the backend', () => withProxy(async (base, seen) => {
    for (const [method, path] of [['GET', '/'], ['GET', '/register-client'], ['POST', '/track-usage'], ['GET', '/client/a/b']]) {
        assert.equal((await fetch(base + path, { method })).status, 404, `${method} ${path}`);
    }
    const hostChange = await new Promise((resolve) => {
        const u = new URL(base);
        http.request({ host: u.hostname, port: u.port, path: '//evil.invalid/health' }, (res) => { res.resume(); resolve(res.statusCode); }).end();
    });
    assert.equal(hostChange, 404);
    assert.equal(seen.length, 0);
}));

test('an oversized body is refused', () => withProxy(async (base, seen) => {
    const r = await fetch(`${base}/submit-category-summary`, { method: 'POST', body: 'x'.repeat(1_000_001) });
    assert.equal(r.status, 413);
    assert.equal(seen.length, 0);
}));

test('an unreachable backend answers 502', async () => {
    const proxy = createProxy('http://127.0.0.1:1');
    const base = await listen(proxy);
    try { assert.equal((await fetch(`${base}/health`)).status, 502); } finally { proxy.close(); }
});
