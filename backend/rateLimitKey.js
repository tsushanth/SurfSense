/**
 * Who a rate limit counts against.
 *
 * On Fly, `req.ip` with `trust proxy 1` is the last X-Forwarded-For entry, which is the app's own public address, so
 * every caller shared one bucket. Fly's proxy sets Fly-Client-IP to the real caller. IPv6 callers are grouped by /64,
 * the block one connection usually owns, so rotating addresses does not reset a limit.
 */
export function rateLimitKey(req) {
    const ip = req.headers['fly-client-ip'] || req.ip || '';
    if (!ip.includes(':')) return ip;
    if (ip.includes('.')) return ip.slice(ip.lastIndexOf(':') + 1); // IPv4-mapped
    const [head, tail] = ip.split('::');
    const left = head ? head.split(':') : [];
    const right = tail ? tail.split(':') : [];
    const groups = tail === undefined ? left : [...left, ...Array(8 - left.length - right.length).fill('0'), ...right];
    return `${groups.slice(0, 4).map((g) => parseInt(g, 16).toString(16)).join(':')}::/64`;
}
