/**
 * Sends backend failures to the unified app-failure-reporter Worker (it dedupes and emails).
 * Fire-and-forget: never throws, never blocks a request.
 *
 * Off unless FAILURE_REPORTER_KEY is set (Fly secret), and always off under NODE_ENV=test/development
 * or FAILURE_REPORTER_DISABLED=1. Needs a `surfsense#backend` entry in the reporter's APP_KEYS.
 *
 * Privacy: callers pass stable messages and route templates only. Never pass device ids, API keys,
 * package names, hostnames, or request bodies: those are the user's browsing/app usage.
 */
const ENDPOINT = () => process.env.FAILURE_REPORTER_URL || 'https://app-failure-reporter.t-sushanth.workers.dev/v1/report';
const VERSION = () => process.env.FLY_IMAGE_REF || process.env.npm_package_version || 'unknown';

export function isEnabled() {
    return Boolean(process.env.FAILURE_REPORTER_KEY)
        && process.env.FAILURE_REPORTER_DISABLED !== '1'
        && process.env.NODE_ENV !== 'test'
        && process.env.NODE_ENV !== 'development';
}

async function post(kind, flow, err, context, timeoutMs) {
    if (!isEnabled()) return;
    try {
        const e = err instanceof Error ? err : new Error(String(err));
        await fetch(ENDPOINT(), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Report-Key': process.env.FAILURE_REPORTER_KEY },
            body: JSON.stringify({
                kind, platform: 'backend', version: VERSION(), flow,
                message: String(e.message || e.name).slice(0, 300), stack: e.stack ?? '', context,
            }),
            signal: AbortSignal.timeout(timeoutMs),
        });
    } catch { /* reporting must never cause a failure of its own */ }
}

/** A handled server error (5xx). `flow` must be a route template, never a concrete URL. */
export function reportBackendError(method, flow, err, statusCode = 500) {
    if (statusCode < 500) return;
    void post('backend_error', `${method} ${flow}`, err, { status: String(statusCode) }, 5000);
}

/** Convenience for Express handlers: uses the matched route template (e.g. /client/:id), not req.path. */
export function reportRouteError(req, err, statusCode = 500) {
    reportBackendError(req.method, req.route?.path ?? 'unmatched', err, statusCode);
}

/** A background flow or dependency failed (cron-like, third party, swallowed catch). */
export function reportFailure(flow, err, context = {}) {
    void post('failure', flow, err, context, 5000);
}

/** Process is about to die: await this before process.exit so the email goes out. */
export function reportCrash(flow, err) {
    return post('crash', flow, err, {}, 2500);
}
