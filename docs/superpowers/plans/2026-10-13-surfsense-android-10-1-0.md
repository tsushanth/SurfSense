# SurfSense Android 10.1.0 (redesign + features) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Ship Android 10.1.0 (versionCode 12): a calm "tide line" redesign of the app, a combined/per-device view, a week-vs-last-week trend, one-tap app category override, and local data export.

**Architecture:** Pure logic (day math, aggregation, week comparison, overrides, CSV) lives in small unit-tested Kotlin files under `usage/` and `insights/`. Compose UI is rebuilt on a new theme (`ui/theme`) with a few shared components (`ui/components`). The server API is unchanged: only category totals per device per day exist server-side, so per-app data and overrides are local to this phone.

**Tech Stack:** Kotlin, Jetpack Compose Material3 (BOM 2024.04.01, minSdk 24), WorkManager, Retrofit, JUnit4.

**Spec:** `docs/superpowers/specs/2026-10-12-surfsense-revival-and-redesign-design.md` (Parts 3 and 4; Part 5 for release).

## Design plan (reviewed against the generic default)

**Subject:** people who want to *see* their day across phone and laptop, not be scolded. Category skews dark, gamified, guilt-framed. We are descriptive and quiet.

**Color (named, fixed, light and dark derived):**
- Tide paper `#F3F6F5` (light bg) / Deep ink `#12232B` (text light, bg dark `#0E1A20`)
- Slate `#5B6C73` (secondary text), Sea glass `#2F7F73` (primary, darkened from the spec's `#4FA89A` so text/controls pass contrast on paper; `#4FA89A` used as dark-mode primary)
- Category hues, one per category, identical on every platform and both themes (see `CategoryPalette`): Social `#3E7CB1`, Entertainment `#8E5BB5`, Work/Productivity `#2F7F73`, News `#C98A2B`, Education `#2A9DB0`, Finance `#5A8F3C`, Health `#C2577F`, Shopping `#B8A12D`, Travel `#5B5FC7`, Food `#C8553D`, Other `#8A979C`.

**Review of the draft (what changed and why):**
1. The spec's "Buoy orange single call-out" is dropped. Orange-on-cool-paper with a dark mode reads as the default terracotta/vermilion accent. The call-out is now structural: in the tide line the largest category is drawn at full saturation and the rest at 55% opacity.
2. "Stacked, color-coded device segments" would collide with category hue. Resolved: hue always means category. Devices are separate rows of the same tide line on one shared scale, identified by name and icon, not color.
3. Dynamic color (Material You) is turned off so the fixed category hues keep their meaning.
4. No identical card grid: sections are separated by space and a single hairline, not by repeated rounded cards. Left-aligned throughout. The default "big number, small label, gradient" hero is replaced by the sentence "6h 12m on your 3 devices today" set in one line with tabular figures.

**Type:** one family, Hanken Grotesk (OFL, variable; humanist grotesque with real weight range and tabular figures via `tnum`), bundled in `res/font`. Durations always `tnum`. Scale: 34/40 Semibold (today total), 20/26 Semibold (section), 16/24 Regular (body), 14/20 Medium (rows), 12/16 Medium (captions). Sentence case everywhere, no all-caps labels.

**Layout concept:** the day is one horizontal tide line, a bar split by category, with one thin bar per device beneath it on the same scale. Everything else is quiet lists and numbers.

```
Today
6h 12m on your 3 devices          [All | Phone | Laptop | Pixel]
▓▓▓▓▓▓▓▓▓▒▒▒▒▒▒▒▒░░░░░░          <- combined tide line
 Phone   ▓▓▓▓▓▓▒▒▒░░  3h 40m
 Laptop  ▓▓▓▒▒░       2h 05m
 Pixel   ▓░           27m
─────────────────────────────
 Social           2h 10m  35%
 Work/Productivity 1h 48m 29%
 ...
Apps on this phone  (tap to change its category)
```

**Principles:** hue = category everywhere; neutral factual copy ("4h 12m on your phone today"), no streaks, scores, or guilt words; visible focus, 48dp touch targets, reduced motion respected (no entrance animation; the only motion is the tide line segments animating width on first draw, skipped when animations are off); readable at phone width and with 200% font scale.

## Global Constraints

- Package `com.kreativekoala.surfsense`, minSdk 24, compileSdk 36. Minify stays off. Backup stays disabled.
- Version: `versionCode = 12`, `versionName = "10.1.0"`.
- No server/API changes. No new permissions. No analytics/ads SDKs.
- Every new user-visible string is added to `values/strings.xml` AND all 19 existing locale folders (lint `MissingTranslation` is an error). Run `./gradlew --no-daemon lintVitalRelease` or `bundleRelease` to prove it.
- Neutral copy rules: sentence case, active voice, no streaks/scores/guilt.
- Signing credentials never written into the repo. Never print secrets.
- Build env: `GRADLE_USER_HOME=$HOME/.gradle-surfsense ANDROID_HOME=$HOME/Library/Android/sdk LC_ALL=en_US.UTF-8`, run from `android/`.
- Hue always means category; device identity never uses a hue.

## Review Focus

- A device with zero seconds today, or all days empty: no divide-by-zero, no empty bar rendered as full width.
- Unknown category names from the server (anything outside the palette) get the "Other" hue and still render.
- This week vs last week when last week has no data: show this week only, no "+∞%" or NaN.
- A "week" is the 7 local days ending today versus the 7 days before that (rolling), so it is never a partial calendar week.
- Override for a package that is no longer installed or has no usage today: kept, harmless, removable.
- Export with zero data produces a header-only CSV, not a crash; category names containing commas/quotes are escaped.
- Font scale 200% and 320dp width: no clipped durations.

## File structure

- Create `android/app/src/main/java/com/kreativekoala/surfsense/insights/TideModel.kt` (pure: scale + segment fractions)
- Create `.../insights/WeekCompare.kt` (pure: rolling 7 vs previous 7)
- Create `.../insights/CsvExport.kt` (pure)
- Create `.../usage/CategoryOverrides.kt` (storage-backed map + pure apply)
- Modify `.../usage/UsageSyncer.kt` (apply overrides)
- Modify `.../usage/UsageCollector.kt` (expose per-app seconds for UI)
- Create `.../history/HistoryRepository.kt` (per-device per-day fetch + past-day cache)
- Create `.../ui/theme/{Color,Type,Theme}.kt` (rewrite), `.../ui/components/{TideLine,DurationText,CategoryRow,DeviceRow}.kt`
- Rewrite `.../ui/SurfSenseApp.kt` into `ui/screens/{TodayScreen,WeekScreen,DevicesScreen,SettingsScreen}.kt` + `ui/SurfSenseApp.kt` (nav only)
- Modify `.../viewmodel/MainViewModel.kt`
- Strings in 20 `values*/strings.xml`
- Tests under `android/app/src/test/java/com/kreativekoala/surfsense/{insights,usage,history}/`

---

### Task 1: Pure logic: tide model, week compare, CSV, overrides

**Files:** Create `insights/TideModel.kt`, `insights/WeekCompare.kt`, `insights/CsvExport.kt`, `usage/CategoryOverrides.kt`; modify `usage/UsageSyncer.kt`; tests `insights/TideModelTest.kt`, `insights/WeekCompareTest.kt`, `insights/CsvExportTest.kt`, `usage/CategoryOverridesTest.kt`, extend `usage/UsageSyncerTest.kt`.

**Interfaces (produce exactly these):**
```kotlin
// insights/TideModel.kt
data class TideSegment(val category: String, val seconds: Int, val fraction: Float) // fraction of the scale, 0..1
data class TideRow(val label: String, val totalSeconds: Int, val segments: List<TideSegment>)
object TideModel {
    /** Segments sorted by seconds desc. Scale: fraction = seconds / scaleSeconds (clamped 0..1). scaleSeconds<=0 => all fractions 0. */
    fun row(label: String, byCategory: Map<String, Int>, scaleSeconds: Int): TideRow
    /** Rows for devices all on one shared scale = the largest device total. Combined row uses scale = its own total. */
    fun rows(devices: List<Pair<String, Map<String, Int>>>): List<TideRow>
    /** Category name -> canonical palette key. Unknown/blank => "Other". "Social"->"Social Media", "Productivity"->"Work/Productivity", case-insensitive. */
    fun canonicalCategory(raw: String): String
}
// insights/WeekCompare.kt
data class WeekComparison(val thisWeekSeconds: Int, val lastWeekSeconds: Int?, val deltaPercent: Int?, val perDay: List<Pair<String, Int>>)
object WeekCompare {
    /** days: map yyyy-MM-dd -> total seconds (all devices). today: yyyy-MM-dd. thisWeek = today and 6 earlier days; lastWeek = the 7 before that.
     *  lastWeekSeconds null when no day of last week has data; deltaPercent null when lastWeek null or 0; else round((this-last)*100/last). perDay = 7 entries oldest->today, missing days = 0. */
    fun compare(days: Map<String, Int>, today: String): WeekComparison
}
// insights/CsvExport.kt
object CsvExport {
    /** Header: date,device,category,seconds. One row per (date, device, category). RFC4180 quoting for commas, quotes, newlines. Rows sorted by date, device, category. Ends with "\n". */
    fun build(rows: List<ExportRow>): String
}
data class ExportRow(val date: String, val device: String, val category: String, val seconds: Int)
// usage/CategoryOverrides.kt
class CategoryOverrides(private val store: Store) {
    interface Store { fun load(): Map<String, String>; fun save(map: Map<String, String>) }
    fun all(): Map<String, String>
    fun set(pkg: String, category: String)        // blank category => remove
    fun remove(pkg: String)
    fun apply(mapping: Map<String, String>, packages: Collection<String>): Map<String, String> // overrides win; packages absent from mapping and overrides are omitted
}
```
`CategoryOverrides` also needs an Android `SharedPrefsStore(context)` implementation (JSON via Gson, prefs name `surfsense_category_overrides`) kept out of the pure unit tests.

- [ ] Write failing tests first for every behaviour above, including: zero totals, unknown category, last week empty, delta rounding (e.g. this 3600 last 2400 => +50), negative delta, CSV quoting (`"a,b"`, `he said "x"`), empty rows => header only, overrides win over mapping, blank category removes.
- [ ] Implement. `UsageSyncer` takes a new constructor parameter `applyOverrides: (Map<String,String>, Collection<String>) -> Map<String,String> = { m, _ -> m }` and applies it to the server mapping before totalling (default keeps the existing tests valid). Add a UsageSyncer test: package X mapped "Social Media" by server, override "News" => summary counts under "News".
- [ ] Run `./gradlew --no-daemon testDebugUnitTest`; all pass.
- [ ] Commit.

### Task 2: History repository (per-device, per-day, past days cached)

**Files:** Create `history/HistoryRepository.kt`, test `history/HistoryRepositoryTest.kt`.

**Interfaces:**
```kotlin
data class DeviceDay(val deviceId: String, val deviceName: String, val date: String, val byCategory: Map<String, Int>)
class HistoryRepository(
    private val fetchDay: suspend (deviceId: String, day: String) -> Result<Map<String, Int>?>, // null = no data that day
    private val cache: PastDayCache,
    private val today: () -> String,
) {
    interface PastDayCache { fun get(deviceId: String, day: String): Map<String, Int>?; fun put(deviceId: String, day: String, byCategory: Map<String, Int>) }
    /** Last [days] days ending today, for each device. Days before yesterday are served from cache when present and cached after a successful non-empty fetch;
     *  today and yesterday are always re-fetched (a device may sync late). At most 4 fetches in flight. A failed fetch yields no entry for that day and does not fail the rest.
     *  Returns (rows, failedCount). */
    suspend fun load(devices: List<Pair<String, String>>, days: Int): Pair<List<DeviceDay>, Int>
}
fun dayMinus(day: String, n: Int): String   // pure date math in a top-level file, tested incl. month/year boundaries and leap day
```
Wire a SharedPreferences `PastDayCache` (prefs `surfsense_past_days`, key `deviceId|day`, trimmed to 40 days) and an adapter calling `UsageRepository.getSummaryHistory(deviceId, day)` that maps the first entry's `summary` (empty list => `Result.success(null)`).

- [ ] Tests: cache hit avoids fetch for day older than yesterday; today/yesterday always fetched; one failing day increments failedCount and others still return; empty (null) results are not cached; concurrency cap <= 4 (use a fake counting max in-flight with `delay`); `dayMinus` boundaries.
- [ ] Implement, run tests, commit.

### Task 3: Theme, font, shared components

**Files:** Rewrite `ui/theme/Color.kt`, `Type.kt`, `Theme.kt`; Create `res/font/hanken_grotesk.ttf` (variable, OFL; download from `https://github.com/google/fonts/raw/main/ofl/hankengrotesk/HankenGrotesk%5Bwght%5D.ttf`, add `OFL.txt` notice to `app/src/main/assets/licenses/` if not present and mention in About), `ui/components/{CategoryPalette,TideLine,DurationText,CategoryRow,DeviceRow}.kt`; a Compose-free unit test for `CategoryPalette.colorFor` fallback.

**Requirements:**
- `SurfSenseTheme(darkTheme, content)` with NO dynamic color; light and dark `ColorScheme` built from the Design plan tokens; status/nav bar follow.
- `Font(R.font.hanken_grotesk, FontWeight.X, variationSettings = FontVariation.Settings(FontVariation.weight(X)))` for weights 400/500/600 (API 26+; below 26 falls back to the file default weight — acceptable, document it). `fontFeatureSettings = "tnum"` on duration styles.
- `CategoryPalette.colorFor(category: String): Color` uses `TideModel.canonicalCategory`; unknown => Other.
- `TideLine(segments: List<TideSegment>, modifier, height = 28.dp, emphasizeLargest = true)`: draws rounded-corner (4dp) bar on a `surfaceVariant` track; segments laid left to right by fraction; largest segment full alpha, others 0.55 alpha; 1dp gap between segments; width animates in once using `animateFloatAsState` unless `LocalReduceMotion`/system animator scale is 0 (read `Settings.Global.ANIMATOR_DURATION_SCALE`); content description = "Social Media 2h 10m, Work/Productivity 1h 48m, ..." sentence for TalkBack.
- `DurationText(seconds)`: uses `MainViewModel.formatSeconds` semantics but never shows "0m" for 1..59s ("<1m"); tabular figures.
- `CategoryRow(category, seconds, shareOfTotal)`: hue dot 10dp, name, right-aligned duration, share `35%`; min height 48dp.
- `DeviceRow(name, type, TideRow, selected, onClick)`: icon by type (existing mapping), name, thin (12dp) `TideLine`, duration; selected state = 2dp left bar in primary; min height 56dp; `Modifier.selectable`.
- [ ] Implement; add `@Preview`s; build debug; commit.

### Task 4: Today and Week screens (+ view model data)

**Files:** Create `ui/screens/TodayScreen.kt`, `ui/screens/WeekScreen.kt`; modify `viewmodel/MainViewModel.kt` (add `scope: DeviceScope` state, `todayRows`, `appsToday`, `week: WeekComparison?`, `weekLoading`, `failedDays`); modify `usage/UsageCollector.kt` only if needed to expose per-package seconds (it already returns them: add a `fun todayApps(): List<AppUsage>` that joins package -> label via PackageManager, skipping failures).

**Behaviour:**
- Today: header sentence from the Design plan using plurals/strings ("%1$s on your %2$d devices today" / "%1$s on your phone today" when only this device); combined `TideLine`; chips "All devices" + one per device (`FilterChip`, single select); `DeviceRow` list (tapping a row selects that device scope); category list for the selected scope; when scope is this device and Usage Access granted: "Apps on this phone" list (top 15 by time) with the app's current category; tapping an app opens a bottom sheet `ModalBottomSheet` listing the 11 categories (+ "Reset to automatic" when overridden); choosing one calls `CategoryOverrides.set`, closes the sheet, and enqueues one `UsageSummaryWorker` sync so the totals update. Empty state: when no data at all, one sentence ("Nothing recorded yet today. Allow Usage Access and sync, or link another device.") plus a "Sync now" button. Error text uses `colorScheme.error` and says what to do.
- Week: segmented control "7 days / 14 days" is dropped; it is always this week vs last week. Shows total this week as the headline, delta line ("12% more than last week" / "8% less than last week" / hidden when null), 7 day columns (stacked by category, same hue rules, Monday..today order is rolling oldest->today, labels are single localized weekday letters via `DateTimeFormatter` with `Locale`... use `SimpleDateFormat("EEEEE")` for minSdk 24), and a "Last week" faint outline total for each day if last week data exists (second ghost column behind). Reads from `HistoryRepository.load(devices, 14)`; shows a small "Couldn't load N days. Pull to retry." line when failedCount>0. Loading state is a skeleton line, not a spinner.
- [ ] View-model logic that can be pure goes into Task 1 files; add tests for any new pure helper.
- [ ] Manual layout check at 320dp and 200% font scale via `@Preview(fontScale = 2f, widthDp = 320)`.
- [ ] Commit.

### Task 5: Devices, Settings, export, nav shell, strings

**Files:** Create `ui/screens/DevicesScreen.kt` (existing link/share/unlink flows moved over unchanged in behaviour, restyled with `DeviceRow`s and plain buttons), `ui/screens/SettingsScreen.kt` (existing rows restyled + new "Export my data" and "Category overrides" list with remove), rewrite `ui/SurfSenseApp.kt` as 4 tabs: Today, Week, Devices, Settings (`NavigationBar`, labels sentence case, selected tab restored across rotation with `rememberSaveable`); delete the old composables and `TrendsActivity`/`activity_trends.xml` ONLY IF nothing references them (grep first; `LocalStorage` usage-history API may stay but unused code must not break compile). Remove `Config`/`SurfSenseApp` dead code.
- Export: builds CSV from `HistoryRepository.load(devices, 30)` via `CsvExport.build`, writes with the Storage Access Framework `ActivityResultContracts.CreateDocument("text/csv")` (file name `surfsense-YYYY-MM-DD.csv`). No storage permission. Success/failure toast.
- Strings: add all new strings to `values/strings.xml` and the 19 locale files (ar, de, es, fr, hi, in, it, ja, ko, ms, nl, pl, pt, ru, th?, tr?, vi?, zh... use exactly the existing folders: list with `ls android/app/src/main/res | grep values-`). Translations are written by the implementer in each language, short and neutral; mark nothing as final; the changelog notes machine-written translations need native review.
- [ ] `./gradlew --no-daemon testDebugUnitTest bundleRelease` passes (this proves MissingTranslation and vital lint).
- [ ] Commit.

### Task 6: Release prep

**Files:** `android/app/build.gradle.kts` (12 / 10.1.0), `fastlane/metadata/android/<locale>/changelogs/12.txt` for the 10 fastlane locales (<=500 chars, plain, e.g. "A new look: your day as one line, split by category. See each device on its own, compare this week with last week, change an app's category with a tap, and export your data."), `Config.kt` unchanged.
- [ ] Clean `bundleRelease`; confirm SHA-256 `ED:D1:37:83...`; install on the Pixel via the lock-directory protocol and screenshot Today (keyboard closed), Week, Devices, Settings, the override sheet, dark mode, and font scale 1.5 as the user sees them; fix defects.
- [ ] `fastlane validate` equivalent, publish at 100% production, verify track = [12], merge+push to main (secrets scan).
