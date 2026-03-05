import crypto from 'crypto';
import { supabase } from './supabase.js';
import { logger } from './logger.js';

/**
 * Generates a cryptographically secure 6-digit linking code
 * @returns {string} 6-digit code
 */
function generateLinkingCode() {
    const buffer = crypto.randomBytes(4);
    const num = buffer.readUInt32BE(0) % 900000 + 100000;
    return num.toString();
}

/**
 * Initiates a link request from one client
 * @param {string} clientId - ID of the client initiating the link
 * @returns {Promise<string>}
 */
async function initiateLinking(clientId) {
    const code = generateLinkingCode();
    const expiresAt = new Date(Date.now() + 15 * 60 * 1000).toISOString();

    const { error } = await supabase
        .from('linking_requests')
        .upsert({
            client_id: clientId,
            code,
            expires_at: expiresAt
        }, { onConflict: 'client_id' });

    if (error) throw new Error(`Failed to create linking request: ${error.message}`);

    logger.info({ clientId }, 'Linking code generated');
    return code;
}

/**
 * Completes the linking using a 6-digit code
 * @param {string} otherClientId - ID of the client entering the code
 * @param {string} code - The linking code
 */
async function completeLinking(otherClientId, code) {
    // Find the linking request by code
    const { data: request, error: findError } = await supabase
        .from('linking_requests')
        .select('*')
        .eq('code', code)
        .single();

    if (findError || !request) {
        throw new Error('Invalid or expired code');
    }

    // Check if expired
    if (new Date() > new Date(request.expires_at)) {
        await supabase
            .from('linking_requests')
            .delete()
            .eq('client_id', request.client_id);
        throw new Error('Code expired');
    }

    const initiatorClientId = request.client_id;

    // Canonical order to avoid duplicate link variations
    const [clientA, clientB] = [initiatorClientId, otherClientId].sort();

    // Create the link
    const { error: linkError } = await supabase
        .from('client_links')
        .insert({
            client_a: clientA,
            client_b: clientB
        });

    if (linkError) {
        // Check if it's a duplicate error (link already exists)
        if (linkError.code !== '23505') {
            throw new Error(`Failed to create link: ${linkError.message}`);
        }
    }

    // Delete the linking request
    await supabase
        .from('linking_requests')
        .delete()
        .eq('client_id', initiatorClientId);

    logger.info({ initiatorClientId, otherClientId }, 'Devices linked');
    return { success: true, linkedWith: initiatorClientId };
}

/**
 * Removes a link between any two clients
 */
async function unlinkClients(clientId1, clientId2) {
    const [clientA, clientB] = [clientId1, clientId2].sort();

    const { error } = await supabase
        .from('client_links')
        .delete()
        .eq('client_a', clientA)
        .eq('client_b', clientB);

    if (error) throw new Error(`Failed to unlink clients: ${error.message}`);

    logger.info({ clientA, clientB }, 'Devices unlinked');
}

/**
 * Get all clients linked to the given one.
 * Uses batch query to avoid N+1 problem.
 */
async function getLinkedClients(clientId) {
    // Query both directions of the link
    const [resultA, resultB] = await Promise.all([
        supabase.from('client_links').select('client_b').eq('client_a', clientId),
        supabase.from('client_links').select('client_a').eq('client_b', clientId)
    ]);

    const linkedClientIds = [
        ...(resultA.data || []).map(row => row.client_b),
        ...(resultB.data || []).map(row => row.client_a)
    ];

    if (linkedClientIds.length === 0) return [];

    // Single batch query instead of N individual queries
    const { data: clientsData } = await supabase
        .from('extension_clients')
        .select('client_id, client_name, client_type')
        .in('client_id', linkedClientIds);

    return (clientsData || []).map(c => ({
        clientId: c.client_id,
        clientName: c.client_name || 'Unknown',
        clientType: c.client_type || 'Unknown'
    }));
}

/**
 * Cleans up expired codes
 */
async function deleteExpiredLinkingCodes() {
    const { data, error } = await supabase
        .from('linking_requests')
        .delete()
        .lte('expires_at', new Date().toISOString())
        .select();

    if (error) throw new Error(`Failed to cleanup expired codes: ${error.message}`);

    return data?.length || 0;
}

/**
 * Clean up inactive links based on lastActivityAt (30 days)
 */
async function deleteInactiveLinks() {
    const cutoff = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString();

    const { data, error } = await supabase
        .from('client_links')
        .delete()
        .lt('last_activity_at', cutoff)
        .select();

    if (error) throw new Error(`Failed to cleanup inactive links: ${error.message}`);

    return data?.length || 0;
}

export {
    generateLinkingCode,
    initiateLinking,
    completeLinking,
    unlinkClients,
    getLinkedClients,
    deleteExpiredLinkingCodes,
    deleteInactiveLinks
};
