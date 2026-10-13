const isId = (v) => typeof v === 'string' && v.length > 0;

/** A client may read its own summary, or a linked device's. */
export function canReadSummary(requesterId, targetId, linkedIds) {
    if (!isId(requesterId) || !isId(targetId)) return false;
    if (requesterId === targetId) return true;
    return Array.isArray(linkedIds) && linkedIds.includes(targetId);
}

/** A client may only unlink a pair it belongs to. */
export function isPartyToLink(requesterId, a, b) {
    if (!isId(requesterId) || !isId(a) || !isId(b)) return false;
    return requesterId === a || requesterId === b;
}
