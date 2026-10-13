# SurfSense Backend

Express.js API server for the SurfSense cross-device usage tracking ecosystem. Handles device registration, linking, usage categorization (local lists and patterns, plus a Play Store lookup for Android packages), and summary storage.

## Setup

```bash
cp .env.example .env
# Fill in your Supabase credentials
npm install
npm start
```

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `SUPABASE_URL` | Yes | Your Supabase project URL |
| `SUPABASE_SERVICE_KEY` | Yes | Supabase service role key (not the anon key) |
| `PORT` | No | Server port (default: 8080) |
| `PLAY_LOOKUP_ENABLED` | No | Default on; set `false` to disable the Play Store lookup (packages categorize as Other, uncached) |
| `CORS_ORIGINS` | No | Comma-separated allowed origins (empty = allow non-browser) |

## Database

Run `schema.sql` in the Supabase SQL Editor to create all tables, indexes, RLS policies, and cleanup functions.

## API Endpoints

### Public (no auth)
- `GET /health` — Health check
- `POST /register-client` — Register a device, returns API key on first call

### Authenticated (Bearer token)
- `POST /get-category-mapping` — Categorize domains/packages
- `POST /submit-category-summary` — Submit daily usage summary
- `GET /get-summary-history` — Fetch usage history for a device
- `POST /track-usage` — Track LLM API usage
- `GET /usage` — Get own usage data
- `POST /initiate-linking` — Generate a 6-digit linking code
- `POST /complete-linking` — Link two devices using a code
- `POST /unlink-device` — Remove a device link
- `GET /get-linked-clients` — List linked devices

## Testing

```bash
npm test          # Run tests once
npm run test:watch  # Watch mode
```

## Deployment

Runs on Fly.io as `usage-tracker-backend` (see `fly.toml`). Secrets (`SUPABASE_URL`, `SUPABASE_SERVICE_KEY`) live only on Fly.

```bash
flyctl secrets list -a usage-tracker-backend
flyctl deploy
SMOKE_BASE_URL=https://usage-tracker-backend.fly.dev node scripts/smoke.mjs
```
