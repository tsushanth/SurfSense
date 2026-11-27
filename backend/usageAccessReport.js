// Why an Android phone is not letting SurfSense read app usage, as the app reports it (POST /usage-access-refused).
// Only fixed reason codes plus the phone model, Android release and app version reach the failure reporter: never the client id.

export const REFUSAL_REASONS = ['not_now', 'returned_without_access', 'settings_unavailable'];

const clean = (value, max) => (typeof value === 'string' ? value.replace(/[^\x20-\x7E]/g, '').trim().slice(0, max) : '');

/** `{ reason, context }` for a valid report, else null. `device` and `android` use the names the reporter's test-device rule reads. */
export function usageAccessReport(body) {
    const reason = body?.reason;
    if (!REFUSAL_REASONS.includes(reason)) return null;
    return {
        reason,
        context: {
            reason,
            device: clean(body.device, 80),
            android: clean(body.android, 20),
            appVersion: clean(body.appVersion, 20),
        },
    };
}
