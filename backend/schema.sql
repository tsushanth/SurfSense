-- Supabase PostgreSQL Schema for SurfSense
-- Run this in Supabase SQL Editor

-- 1. Extension Clients (devices)
CREATE TABLE IF NOT EXISTS extension_clients (
    client_id TEXT PRIMARY KEY,
    client_type TEXT DEFAULT 'UNKNOWN',
    client_name TEXT DEFAULT 'UNKNOWN',
    notification_enabled BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    last_active TIMESTAMPTZ DEFAULT NOW(),
    api_key_hash TEXT
);

-- Index for API key lookups (used by auth middleware)
CREATE INDEX IF NOT EXISTS idx_extension_clients_api_key ON extension_clients(api_key_hash);

-- 2. Client Links (device pairings)
CREATE TABLE IF NOT EXISTS client_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_a TEXT NOT NULL REFERENCES extension_clients(client_id) ON DELETE CASCADE,
    client_b TEXT NOT NULL REFERENCES extension_clients(client_id) ON DELETE CASCADE,
    linked_at TIMESTAMPTZ DEFAULT NOW(),
    last_activity_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(client_a, client_b)
);

-- Index for faster lookups
CREATE INDEX IF NOT EXISTS idx_client_links_a ON client_links(client_a);
CREATE INDEX IF NOT EXISTS idx_client_links_b ON client_links(client_b);

-- 3. Linking Requests (temporary codes)
CREATE TABLE IF NOT EXISTS linking_requests (
    client_id TEXT PRIMARY KEY,
    code TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);

-- Index for code lookups
CREATE INDEX IF NOT EXISTS idx_linking_requests_code ON linking_requests(code);

-- 4. Domain Categories (AI classification cache)
CREATE TABLE IF NOT EXISTS domain_categories (
    domain TEXT PRIMARY KEY,
    category TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 5. Daily Summaries (usage data)
CREATE TABLE IF NOT EXISTS daily_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES extension_clients(client_id) ON DELETE CASCADE,
    day DATE NOT NULL,
    timestamp TIMESTAMPTZ DEFAULT NOW(),
    summary JSONB NOT NULL DEFAULT '{}',
    UNIQUE(user_id, day)
);

-- Index for user lookups
CREATE INDEX IF NOT EXISTS idx_daily_summaries_user_day ON daily_summaries(user_id, day);

-- 6. Usage Logs (LLM API tracking)
CREATE TABLE IF NOT EXISTS usage_logs (
    user_id TEXT PRIMARY KEY REFERENCES extension_clients(client_id) ON DELETE CASCADE,
    total_calls INTEGER DEFAULT 0,
    total_cost DECIMAL(10, 4) DEFAULT 0,
    last_active TIMESTAMPTZ DEFAULT NOW(),
    tracking_id UUID DEFAULT gen_random_uuid()
);

-- Enable Row Level Security
ALTER TABLE daily_summaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_logs ENABLE ROW LEVEL SECURITY;

-- RLS Policies (service role bypasses these, but they protect against anon key misuse)
CREATE POLICY "Users can only access their own summaries" ON daily_summaries
    FOR ALL USING (user_id = current_setting('request.jwt.claim.sub', true));

CREATE POLICY "Users can only access their own usage" ON usage_logs
    FOR ALL USING (user_id = current_setting('request.jwt.claim.sub', true));

-- Cleanup function for expired linking codes
CREATE OR REPLACE FUNCTION cleanup_expired_linking_codes()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM linking_requests WHERE expires_at <= NOW();
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Cleanup function for inactive links (30 days)
CREATE OR REPLACE FUNCTION cleanup_inactive_links()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM client_links WHERE last_activity_at < NOW() - INTERVAL '30 days';
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Atomic upsert for usage tracking (avoids race conditions)
CREATE OR REPLACE FUNCTION upsert_usage_log(
    p_user_id TEXT,
    p_calls INTEGER,
    p_cost DECIMAL,
    p_last_active TIMESTAMPTZ
)
RETURNS VOID AS $$
BEGIN
    INSERT INTO usage_logs (user_id, total_calls, total_cost, last_active, tracking_id)
    VALUES (p_user_id, p_calls, p_cost, p_last_active, gen_random_uuid())
    ON CONFLICT (user_id) DO UPDATE SET
        total_calls = usage_logs.total_calls + EXCLUDED.total_calls,
        total_cost = usage_logs.total_cost + EXCLUDED.total_cost,
        last_active = EXCLUDED.last_active;
END;
$$ LANGUAGE plpgsql;

-- Schedule cleanup via pg_cron (enable the extension first: CREATE EXTENSION IF NOT EXISTS pg_cron;)
-- SELECT cron.schedule('cleanup-expired-codes', '*/15 * * * *', 'SELECT cleanup_expired_linking_codes()');
-- SELECT cron.schedule('cleanup-inactive-links', '0 3 * * *', 'SELECT cleanup_inactive_links()');

-- Migration: If upgrading from previous schema, run these ALTER statements:
-- ALTER TABLE extension_clients ADD COLUMN IF NOT EXISTS api_key_hash TEXT;
-- CREATE INDEX IF NOT EXISTS idx_extension_clients_api_key ON extension_clients(api_key_hash);
-- ALTER TABLE domain_categories ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT NOW();
-- ALTER TABLE client_links ADD CONSTRAINT fk_client_links_a FOREIGN KEY (client_a) REFERENCES extension_clients(client_id) ON DELETE CASCADE;
-- ALTER TABLE client_links ADD CONSTRAINT fk_client_links_b FOREIGN KEY (client_b) REFERENCES extension_clients(client_id) ON DELETE CASCADE;
-- ALTER TABLE daily_summaries ADD CONSTRAINT fk_daily_summaries_user FOREIGN KEY (user_id) REFERENCES extension_clients(client_id) ON DELETE CASCADE;
-- ALTER TABLE usage_logs ADD CONSTRAINT fk_usage_logs_user FOREIGN KEY (user_id) REFERENCES extension_clients(client_id) ON DELETE CASCADE;
