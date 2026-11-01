/**
 * Stamps extension_clients.last_active after a successful sync. Best effort: it never throws and never
 * delays or fails the request that triggered it (a missing column or a failed write is only logged).
 * Returns true when the write went through.
 */
export async function touchLastActive(db, clientId, log = null, now = () => new Date()) {
    if (typeof clientId !== 'string' || clientId.length === 0) return false;
    try {
        const { error } = await db
            .from('extension_clients')
            .update({ last_active: now().toISOString() })
            .eq('client_id', clientId);
        if (error) {
            log?.warn?.({ err: error.message, clientId }, 'last_active update failed');
            return false;
        }
        return true;
    } catch (err) {
        log?.warn?.({ err: err && err.message, clientId }, 'last_active update threw');
        return false;
    }
}
