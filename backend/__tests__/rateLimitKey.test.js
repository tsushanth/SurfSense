import { describe, it, expect, afterEach } from 'vitest';
import express from 'express';
import rateLimit from 'express-rate-limit';
import { rateLimitKey } from '../rateLimitKey.js';

const req = (headers, ip = '66.241.124.1') => ({ headers, ip });

describe('rateLimitKey', () => {
    it('uses the caller Fly reports, not the shared proxy address', () => {
        expect(rateLimitKey(req({ 'fly-client-ip': '203.0.113.7' }))).toBe('203.0.113.7');
    });

    it('falls back to req.ip when not behind Fly', () => {
        expect(rateLimitKey(req({}, '127.0.0.1'))).toBe('127.0.0.1');
    });

    it('groups IPv6 addresses by /64', () => {
        const a = rateLimitKey(req({ 'fly-client-ip': '2001:db8:1:2:aaaa::1' }));
        expect(rateLimitKey(req({ 'fly-client-ip': '2001:0db8:0001:0002:ffff:ffff:ffff:ffff' }))).toBe(a);
        expect(rateLimitKey(req({ 'fly-client-ip': '2001:db8:1:3::1' }))).not.toBe(a);
        expect(rateLimitKey(req({ 'fly-client-ip': '2001:db8::1' }))).toBe('2001:db8:0:0::/64');
    });

    it('reads an IPv4-mapped address as IPv4', () => {
        expect(rateLimitKey(req({}, '::ffff:203.0.113.7'))).toBe('203.0.113.7');
    });
});

describe('a limiter keyed this way', () => {
    let server;
    afterEach(() => server?.close());

    it('lets a second caller through when the first has used up its limit', async () => {
        const app = express();
        app.set('trust proxy', 1);
        app.get('/', rateLimit({ windowMs: 60_000, max: 1, keyGenerator: rateLimitKey }), (r, res) => res.send('ok'));
        server = app.listen(0);
        const hit = async (ip) => (await fetch(`http://127.0.0.1:${server.address().port}/`, {
            // Same forwarded-for on every request, as seen in production.
            headers: { 'fly-client-ip': ip, 'x-forwarded-for': `${ip}, 66.241.124.1` },
        })).status;
        expect(await hit('203.0.113.7')).toBe(200);
        expect(await hit('203.0.113.7')).toBe(429);
        expect(await hit('198.51.100.9')).toBe(200);
    });
});
