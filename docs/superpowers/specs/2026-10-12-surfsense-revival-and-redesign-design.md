# SurfSense: backend revival, fixes, redesign, release

Date: 2026-10-12. Status: approved with edits 2026-10-12; implementation plan next.

## Intent

SurfSense shows how a person spends time across their phone and laptop in one view, with no account. Devices pair with a 6-digit code. iOS (Screen Time / DeviceActivity), Android (usage stats) and a Chrome extension each categorize usage and submit daily summaries to a shared backend.

Success: a new user can install on two devices, link them with a code, and see a combined and per-device day and week. The Play Store (Android) and the Chrome Web Store (extension) ship a version that works. iOS does not ship in this release.

## Current state (verified 2026-10-12)

- The backend (`backend/`, Express + Supabase + OpenAI fallback categorizer) ran on GCP Cloud Run. It died when GCP billing closed. All shipped clients fail.
- The working tree points all three clients at `https://usage-tracker-backend.fly.dev`. That host does not resolve: no Fly app of that name exists.
- The old Supabase project referenced in `usage-tracker-backend/.fly-secrets.sh` (`qhuyipmjmllsouudiwos`) no longer resolves, so it is deleted. No data is recoverable. Decision (user, 2026-10-12): start fresh, users re-register.
- `~/Documents/GitHub/usage-tracker-backend` is an older Firebase-era variant. Only its `fly.toml` is useful. `SurfSense/backend` is the code to deploy.
- Versions: iOS `MARKETING_VERSION = 99.0` (placeholder from a rejected INVALID_BINARY), Android `9.1` (code 9), extension `3.0`.
- Uncommitted work exists in the tree. The Android and extension URL changes are committed as part of this work, after review. The iOS changes (URL migration, `ReviewManager`, app icon swap, `ExportOptions.plist`) stay uncommitted and untouched.

## Scope

In: backend revival; client bug fixes found by testing real flows; feature additions (below); frontend redesign across the extension popup and Android; release of Android and the extension.

Out: iOS release (decision 2026-10-12: ship backend, Android and extension only; iOS working-tree changes stay uncommitted, API stays unchanged so a later iOS release only flips the URL; shipped iOS builds stay broken until then because the old Cloud Run host cannot be redirected), accounts or sign-in, hard app blocking, Family Controls shielding, new platforms, monetization changes.

## Part 1: Backend revival (blocks everything)

1. Database (decision 2026-10-12, supersedes the shared-schema idea): a dedicated Supabase project `SurfSense` in its own Free-plan org (org `cpjqdstaljfkqeggtupk`, project ref `rxwtuegccdhygbxvwzvo`, East US N. Virginia, created by the user 2026-10-13). Costs $0 and isolates SurfSense completely, so the backend keeps the existing Supabase client and the project-wide service key only reaches SurfSense's own tables (sharing AIRadio's project was rejected: it holds `oauth_tokens` and `users`). Apply `backend/schema.sql` (the Supabase tool is bound to the Pro org and cannot see this one, so use the CLI or the dashboard SQL editor). Confirm RLS policies and cleanup functions. Free-plan caveats: inactive projects auto-pause and there are no automated backups, so add a scheduled ping to `/health` (which touches the DB) to avoid pausing, and treat data as disposable (summaries only).
2. New Fly app `usage-tracker-backend` (the name the clients already use), region `iad`, from `backend/` with a `fly.toml` based on the sibling's. Use `auto_stop_machines = "stop"` and `min_machines_running = 0` so it costs near nothing at low traffic; the trade-off is a cold start of a few seconds on the first request after idle.
3. The Dockerfile is `node:18-alpine` (Node 18 is end-of-life): move to a current Node LTS while verifying `npm test` still passes. Secrets set on Fly: `SUPABASE_URL` and `SUPABASE_SERVICE_KEY` only (no LLM key needed). Never committed; values confirmed with the user before setting, since this is a new app and no existing secret is overwritten.
4. Run `npm test` in `backend/`. Add a smoke script that exercises register, link, complete-link, submit, history, unlink against the live URL.
5. Verify `/health` from outside.

## Part 2: Client fixes

Run the real flows on an Android device or emulator and in Chrome: register, show code, link, submit summary, view history, unlink, offline and cold-start behavior. Fix what fails. Known suspects: hard-coded URLs in three places (centralize per platform), error states when the backend is slow to wake, linking code expiry display.

## Part 3: Features (from market research)

Competitors are mostly blockers (Opal, One Sec, ScreenZen, Freedom). Only RescueTime and StayFree combine tracking with cross-device views, and RescueTime paywalls history and does not split by device. Our angle is no-account cross-device tracking. Planned, in priority order:

1. Combined and per-device toggle with devices as stacked, color-coded segments (small build on existing data).
2. Cross-device category rollups with one-tap override of a site or app category.
3. Week vs last week trend, with unlimited free history.
4. Local export of data (no account means no recovery; export is the backup story).
5. Deferred: soft friction on a category. Needs Family Controls entitlement on iOS; not in this release.

Unverified and to check before promising: iOS per-app time depends on what DeviceActivity reports; the extension only sees websites. Copy must not claim more than each platform can measure.

## Part 4: Redesign (draft plan, to be reviewed against the brief before any code)

Subject: a calm, descriptive instrument for people who want to see their day, not be scolded. The category skews dark, gamified and guilt-framed (Opal, Forest). We go the other way.

- Palette: Tide paper `#F3F6F5`, Deep ink `#12232B`, Sea glass `#4FA89A`, Buoy orange `#E8743B` (used only for the single "most time" call-out), Slate `#5B6C73`, plus one fixed hue per category reused identically on every platform. Dark mode derived, not an afterthought.
- Type: one family with real optical range, a humanist grotesque for UI plus tabular figures for durations; decide the exact face during the design pass (not a default system or Inter).
- Layout concept: the day is one horizontal "tide line" bar split by device and category, the memorable element. Everything else is quiet lists and numbers. Left-aligned. No identical card grid.
- Principles: the stacked device bar is the one bold move; neutral, factual copy ("4h 12m on your phone today"); no streaks, scores or guilt language; visible focus, reduced motion respected, readable at phone width.
- Review step: before code, I compare this plan to what I would produce for any screen-time app and revise any default-looking part, saying what changed.

## Part 5: Release

- Versions: Android 10.0.0 (code 10), extension 4.0.0. Confirm against Play and the Chrome Web Store before bumping.
- Pre-push secrets scan; push after each merge.
- Android: signing credentials come from `~/.gradle/gradle.properties` via `providers.gradleProperty(...)` (see the shared CLAUDE.md); never write keystore passwords or files into the repo, and verify `android/app/build.gradle.kts` follows that before building. AAB via Gradle, upload to Play (staged rollout). Minify stays off.
- Extension: zip and submit to the Chrome Web Store.

## Categorizer (decision 2026-10-12: approach A, B as measured follow-up)

No LLM and no API key. Tiers: Supabase cache, then patterns and static map, then a pre-labeled list of the top ~20k domains (labels produced once in chat and spot-checked, shipped as static JSON; check the source ranking list's license first), then for Android packages the category on the public Play Store page (fetch only `play.google.com` with the package name regex-validated, so no SSRF surface), else "Other". Users can override any category in one tap. Log how many lookups end as "Other" and what users override.

Tier B (small local zero-shot embedding model) spike result, 2026-10-12: CONDITIONAL, leaning NO-GO, so it is not in this release. Model `Xenova/gte-small` q8 via `@huggingface/transformers`: 83% accuracy on its non-Other predictions overall, but on inputs the regexes miss it labeled only 41% of domains and 36% of packages (bar was 50%), at 84% and 76% accuracy. Operationally fine (about 141MB RSS growth in a 512MB container, under 1s cold start, 5-10ms per prediction) but heavy: about 480MB of node_modules, and `onnxruntime-node` fails on Alpine (needs glibc, Node 20+; Fly x64 not verified). Its labels were the spike author's own judgment. It becomes worth revisiting only with a real signal added (page title, Play Store metadata) and only if the logged Other rate justifies it. The Play Store category lookup for packages covers the same gap with better signal. Option C (fetching arbitrary page titles) stays rejected: SSRF attack surface.

Lookup order in code: domains resolve in memory (static map, then the pre-labeled list, then regexes, else Other) and never touch the DB; Android packages resolve static map, regexes, then the DB cache, then the Play Store category (only definitive answers cached), else Other. The pre-labeled list (`backend/data/domain-categories.json`, 12.5k domains) is checked BEFORE the regexes because patterns like `news|daily|post|times` mislabel many domains. Lookup strips a leading `www.` and also tries the parent registrable domain.

OpenAI is removed from the backend (dependency, env var, README).

## Decisions

- Fly: scale-to-zero, `min_machines_running = 0`.
- After deploy, update the Fly guide mapping for `usage-tracker-backend` to point at `SurfSense/backend`.

## Testing

Backend unit tests plus the live smoke script against the deployed URL. No simulators: real Android phone (the shared Pixel; check with the other Claude sessions before using it) with the release build, real Chrome with the packed extension, and a real end-to-end link between the phone and the browser, including a first request after the Fly machine has scaled to zero. UI verified with screenshots from the real device at phone width, keyboard open, error states visible.
