/**
 * Detects a provider-wide outage from a stream of per-call results, so a silent degradation
 * (every Play Store lookup failing -> every app categorized "Other") produces one alert instead of nothing.
 * Alerts when, within `windowMs`, there are at least `minSamples` calls and the error share >= `threshold`.
 * Re-alerts at most once per `cooldownMs`. Holds counts only, never the keys looked up.
 */
export function createOutageMonitor({ windowMs = 15 * 60_000, minSamples = 10, threshold = 0.8, cooldownMs = 60 * 60_000, now = Date.now, onOutage }) {
    let samples = []; // [timestamp, isError]
    let lastAlert = -Infinity;
    return {
        record(isError) {
            const t = now();
            samples.push([t, Boolean(isError)]);
            samples = samples.filter(([ts]) => t - ts <= windowMs);
            const total = samples.length;
            const errors = samples.filter(([, e]) => e).length;
            if (total >= minSamples && errors / total >= threshold && t - lastAlert >= cooldownMs) {
                lastAlert = t;
                try { onOutage({ errors, total }); } catch { /* alerting must not break the request path */ }
            }
        },
    };
}
