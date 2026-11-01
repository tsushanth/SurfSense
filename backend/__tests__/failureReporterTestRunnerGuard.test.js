import { it, expect, vi, afterEach } from 'vitest';
import { reportFailure, reportBackendError, isEnabled } from '../failureReporter.js';

// Regression: the reporter used to be off only for NODE_ENV test/development or FAILURE_REPORTER_DISABLED=1, so a test run
// with NODE_ENV overridden and a reporter key in the environment posted fake errors to the live reporter.
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllEnvs(); });

it('never posts under the test runner even with a key set and NODE_ENV=production', async () => {
    expect(process.env.VITEST).toBeTruthy(); // sanity: running under vitest
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true });
    vi.stubEnv('NODE_ENV', 'production');
    vi.stubEnv('FAILURE_REPORTER_DISABLED', '');
    vi.stubEnv('FAILURE_REPORTER_FORCE', '');
    vi.stubEnv('FAILURE_REPORTER_KEY', 'k');
    reportFailure('x', new Error('must not leave the process'));
    reportBackendError('GET', '/x', new Error('nor this'), 500);
    await new Promise((r) => setTimeout(r, 20));
    expect(isEnabled()).toBe(false);
    expect(fetchSpy).not.toHaveBeenCalled();
});
