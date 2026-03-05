import crypto from 'crypto';
import { supabase } from '../supabase.js';
import { logger } from '../logger.js';

/**
 * Hash an API key using SHA-256
 */
export function hashApiKey(apiKey) {
    return crypto.createHash('sha256').update(apiKey).digest('hex');
}

/**
 * Generate a new API key (returned to client once on registration)
 */
export function generateApiKey() {
    return crypto.randomBytes(32).toString('hex');
}

/**
 * Authentication middleware: validates Bearer token against stored API key hashes.
 * Attaches req.clientId and req.clientType on success.
 */
export async function authenticateClient(req, res, next) {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return res.status(401).json({ error: 'Missing or invalid authorization header' });
    }

    const token = authHeader.substring(7);
    if (!token || token.length < 16) {
        return res.status(401).json({ error: 'Invalid API key format' });
    }

    const tokenHash = hashApiKey(token);

    try {
        const { data: client, error } = await supabase
            .from('extension_clients')
            .select('client_id, client_type')
            .eq('api_key_hash', tokenHash)
            .single();

        if (error || !client) {
            return res.status(401).json({ error: 'Invalid API key' });
        }

        req.clientId = client.client_id;
        req.clientType = client.client_type;
        next();
    } catch (err) {
        logger.error({ err }, 'Auth middleware error');
        return res.status(500).json({ error: 'Authentication service unavailable' });
    }
}
