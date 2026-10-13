/**
 * Resolve to `promise`'s value if it settles within `ms`, otherwise to `fallback`.
 * Rejects if the promise rejects before the deadline. The timer is always cleared.
 * The original promise is NOT cancelled and keeps running after the deadline.
 */
export function withDeadline(promise, ms, fallback) {
    let timer;
    const timeout = new Promise((resolve) => {
        timer = setTimeout(() => resolve(fallback), ms);
    });
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}
