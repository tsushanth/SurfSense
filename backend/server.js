import express from 'express';
import { OpenAI } from 'openai';
import dotenv from 'dotenv';
import cors from 'cors';
import rateLimit from 'express-rate-limit';

dotenv.config();

import { supabase } from './supabase.js';
import { logger } from './logger.js';
import { categorizeLocally } from './categorizer.js';
import { authenticateClient, generateApiKey, hashApiKey } from './middleware/auth.js';
import pLimit from 'p-limit';
import {
    initiateLinking,
    completeLinking,
    unlinkClients,
    getLinkedClients,
} from './linkingFunctions.js';

const app = express();
const port = process.env.PORT || 8080;

// Trust proxy — required for Cloud Run (behind load balancer) so express-rate-limit reads X-Forwarded-For
app.set('trust proxy', 1);

// --- CORS: restrict to allowed origins ---
const allowedOrigins = process.env.CORS_ORIGINS
    ? process.env.CORS_ORIGINS.split(',').map(o => o.trim())
    : [];

app.use(cors({
    origin: (origin, callback) => {
        // Allow requests with no origin (mobile apps, curl, server-to-server)
        if (!origin) return callback(null, true);
        // Allow Chrome extensions (chrome-extension:// scheme)
        if (origin.startsWith('chrome-extension://')) return callback(null, true);
        // Check against whitelist
        if (allowedOrigins.length === 0 || allowedOrigins.includes(origin)) {
            return callback(null, true);
        }
        callback(new Error('Not allowed by CORS'));
    },
    methods: ['GET', 'POST'],
    allowedHeaders: ['Content-Type', 'Authorization'],
}));

// --- Body parsing (Express 5 built-in, replaces body-parser) ---
app.use(express.json({ limit: '1mb' }));

// --- Rate Limiting ---
const generalLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: 100,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Too many requests, please try again later' },
});

const aiLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: 10,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Rate limit exceeded for AI categorization' },
});

const linkingLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 5,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Too many linking attempts, please try again later' },
});

app.use(generalLimiter);

// --- OpenAI client ---
const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

// Valid categories
const VALID_CATEGORIES = [
    'Social Media', 'Entertainment', 'Work/Productivity',
    'Shopping', 'Education', 'News', 'Finance', 'Health',
    'Travel', 'Food', 'Other'
];

const VALID_CLIENT_TYPES = ['CHROME_EXTENSION', 'IOS', 'ANDROID', 'UNKNOWN'];

// --- Helper: validate UUID-like string ---
function isValidClientId(id) {
    return typeof id === 'string' && id.length >= 8 && id.length <= 128;
}

// --- Categorization with retry ---
async function categorize(input, isPackage = false) {
    if (!input || input.trim() === '/' || input.trim() === '') {
        return 'Other';
    }

    // Tier 1: Check Supabase cache
    const { data: cached } = await supabase
        .from('domain_categories')
        .select('category')
        .eq('domain', input)
        .single();

    if (cached?.category) {
        return cached.category;
    }

    // Tier 2: Local categorization (pattern matching + static DB)
    const localCategory = categorizeLocally(input, isPackage);
    if (localCategory) {
        await supabase
            .from('domain_categories')
            .upsert({ domain: input, category: localCategory }, { onConflict: 'domain' });
        return localCategory;
    }

    // Tier 3: GPT-3.5-turbo fallback with retry
    const type = isPackage ? 'Android app package' : 'domain';
    const prompt = `Categorize this ${type} into exactly one category.\n\n${type}: ${input}\n\nCategories: Social Media, Entertainment, Work/Productivity, Shopping, Education, News, Finance, Health, Travel, Food, Other\n\nReply with ONLY the category name, nothing else.`;

    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            const completion = await openai.chat.completions.create({
                model: 'gpt-3.5-turbo',
                messages: [
                    { role: 'system', content: 'You categorize domains and apps. Reply with only the category name.' },
                    { role: 'user', content: prompt }
                ],
                max_tokens: 15,
                temperature: 0
            });

            let category = completion.choices[0].message.content.trim();

            // Validate the category
            if (!VALID_CATEGORIES.includes(category)) {
                const lowerCategory = category.toLowerCase();
                const match = VALID_CATEGORIES.find(c => c.toLowerCase() === lowerCategory);
                category = match || 'Other';
            }

            // Cache the result
            await supabase
                .from('domain_categories')
                .upsert({ domain: input, category }, { onConflict: 'domain' });

            logger.info({ input, category }, 'LLM categorized');
            return category;
        } catch (err) {
            if (attempt < 2 && (err.status === 429 || err.status >= 500)) {
                await new Promise(r => setTimeout(r, Math.pow(2, attempt) * 1000));
                continue;
            }
            logger.error({ input, err: err.message }, 'LLM categorization failed');
            return 'Other';
        }
    }

    return 'Other';
}

// ==========================================
// PUBLIC ROUTES (no auth required)
// ==========================================

app.get('/health', (req, res) => {
    res.json({ status: 'ok' });
});

/**
 * POST /register-client
 * Registers a new client or returns existing client info.
 * Returns an API key on first registration (store it — it won't be shown again).
 */
app.post('/register-client', async (req, res) => {
    try {
        const { clientId, clientType, clientName } = req.body;

        if (!clientId || !isValidClientId(clientId)) {
            return res.status(400).json({ error: 'Valid clientId is required (8-128 characters)' });
        }

        const resolvedType = VALID_CLIENT_TYPES.includes(clientType) ? clientType : 'UNKNOWN';
        const resolvedName = typeof clientName === 'string' ? clientName.substring(0, 255) : 'UNKNOWN';

        // Check if client already exists
        const { data: existing, error: fetchError } = await supabase
            .from('extension_clients')
            .select('client_id, created_at, last_active, notification_enabled, api_key_hash')
            .eq('client_id', clientId)
            .single();

        if (fetchError && fetchError.code !== 'PGRST116') throw fetchError;

        if (existing) {
            // If client exists but has no API key (pre-auth migration), generate one
            if (!existing.api_key_hash) {
                const apiKey = generateApiKey();
                const apiKeyHash = hashApiKey(apiKey);

                await supabase
                    .from('extension_clients')
                    .update({ last_active: new Date().toISOString(), api_key_hash: apiKeyHash })
                    .eq('client_id', clientId);

                logger.info({ clientId }, 'Generated API key for pre-existing client');

                return res.json({
                    success: true,
                    message: 'Client updated with API key',
                    apiKey,
                    client: {
                        id: existing.client_id,
                        createdAt: existing.created_at,
                        lastActive: existing.last_active,
                        notificationEnabled: existing.notification_enabled || false,
                    }
                });
            }

            // Update last_active
            await supabase
                .from('extension_clients')
                .update({ last_active: new Date().toISOString() })
                .eq('client_id', clientId);

            return res.json({
                success: true,
                message: 'Client already registered',
                client: {
                    id: existing.client_id,
                    createdAt: existing.created_at,
                    lastActive: existing.last_active,
                    notificationEnabled: existing.notification_enabled || false,
                }
            });
        }

        // New client: generate API key
        const apiKey = generateApiKey();
        const apiKeyHash = hashApiKey(apiKey);
        const now = new Date().toISOString();

        const { error: insertError } = await supabase
            .from('extension_clients')
            .insert({
                client_id: clientId,
                client_type: resolvedType,
                client_name: resolvedName,
                notification_enabled: false,
                created_at: now,
                last_active: now,
                api_key_hash: apiKeyHash,
            });

        if (insertError) throw insertError;

        logger.info({ clientId, clientType: resolvedType }, 'New client registered');

        return res.status(201).json({
            success: true,
            message: 'Client created',
            apiKey,
            client: {
                id: clientId,
                createdAt: now,
                lastActive: now,
                notificationEnabled: false,
            }
        });

    } catch (error) {
        logger.error({ err: error.message, clientId: req.body?.clientId }, 'Client registration error');
        res.status(500).json({ error: 'Failed to register client' });
    }
});

// Keep legacy GET /client/:id for backward compatibility during migration
app.get('/client/:id', async (req, res) => {
    try {
        const { id } = req.params;
        const { clientType, clientName } = req.query;

        if (!id || !isValidClientId(id)) {
            return res.status(400).json({ error: 'Valid client ID is required' });
        }

        const { data: existing, error: fetchError } = await supabase
            .from('extension_clients')
            .select('*')
            .eq('client_id', id)
            .single();

        if (fetchError && fetchError.code !== 'PGRST116') throw fetchError;

        if (!existing) {
            const resolvedType = VALID_CLIENT_TYPES.includes(clientType) ? clientType : 'UNKNOWN';
            const resolvedName = typeof clientName === 'string' ? clientName.substring(0, 255) : 'UNKNOWN';
            const apiKey = generateApiKey();
            const apiKeyHash = hashApiKey(apiKey);
            const now = new Date().toISOString();

            const { error: insertError } = await supabase
                .from('extension_clients')
                .insert({
                    client_id: id,
                    client_type: resolvedType,
                    client_name: resolvedName,
                    notification_enabled: false,
                    created_at: now,
                    last_active: now,
                    api_key_hash: apiKeyHash,
                });

            if (insertError) throw insertError;

            logger.info({ clientId: id, clientType: resolvedType }, 'New client registered via legacy endpoint');

            return res.status(201).json({
                success: true,
                message: 'Client created',
                apiKey,
                client: {
                    id,
                    createdAt: now,
                    lastActive: now,
                    notificationEnabled: false,
                }
            });
        }

        // If client exists but has no API key (pre-auth migration), generate one
        if (!existing.api_key_hash) {
            const apiKey = generateApiKey();
            const apiKeyHash = hashApiKey(apiKey);

            await supabase
                .from('extension_clients')
                .update({ last_active: new Date().toISOString(), api_key_hash: apiKeyHash })
                .eq('client_id', id);

            logger.info({ clientId: id }, 'Generated API key for pre-existing client via legacy endpoint');

            return res.json({
                success: true,
                apiKey,
                client: {
                    id: existing.client_id,
                    createdAt: existing.created_at,
                    lastActive: existing.last_active,
                    notificationEnabled: existing.notification_enabled || false,
                }
            });
        }

        res.json({
            success: true,
            client: {
                id: existing.client_id,
                createdAt: existing.created_at,
                lastActive: existing.last_active,
                notificationEnabled: existing.notification_enabled || false,
            }
        });

    } catch (error) {
        logger.error({ err: error.message, clientId: req.params.id }, 'Client fetch error');
        res.status(500).json({ error: 'Failed to fetch client data' });
    }
});

// ==========================================
// AUTHENTICATED ROUTES (require API key)
// ==========================================

app.post('/get-category-mapping', authenticateClient, aiLimiter, async (req, res) => {
    const domains = req.body.domains || [];
    const packages = req.body.packages || [];

    // Input validation
    if (!Array.isArray(domains) || !Array.isArray(packages)) {
        return res.status(400).json({ error: 'domains and packages must be arrays' });
    }
    if (domains.length > 100 || packages.length > 100) {
        return res.status(400).json({ error: 'Maximum 100 items per array' });
    }
    const invalidDomain = domains.find(d => typeof d !== 'string' || d.length > 255);
    const invalidPackage = packages.find(p => typeof p !== 'string' || p.length > 255);
    if (invalidDomain || invalidPackage) {
        return res.status(400).json({ error: 'Each item must be a string under 255 characters' });
    }

    const response = {};

    // Process all items with concurrency limit (max 5 concurrent OpenAI calls)
    const limit = pLimit(5);
    const allItems = [
        ...domains.map(d => ({ input: d, isPackage: false })),
        ...packages.map(p => ({ input: p, isPackage: true }))
    ];

    const results = await Promise.allSettled(
        allItems.map(({ input, isPackage }) => limit(() => categorize(input, isPackage)))
    );

    allItems.forEach(({ input }, index) => {
        const result = results[index];
        response[input] = result.status === 'fulfilled' ? result.value : 'Other';
    });

    res.json(response);
});

app.post('/submit-category-summary', authenticateClient, async (req, res) => {
    const { timestamp, categorySummary, userId } = req.body;

    if (!timestamp || !categorySummary || !userId) {
        return res.status(400).json({ error: 'Missing required fields: timestamp, categorySummary, userId' });
    }

    // Validate userId matches authenticated client
    if (userId !== req.clientId) {
        return res.status(403).json({ error: 'userId does not match authenticated client' });
    }

    // Validate timestamp
    const parsedDate = new Date(timestamp);
    if (isNaN(parsedDate.getTime())) {
        return res.status(400).json({ error: 'Invalid timestamp format' });
    }

    // Validate categorySummary is an object with valid keys
    if (typeof categorySummary !== 'object' || Array.isArray(categorySummary)) {
        return res.status(400).json({ error: 'categorySummary must be an object' });
    }

    try {
        const day = parsedDate.toISOString().split('T')[0];

        const { error } = await supabase
            .from('daily_summaries')
            .upsert({
                user_id: userId,
                day,
                timestamp,
                summary: categorySummary
            }, { onConflict: 'user_id,day' });

        if (error) throw error;

        res.json({ status: 'success' });
    } catch (err) {
        logger.error({ err: err.message, userId }, 'Failed to submit category summary');
        res.status(500).json({ error: 'Failed to save summary' });
    }
});

app.get('/get-summary-history', authenticateClient, async (req, res) => {
    const { day, userId } = req.query;

    if (!userId || !/^\d{4}-\d{2}-\d{2}$/.test(day)) {
        return res.status(400).json({ error: 'Missing or invalid userId/day. Day must be in YYYY-MM-DD format.' });
    }

    // Users can query their own data or linked devices' data
    // For now, allow if authenticated (backend auth ensures legitimacy)

    try {
        const { data, error } = await supabase
            .from('daily_summaries')
            .select('timestamp, user_id, summary')
            .eq('user_id', userId)
            .eq('day', day)
            .single();

        if (error && error.code !== 'PGRST116') throw error;

        if (!data) {
            return res.json([]);
        }

        res.json([{
            timestamp: data.timestamp,
            userId: data.user_id,
            summary: data.summary
        }]);
    } catch (err) {
        logger.error({ err: err.message, userId, day }, 'Failed to fetch summary history');
        res.status(500).json({ error: 'Failed to retrieve summary data' });
    }
});

app.post('/track-usage', authenticateClient, async (req, res) => {
    const { userId, timestamp, usage } = req.body;

    if (!userId || !timestamp || !usage) {
        return res.status(400).json({ error: 'Missing required fields: userId, timestamp, usage' });
    }

    if (userId !== req.clientId) {
        return res.status(403).json({ error: 'userId does not match authenticated client' });
    }

    // Validate usage fields are numbers
    const llmCall = Number(usage.llmCall) || 0;
    const cost = Number(usage.cost) || 0;
    if (llmCall < 0 || cost < 0) {
        return res.status(400).json({ error: 'Usage values cannot be negative' });
    }

    const parsedTimestamp = new Date(timestamp);
    if (isNaN(parsedTimestamp.getTime())) {
        return res.status(400).json({ error: 'Invalid timestamp format' });
    }

    try {
        // Atomic upsert via Supabase RPC to avoid race conditions
        const { error } = await supabase.rpc('upsert_usage_log', {
            p_user_id: userId,
            p_calls: llmCall,
            p_cost: cost,
            p_last_active: parsedTimestamp.toISOString(),
        });

        if (error) throw error;

        res.json({ status: 'success', userId });
    } catch (error) {
        logger.error({ err: error.message, userId }, 'Failed to track usage');
        res.status(500).json({ error: 'Failed to track usage' });
    }
});

// Scoped usage endpoint: only returns the authenticated client's own data
app.get('/usage', authenticateClient, async (req, res) => {
    try {
        const { data, error } = await supabase
            .from('usage_logs')
            .select('*')
            .eq('user_id', req.clientId)
            .single();

        if (error && error.code !== 'PGRST116') throw error;

        if (!data) {
            return res.json({});
        }

        res.json({
            totalCalls: data.total_calls,
            totalCost: data.total_cost,
            lastActive: data.last_active,
            id: data.tracking_id
        });
    } catch (error) {
        logger.error({ err: error.message, clientId: req.clientId }, 'Failed to fetch usage');
        res.status(500).json({ error: 'Failed to retrieve usage data' });
    }
});

app.post('/initiate-linking', authenticateClient, async (req, res) => {
    try {
        const { clientId } = req.body;

        if (!clientId || !isValidClientId(clientId)) {
            return res.status(400).json({ success: false, error: 'Valid clientId is required' });
        }

        if (clientId !== req.clientId) {
            return res.status(403).json({ success: false, error: 'clientId does not match authenticated client' });
        }

        const code = await initiateLinking(clientId);
        res.json({ success: true, code });
    } catch (error) {
        logger.error({ err: error.message }, 'Failed to initiate linking');
        res.status(500).json({ success: false, error: 'Failed to generate linking code' });
    }
});

app.post('/complete-linking', authenticateClient, linkingLimiter, async (req, res) => {
    try {
        const { clientId, mobileClientId, code } = req.body;
        const resolvedClientId = clientId || mobileClientId;

        if (!resolvedClientId || !isValidClientId(resolvedClientId)) {
            return res.status(400).json({ success: false, error: 'Valid clientId is required' });
        }

        // Validate code is exactly 6 numeric digits
        if (!code || !/^\d{6}$/.test(code)) {
            return res.status(400).json({ success: false, error: 'Code must be exactly 6 numeric digits' });
        }

        const { success, linkedWith } = await completeLinking(resolvedClientId, code);
        res.json({ success, linkedWith });
    } catch (error) {
        logger.error({ err: error.message }, 'Failed to complete linking');
        res.status(500).json({ success: false, error: 'Failed to complete device linking' });
    }
});

app.post('/unlink-device', authenticateClient, async (req, res) => {
    try {
        const { clientIdA, clientIdB } = req.body;

        if (!clientIdA || !clientIdB || !isValidClientId(clientIdA) || !isValidClientId(clientIdB)) {
            return res.status(400).json({ success: false, error: 'Both clientIdA and clientIdB are required' });
        }

        await unlinkClients(clientIdA, clientIdB);
        res.json({ success: true });
    } catch (error) {
        logger.error({ err: error.message }, 'Failed to unlink device');
        res.status(500).json({ success: false, error: 'Failed to unlink device' });
    }
});

app.get('/get-linked-clients', authenticateClient, async (req, res) => {
    try {
        const { clientId } = req.query;

        if (!clientId || !isValidClientId(clientId)) {
            return res.status(400).json({ success: false, error: 'Valid clientId is required' });
        }

        const linkedClients = await getLinkedClients(clientId);

        res.json({
            success: true,
            data: {
                count: linkedClients.length,
                clients: linkedClients
            }
        });
    } catch (error) {
        logger.error({ err: error.message, clientId: req.query.clientId }, 'Failed to fetch linked clients');
        res.status(500).json({ success: false, error: 'Failed to fetch linked clients' });
    }
});

// NOTE: Cleanup endpoints removed. Use Supabase pg_cron for scheduled cleanup:
//   SELECT cron.schedule('cleanup-expired-codes', '*/15 * * * *', 'SELECT cleanup_expired_linking_codes()');
//   SELECT cron.schedule('cleanup-inactive-links', '0 3 * * *', 'SELECT cleanup_inactive_links()');

app.listen(port, () => {
    logger.info({ port }, 'Server listening');
});
