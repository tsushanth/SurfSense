# Deployed state

Newest entries first. Backend deploys come from a clean worktree of origin/main.

## Android (Play, package `com.kreativekoala.surfsense`)

| Version | Code | Source | Track | Rollback |
|---|---|---|---|---|
| 10.2.2 | 17 | main `9620b2a`, tag `play/10.2.2-17` (Traditional Chinese zh-TW/zh-HK) | production, inProgress at 20% (2026-11-23); 10.2.1/16 remains completed | halt the staged rollout in Play Console (users stay on 16); do not promote past 20% until reviewed |
| 10.2.1 | 16 | main `bc2c300`, tag `play/10.2.1-16` | production, completed at 100% (2026-11-23) | previous release |

## Backend (Fly app `usage-tracker-backend`)

| Release | Date (PT) | Source | Image | Rollback |
|---|---|---|---|---|
| v8 | 2026-11-26 | origin/main `98fcdbd` (tree `cb7f676f`, PR #22: rate limits counted per caller) | `registry.fly.io/usage-tracker-backend:deployment-01M8CSB9RMSH9DTAJ0T6KEK9SX` | `flyctl releases rollback 7 -a usage-tracker-backend` |
| v7 | 2026-11-25 | origin/main `40e4eec` (tree `3702a033`, PR #20: `ANDROID_PHONE` stored as `ANDROID`) | `registry.fly.io/usage-tracker-backend:deployment-01M8A0MMYSFE0CTPCBMMGZY0KZ` | `flyctl releases rollback 6 -a usage-tracker-backend` |
| v6 | 2026-11-23 | origin/main `f9da028` (tree `359787f5`, PR #13: stable `code` on every 4xx, `error` text unchanged) | `registry.fly.io/usage-tracker-backend:deployment-01M852RPQ8QJNXCQBAK10HJEXC` | `flyctl releases rollback 5 -a usage-tracker-backend` (v5 image `deployment-01M6BTT1ATG50YJA0VA8JEJNHX`) |

Verified after v8: two callers have separate `ratelimit-remaining` counters, a forged `Fly-Client-IP` or `X-Forwarded-For` does not start a new one, and device linking passed in both directions (published extension 4.0.0 in headless Chromium, Android request shapes over HTTP): code shown, code entered, wrong code refused, each side reads the other's day, unlink, read refused afterwards. Test devices deleted. No Android build was run.

Verified after deploy: `/health` returns ok, a 4xx returns both `error` and `code`, rate-limit headers unchanged.

Verified after v7: the versionCode 8 request sequence (register, category mapping, summary submit and read back, linked clients, linking code) passed through `legacy-proxy` run locally against production; the test device it created was deleted.

## Legacy address proxy (Cloud Run, `legacy-proxy/`)

Not deployed (2026-11-25). Android versionCode 7 and 8 still get 404 from the old address. Deploy command, cost and limits: `legacy-proxy/README.md`.

