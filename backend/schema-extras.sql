-- Run AFTER schema.sql.

-- 1. Lock the remaining tables to the service role (no policies = anon/authenticated get nothing).
ALTER TABLE extension_clients ENABLE ROW LEVEL SECURITY;
ALTER TABLE client_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE linking_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE domain_categories ENABLE ROW LEVEL SECURITY;

-- 2. Scheduled cleanup. If CREATE EXTENSION errors, enable pg_cron in
--    Dashboard > Database > Extensions, then re-run from the SELECT lines.
CREATE EXTENSION IF NOT EXISTS pg_cron;
SELECT cron.schedule('cleanup-expired-codes', '*/15 * * * *', 'SELECT cleanup_expired_linking_codes()');
SELECT cron.schedule('cleanup-inactive-links', '0 3 * * *', 'SELECT cleanup_inactive_links()');
