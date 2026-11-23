# Deployed state

Newest entries first. Backend deploys come from a clean worktree of origin/main.

## Android (Play, package `com.kreativekoala.surfsense`)

| Version | Code | Source | Track | Rollback |
|---|---|---|---|---|
| 10.2.1 | 16 | main `bc2c300`, tag `play/10.2.1-16` | production, inProgress at 20% (2026-11-23); 10.2.0/15 remains completed | halt the staged rollout in Play Console (users stay on 15); do not promote past 20% until reviewed |

## Backend (Fly app `usage-tracker-backend`)

| Release | Date (PT) | Source | Image | Rollback |
|---|---|---|---|---|
| v6 | 2026-11-23 | origin/main `f9da028` (tree `359787f5`, PR #13: stable `code` on every 4xx, `error` text unchanged) | `registry.fly.io/usage-tracker-backend:deployment-01M852RPQ8QJNXCQBAK10HJEXC` | `flyctl releases rollback 5 -a usage-tracker-backend` (v5 image `deployment-01M6BTT1ATG50YJA0VA8JEJNHX`) |

Verified after deploy: `/health` returns ok, a 4xx returns both `error` and `code`, rate-limit headers unchanged.
