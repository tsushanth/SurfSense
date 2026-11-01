import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { reportBackendError, reportRouteError, reportFailure, isEnabled } from '../failureReporter.js';
import { createOutageMonitor } from '../outageMonitor.js';

const flush = () => new Promise((r) => setTimeout(r, 0));

describe('failureReporter', () => {
    let fetchSpy;
    beforeEach(() => {
        fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true });
    });
    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllEnvs();
    });

    it('is silent under NODE_ENV=test even with a key', async () => {
        vi.stubEnv('FAILURE_REPORTER_KEY', 'k');
        reportFailure('x', new Error('boom'));
        await flush();
        expect(isEnabled()).toBe(false);
        expect(fetchSpy).not.toHaveBeenCalled();
    });

    it('is off without a key even outside test', async () => {
        vi.stubEnv('NODE_ENV', 'production');
        vi.stubEnv('FAILURE_REPORTER_KEY', '');
        reportFailure('x', new Error('boom'));
        await flush();
        expect(fetchSpy).not.toHaveBeenCalled();
    });

    it('posts the route template, never the concrete path or ids', async () => {
        vi.stubEnv('NODE_ENV', 'production');
        vi.stubEnv('FAILURE_REPORTER_FORCE', '1'); // the test runner would otherwise keep the reporter off
        vi.stubEnv('FAILURE_REPORTER_KEY', 'k');
        reportRouteError({ method: 'GET', path: '/client/abcdef123456', route: { path: '/client/:id' } }, new Error('db down'));
        await flush();
        expect(fetchSpy).toHaveBeenCalledTimes(1);
        const [, init] = fetchSpy.mock.calls[0];
        const body = JSON.parse(init.body);
        expect(init.headers['X-Report-Key']).toBe('k');
        expect(body).toMatchObject({ kind: 'backend_error', platform: 'backend', flow: 'GET /client/:id', context: { status: '500' } });
        expect(JSON.stringify(body)).not.toContain('abcdef123456');
    });

    it('ignores non-5xx and never throws when the reporter is down', async () => {
        vi.stubEnv('NODE_ENV', 'production');
        vi.stubEnv('FAILURE_REPORTER_KEY', 'k');
        reportBackendError('GET', '/x', new Error('bad'), 400);
        await flush();
        expect(fetchSpy).not.toHaveBeenCalled();
        fetchSpy.mockRejectedValue(new Error('network'));
        expect(() => reportFailure('f', 'string error')).not.toThrow();
        await flush();
    });
});

describe('outageMonitor', () => {
    const make = (extra = {}) => {
        let t = 0;
        const onOutage = vi.fn();
        const m = createOutageMonitor({ now: () => t, onOutage, ...extra });
        return { m, onOutage, advance: (ms) => { t += ms; } };
    };

    it('stays quiet below the minimum sample size', () => {
        const { m, onOutage } = make();
        for (let i = 0; i < 9; i++) m.record(true);
        expect(onOutage).not.toHaveBeenCalled();
    });

    it('alerts once when most lookups in the window fail, with counts only', () => {
        const { m, onOutage } = make();
        for (let i = 0; i < 12; i++) m.record(true);
        expect(onOutage).toHaveBeenCalledTimes(1);
        expect(onOutage).toHaveBeenCalledWith({ errors: 10, total: 10 });
    });

    it('does not alert on a healthy mix', () => {
        const { m, onOutage } = make();
        for (let i = 0; i < 30; i++) m.record(i % 5 === 0);
        expect(onOutage).not.toHaveBeenCalled();
    });

    it('forgets old samples and re-alerts only after the cooldown', () => {
        const { m, onOutage, advance } = make();
        for (let i = 0; i < 10; i++) m.record(true);
        advance(20 * 60_000); // window expired, cooldown (60 min) not
        for (let i = 0; i < 10; i++) m.record(true);
        expect(onOutage).toHaveBeenCalledTimes(1);
        advance(61 * 60_000);
        for (let i = 0; i < 10; i++) m.record(true);
        expect(onOutage).toHaveBeenCalledTimes(2);
    });

    it('survives a throwing handler', () => {
        const m = createOutageMonitor({ onOutage: () => { throw new Error('x'); } });
        expect(() => { for (let i = 0; i < 12; i++) m.record(true); }).not.toThrow();
    });
});
