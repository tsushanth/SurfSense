# SurfSense Android Reliability Release (10.0.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an Android build that reliably registers, syncs correct daily totals, links to other devices and recovers from backend/auth problems, against the revived backend, with a clean privacy/permissions surface.

**Architecture:** Replace `ANDROID_ID` with a random per-install identity (id + API key kept in encrypted prefs) owned by `DeviceIdentity`/`DeviceRegistrar`, with an OkHttp `Authenticator` that re-registers on 401. Replace the blanket retry interceptor with a policy object. Replace the rolling-24h `queryUsageStats` call with an event-based, calendar-day foreground-time calculator, chunked category lookups and a testable `UsageSyncer`. All new logic is plain Kotlin behind small interfaces so it is JVM-unit-testable; Android glue stays thin. The redesign and new features are a separate plan (10.1.0).

**Tech Stack:** Kotlin 2.0, Android Gradle Plugin 8.9.2, Gradle 8.11.1, minSdk 24 / target+compile 36, WorkManager 2.10, Retrofit 2.9 / OkHttp 4.12 / Gson, JUnit 4 + MockWebServer (new), fastlane `supply`.

**Spec:** `docs/superpowers/specs/2026-10-12-surfsense-revival-and-redesign-design.md` (Parts 2 and 5, Android). Backend contract: `docs/superpowers/plans/2026-10-12-surfsense-backend-revival.md` (done, live at https://usage-tracker-backend.fly.dev).

## Global Constraints

- Android: `minSdk = 24`, `targetSdk = compileSdk = 36`, `isMinifyEnabled = false` (never enable minify), no `READ_MEDIA_*` permissions, application id `com.kreativekoala.surfsense`.
- Backend base URL `https://usage-tracker-backend.fly.dev/`. `Config.CLIENT_TYPE` must be exactly `ANDROID` (the backend accepts only `CHROME_EXTENSION`, `IOS`, `ANDROID`, `UNKNOWN`; anything else is stored as `UNKNOWN`).
- Category names, exact spelling: `Social Media`, `Entertainment`, `Work/Productivity`, `Shopping`, `Education`, `News`, `Finance`, `Health`, `Travel`, `Food`, `Other`. Unmapped packages become `Other` (never `Uncategorized`).
- `/get-category-mapping` accepts at most 100 items per array: the app must send packages in batches of at most 100.
- A day is the device's local calendar date (`yyyy-MM-dd`). Usage for "today" runs from local midnight to now (not a rolling 24 h).
- Never write a keystore password, key password or keystore file into the repo. Signing credentials come only from `~/.gradle/gradle.properties` keys `ANDROID_UPLOAD_STORE_FILE`, `ANDROID_UPLOAD_STORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`, `ANDROID_UPLOAD_KEY_PASSWORD`, read with `providers.gradleProperty(...)`. The pre-push hook blocks literal passwords; never bypass it.
- Gradle must run with `GRADLE_USER_HOME=$HOME/.gradle-surfsense` (the shared `~/.gradle` cache is corrupted by other sessions) and `LC_ALL=en_US.UTF-8`. `~/.gradle-surfsense/gradle.properties` is a copy of `~/.gradle/gradle.properties` (mode 600) so the signing properties resolve.
- The Pixel (`41111FDAP0005H`) is shared with other Claude sessions and the owner: ask the other sessions via SendMessage before any `adb` use, check the foreground app with `dumpsys` first, never press Back blind, no uninstalls except the `.verify` build id, no `adb kill-server`. If it is locked and needs the fingerprint, stop and ask.
- Do not log API keys or full client ids with Timber. Never commit `google-services.json`, `local.properties` or `.env`.
- Publishing to Google Play happens only on the user's explicit approval, as a staged rollout. A completed 100% release cannot be halted, only superseded by a higher versionCode.
- Stage files by explicit path only (the main checkout has unrelated uncommitted iOS/extension changes). Commit messages end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Work in a fresh worktree: `git worktree add -b android-10 ../SurfSense-android main` from `~/Documents/GitHub/SurfSense`. Run Gradle from `<worktree>/android`.

## Review Focus

Failure modes the spec implies but no task would otherwise exercise; each is pinned by a test in the named task.

1. **First launch offline / backend asleep**: registration fails, no crash, the install id is stable, and the next worker run or launch registers. Task 2.
2. **A phone with 150+ apps with foreground time**: category lookups go out in batches of at most 100, never one oversized request that returns 400 and retries forever. Task 4.
3. **Midnight rollover, time zones and DST**: "today" is local midnight to now, the reported date is the local date, a session spanning midnight is clamped. Task 4.
4. **Server forgets the device (401)**: after the DB wipe every existing install is unknown to the backend; a 401 must trigger one re-registration and one retry, not an endless failure. Task 2.
5. **Wrong linking code**: the user sees "Invalid or expired code", the request is not retried (retrying burns the 5-per-15-minutes limit), and the app does not show "500". Tasks 1, 3, 5.
6. **Usage access not granted**: the worker does nothing and succeeds; the user first sees a plain-language disclosure before being sent to Settings. Tasks 4, 6.
7. **Backup/restore**: a restored backup must not carry an old identity or an undecryptable key. Task 6.

---

### Task 1: Backend: linking-code errors return 400 with a message

**Files:**
- Modify: `backend/linkingFunctions.js`
- Modify: `backend/server.js` (`/complete-linking` catch block, import)
- Modify: `backend/__tests__/linkingFunctions.test.js`
- Modify: `backend/scripts/smoke.mjs`

**Interfaces:**
- Produces: `export class LinkingError extends Error` with `name === 'LinkingError'` and numeric `status`. `completeLinking` throws it for unknown code, expired code and self-link. `/complete-linking` responds `400 { success:false, error:<message> }` for those; everything else stays `500`.
- Why: today a wrong code returns `500 "Failed to complete device linking"`; the old Android retry interceptor re-sent it three more times per attempt, burning the 5-per-15-minutes limit.

- [ ] **Step 1: Write failing tests**

In `backend/__tests__/linkingFunctions.test.js`, change the import line to:

```js
const { generateLinkingCode, getLinkedClients, completeLinking, LinkingError } = await import('../linkingFunctions.js');
```

Replace the whole `describe('completeLinking', ...)` block with:

```js
describe('completeLinking', () => {
    it('rejects an unknown code with a 400 LinkingError', async () => {
        h.state.results.linking_requests = { error: { message: 'no rows' } };
        const err = await completeLinking('b-device', '123456').catch(e => e);
        expect(err).toBeInstanceOf(LinkingError);
        expect(err).toMatchObject({ name: 'LinkingError', status: 400, message: 'Invalid or expired code' });
    });

    it('rejects an expired code with a 400 LinkingError and deletes the request', async () => {
        h.state.results.linking_requests = {
            data: { client_id: 'a-device', expires_at: new Date(Date.now() - 1000).toISOString() },
        };
        const err = await completeLinking('b-device', '123456').catch(e => e);
        expect(err).toBeInstanceOf(LinkingError);
        expect(err).toMatchObject({ status: 400, message: 'Code expired' });
        expect(h.state.calls.some(c => c.table === 'linking_requests' && c.m === 'delete')).toBe(true);
    });

    it('rejects self-linking with a 400 LinkingError and does not insert a link', async () => {
        h.state.results.linking_requests = {
            data: { client_id: 'same-device', expires_at: new Date(Date.now() + 60000).toISOString() },
        };
        const err = await completeLinking('same-device', '123456').catch(e => e);
        expect(err).toBeInstanceOf(LinkingError);
        expect(err).toMatchObject({ status: 400, message: 'Cannot link a device to itself' });
        expect(h.state.calls.some(c => c.table === 'client_links')).toBe(false);
        expect(h.state.calls.some(c => c.table === 'linking_requests' && c.m === 'delete')).toBe(false);
    });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && npm test -- linkingFunctions`
Expected: FAIL (`LinkingError` is undefined / `toBeInstanceOf` fails).

- [ ] **Step 3: Implement**

In `backend/linkingFunctions.js`, add above `generateLinkingCode`:

```js
/** Expected, user-correctable linking failures. The route maps these to HTTP 400. */
class LinkingError extends Error {
    constructor(message, status = 400) {
        super(message);
        this.name = 'LinkingError';
        this.status = status;
    }
}
```

Replace the three throws in `completeLinking`:
- `throw new Error('Invalid or expired code');` → `throw new LinkingError('Invalid or expired code');`
- `throw new Error('Code expired');` → `throw new LinkingError('Code expired');`
- `throw new Error('Cannot link a device to itself');` → `throw new LinkingError('Cannot link a device to itself');`

Add `LinkingError,` to the `export { ... }` list at the bottom.

In `backend/server.js`: add `LinkingError,` to the import list from `./linkingFunctions.js`. In the `/complete-linking` catch block, replace

```js
        if (error.message === 'Cannot link a device to itself') {
            return res.status(400).json({ success: false, error: 'Cannot link a device to itself' });
        }
```

with

```js
        if (error instanceof LinkingError) {
            return res.status(error.status).json({ success: false, error: error.message });
        }
```

- [ ] **Step 4: Run to verify pass**

Run: `npm test && node --check server.js`
Expected: all PASS, syntax clean.

- [ ] **Step 5: Extend the smoke test**

In `backend/scripts/smoke.mjs`, immediately after the `stolen` check (the "C cannot complete linking as B (403)" check), add:

```js
const badCode = await call('POST', '/complete-linking', { key: keys.c, body: { clientId: ids.c, code: '000000' } });
check('wrong linking code -> 400 with message (not 500)', badCode.status === 400 && badCode.json?.error === 'Invalid or expired code', JSON.stringify(badCode));
```

(`000000` can never be issued: codes are 100000 to 999999.) The full smoke now makes 3 `/complete-linking` calls per run; the limiter allows 5 per 15 minutes per IP, so run the full smoke at most once per 15 minutes.

Run: `node --check scripts/smoke.mjs`

- [ ] **Step 6: Commit, then deploy and smoke (live, from a clean worktree)**

```bash
cd ~/Documents/GitHub/SurfSense-android
git add backend/linkingFunctions.js backend/server.js backend/__tests__/linkingFunctions.test.js backend/scripts/smoke.mjs
git commit -m "backend: linking-code errors return 400 with a message" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
cd backend && git status --short -- . | wc -l      # must print 0
flyctl deploy -a usage-tracker-backend --remote-only
node scripts/smoke.mjs
```
Expected: deploy succeeds; smoke ends `ALL CHECKS PASSED` including the new check. (Do not deploy over uncommitted backend changes.)

---

### Task 2: Per-install identity, registration and 401 recovery

**Files:**
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/identity/SecretStore.kt`
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/identity/DeviceIdentity.kt`
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/identity/DeviceRegistrar.kt`
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/identity/Identity.kt`
- Create: `android/app/src/test/java/com/kreativekoala/surfsense/identity/FakeSecretStore.kt`
- Create: `android/app/src/test/java/com/kreativekoala/surfsense/identity/DeviceIdentityTest.kt`
- Create: `android/app/src/test/java/com/kreativekoala/surfsense/identity/DeviceRegistrarTest.kt`
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/ApiClient.kt`
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/Config.kt`
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/SurfSenseApp.kt` (the `Application` class)
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/MainActivity.kt`
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/viewmodel/MainViewModel.kt`

**Interfaces:**
- Produces:
  - `interface SecretStore { fun getString(key: String): String?; fun putString(key: String, value: String); fun remove(key: String) }`
  - `class DeviceIdentity(store: SecretStore, newId: () -> String = { UUID.randomUUID().toString() })` with `val clientId: String`, `var apiKey: String?`, `fun reset()`.
  - `class DeviceRegistrar(identity: DeviceIdentity, deviceName: () -> String, register: suspend (RegisterClientRequest) -> Response<RegisterClientResponse>)` with `suspend fun ensureRegistered(): Boolean` and `suspend fun onUnauthorized(): Boolean`.
  - `object Identity { fun init(context: Context); fun identity(): DeviceIdentity; fun registrar(): DeviceRegistrar; fun deviceName(context: Context): String }`.
- Behavior: `ensureRegistered()` returns true when an API key is stored (no network); otherwise registers `clientId` with `clientType = "ANDROID"`; if the server answers 200 without an `apiKey` (it already knows this id but we have no key: unrecoverable) the identity is reset and registration is retried once with a fresh id; offline/5xx returns false and leaves the id stable. `onUnauthorized()` forgets the key and re-registers (same id when the server does not know it).

- [ ] **Step 1: Create the worktree and make the build run**

```bash
cd ~/Documents/GitHub/SurfSense
git worktree add -b android-10 ../SurfSense-android main
cd ../SurfSense-android/android
export GRADLE_USER_HOME=$HOME/.gradle-surfsense LC_ALL=en_US.UTF-8
ls "$GRADLE_USER_HOME/gradle.properties" && ./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` for the existing (placeholder) unit test. If this fails, STOP and report the exact error: nothing else in this plan can be verified without a working build.

Add the test dependency. In `android/gradle/libs.versions.toml` add under `[libraries]`:
`okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }`
In `android/app/build.gradle.kts`, in `dependencies`, after `testImplementation(libs.junit)` add `testImplementation(libs.okhttp.mockwebserver)`.

- [ ] **Step 2: Write the failing tests**

`FakeSecretStore.kt`:

```kotlin
package com.kreativekoala.surfsense.identity

class FakeSecretStore : SecretStore {
    val data = HashMap<String, String>()
    override fun getString(key: String): String? = data[key]
    override fun putString(key: String, value: String) { data[key] = value }
    override fun remove(key: String) { data.remove(key) }
}
```

`DeviceIdentityTest.kt`:

```kotlin
package com.kreativekoala.surfsense.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceIdentityTest {
    private fun identity(store: SecretStore = FakeSecretStore()): DeviceIdentity {
        var n = 0
        return DeviceIdentity(store, newId = { "install-${++n}-aaaa" })
    }

    @Test fun clientIdIsCreatedOnceAndStable() {
        val store = FakeSecretStore()
        val id = identity(store)
        val first = id.clientId
        assertEquals(first, id.clientId)
        assertEquals(first, identity(store).clientId) // survives a new instance over the same store
    }

    @Test fun apiKeyRoundTripsAndNullRemoves() {
        val id = identity()
        assertNull(id.apiKey)
        id.apiKey = "k1"
        assertEquals("k1", id.apiKey)
        id.apiKey = null
        assertNull(id.apiKey)
    }

    @Test fun resetForgetsIdAndKey() {
        val id = identity()
        val first = id.clientId
        id.apiKey = "k1"
        id.reset()
        assertNull(id.apiKey)
        assertNotEquals(first, id.clientId)
    }

    @Test fun generatedIdIsAcceptableToTheBackend() {
        val real = DeviceIdentity(FakeSecretStore())
        assertEquals(true, real.clientId.length in 8..128)
    }
}
```

`DeviceRegistrarTest.kt`:

```kotlin
package com.kreativekoala.surfsense.identity

import com.kreativekoala.surfsense.RegisterClientRequest
import com.kreativekoala.surfsense.RegisterClientResponse
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class DeviceRegistrarTest {
    private class Fixture(vararg replies: () -> Response<RegisterClientResponse>) {
        val store = FakeSecretStore()
        var ids = 0
        val identity = DeviceIdentity(store, newId = { "install-${++ids}-bbbb" })
        val requests = ArrayList<RegisterClientRequest>()
        private val queue = ArrayDeque(replies.toList())
        val registrar = DeviceRegistrar(identity, { "Test Phone" }) { req ->
            requests.add(req)
            queue.removeFirst().invoke()
        }
    }

    private fun ok(key: String?) = { Response.success(RegisterClientResponse(true, key, null)) }
    private fun http(code: Int) = { Response.error<RegisterClientResponse>(code, "".toResponseBody(null)) }
    private fun offline(): () -> Response<RegisterClientResponse> = { throw IOException("offline") }

    @Test fun alreadyHavingAKeyMakesNoNetworkCall() = runBlocking {
        val f = Fixture()
        f.identity.apiKey = "have"
        assertTrue(f.registrar.ensureRegistered())
        assertEquals(0, f.requests.size)
    }

    @Test fun firstRegistrationStoresTheKeyAndSendsAndroidType() = runBlocking {
        val f = Fixture(ok("key-1"))
        assertTrue(f.registrar.ensureRegistered())
        assertEquals("key-1", f.identity.apiKey)
        assertEquals("ANDROID", f.requests.single().clientType)
        assertEquals("Test Phone", f.requests.single().clientName)
        assertEquals(f.identity.clientId, f.requests.single().clientId)
    }

    @Test fun offlineFailsWithoutLosingTheIdAndTheNextCallRetries() = runBlocking {
        val f = Fixture(offline(), ok("key-2"))
        assertFalse(f.registrar.ensureRegistered())
        val idAfterFailure = f.identity.clientId
        assertNull(f.identity.apiKey)
        assertTrue(f.registrar.ensureRegistered())
        assertEquals(idAfterFailure, f.requests[1].clientId)
        assertEquals("key-2", f.identity.apiKey)
    }

    @Test fun serverErrorReturnsFalseAndKeepsTheId() = runBlocking {
        val f = Fixture(http(500))
        assertFalse(f.registrar.ensureRegistered())
        assertEquals(1, f.requests.size)
        assertNull(f.identity.apiKey)
    }

    @Test fun okWithoutAKeyMeansTheIdIsBurnedSoItStartsOverWithANewId() = runBlocking {
        val f = Fixture(ok(null), ok("key-3"))
        assertTrue(f.registrar.ensureRegistered())
        assertEquals(2, f.requests.size)
        assertNotEquals(f.requests[0].clientId, f.requests[1].clientId)
        assertEquals("key-3", f.identity.apiKey)
    }

    @Test fun okWithoutAKeyTwiceGivesUp() = runBlocking {
        val f = Fixture(ok(null), ok(null))
        assertFalse(f.registrar.ensureRegistered())
        assertEquals(2, f.requests.size)
    }

    @Test fun unauthorizedForgetsTheKeyAndReRegistersWithTheSameId() = runBlocking {
        val f = Fixture(ok("key-new"))
        f.identity.apiKey = "stale"
        val id = f.identity.clientId
        assertTrue(f.registrar.onUnauthorized())
        assertEquals(id, f.requests.single().clientId)
        assertEquals("key-new", f.identity.apiKey)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.kreativekoala.surfsense.identity.*' --console=plain 2>&1 | tail -15`
Expected: FAIL (compilation: unresolved `SecretStore`, `DeviceIdentity`, `DeviceRegistrar`).

- [ ] **Step 4: Implement the identity classes**

`SecretStore.kt`:

```kotlin
package com.kreativekoala.surfsense.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import timber.log.Timber

/** Minimal key-value store so identity logic can be unit-tested off-device. */
interface SecretStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** Encrypted SharedPreferences, rebuilt if the keystore master key is lost, plain prefs as a last resort. */
class AndroidSecretStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences = open(context.applicationContext)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }

    private companion object {
        const val ENCRYPTED_NAME = "surfsense_identity"
        const val PLAIN_NAME = "surfsense_identity_plain"

        fun open(context: Context): SharedPreferences = try {
            encrypted(context)
        } catch (e: Exception) {
            Timber.e(e, "Encrypted prefs unavailable; recreating")
            try {
                context.deleteSharedPreferences(ENCRYPTED_NAME)
                encrypted(context)
            } catch (e2: Exception) {
                Timber.e(e2, "Encrypted prefs still unavailable; using app-private plain prefs")
                context.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)
            }
        }

        fun encrypted(context: Context): SharedPreferences {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            return EncryptedSharedPreferences.create(
                ENCRYPTED_NAME,
                alias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
```

`DeviceIdentity.kt`:

```kotlin
package com.kreativekoala.surfsense.identity

import java.util.UUID

/**
 * A random per-install identity. Unlike ANDROID_ID it changes on reinstall, so a reinstalled app
 * can always register (the backend never re-issues an API key for an id it already knows).
 */
class DeviceIdentity(
    private val store: SecretStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val clientId: String
        @Synchronized get() = store.getString(KEY_ID) ?: newId().also { store.putString(KEY_ID, it) }

    var apiKey: String?
        @Synchronized get() = store.getString(KEY_API_KEY)
        @Synchronized set(value) {
            if (value == null) store.remove(KEY_API_KEY) else store.putString(KEY_API_KEY, value)
        }

    /** Forget the id and the key; the next [clientId] read creates a fresh identity. */
    @Synchronized
    fun reset() {
        store.remove(KEY_ID)
        store.remove(KEY_API_KEY)
    }

    private companion object {
        const val KEY_ID = "client_id"
        const val KEY_API_KEY = "api_key"
    }
}
```

`DeviceRegistrar.kt`:

```kotlin
package com.kreativekoala.surfsense.identity

import com.kreativekoala.surfsense.Config
import com.kreativekoala.surfsense.RegisterClientRequest
import com.kreativekoala.surfsense.RegisterClientResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException

class DeviceRegistrar(
    private val identity: DeviceIdentity,
    private val deviceName: () -> String,
    private val register: suspend (RegisterClientRequest) -> Response<RegisterClientResponse>,
) {
    private val mutex = Mutex()

    /** True when this install holds an API key (registers first if it does not). */
    suspend fun ensureRegistered(): Boolean = mutex.withLock { registerLocked() }

    /** The server rejected our key (HTTP 401): forget it and register again. */
    suspend fun onUnauthorized(): Boolean = mutex.withLock {
        identity.apiKey = null
        registerLocked()
    }

    private suspend fun registerLocked(): Boolean {
        if (identity.apiKey != null) return true
        repeat(2) {
            val response = try {
                register(RegisterClientRequest(identity.clientId, Config.CLIENT_TYPE, deviceName()))
            } catch (e: IOException) {
                return false
            }
            if (!response.isSuccessful) return false
            val key = response.body()?.apiKey
            if (key != null) {
                identity.apiKey = key
                return true
            }
            // 200 without a key: the server already knows this id and will never issue another key.
            // This id can never authenticate; start over with a fresh identity.
            identity.reset()
        }
        return false
    }
}
```

`Identity.kt`:

```kotlin
package com.kreativekoala.surfsense.identity

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Process-wide wiring for the install identity. Call [init] from Application.onCreate. */
object Identity {
    @Volatile private var identity: DeviceIdentity? = null
    @Volatile private var registrar: DeviceRegistrar? = null

    fun init(context: Context) {
        synchronized(this) {
            if (identity != null) return
            val app = context.applicationContext
            val id = DeviceIdentity(AndroidSecretStore(app))
            identity = id
            registrar = DeviceRegistrar(id, { deviceName(app) }) { request ->
                com.kreativekoala.surfsense.ApiClient.create().registerClient(request)
            }
        }
    }

    fun identity(): DeviceIdentity = checkNotNull(identity) { "Identity.init() was not called" }
    fun registrar(): DeviceRegistrar = checkNotNull(registrar) { "Identity.init() was not called" }

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
}
```

- [ ] **Step 5: Wire it in (ApiClient, Config, Application, Activity, ViewModel)**

`Config.kt`: change `const val CLIENT_TYPE = "ANDROID_PHONE"` to `const val CLIENT_TYPE = "ANDROID"`. Confirm `API_BASE_URL` is `"https://usage-tracker-backend.fly.dev/"`.

`SurfSenseApp.kt` (the `Application` subclass at `com.kreativekoala.surfsense`): add `com.kreativekoala.surfsense.identity.Identity.init(this)` as the last line of `onCreate()`.

`ApiClient.kt`: remove the `apiKey` field, `setApiKey`, `initialize`, `saveApiKey` and `getSecurePrefs` (and the now-unused imports of `SharedPreferences`, `EncryptedSharedPreferences`, `MasterKeys`, `Context`). Keep `create()`. In `buildApiService()`, replace the auth interceptor and add an authenticator:

```kotlin
        // Auth interceptor: attach the current API key (read per request, so a fresh key is picked up)
        clientBuilder.addInterceptor { chain ->
            val builder = chain.request().newBuilder()
            Identity.identity().apiKey?.let { builder.header("Authorization", "Bearer $it") }
            chain.proceed(builder.build())
        }

        // 401 = the server does not recognise our key (e.g. its database was reset): register again once.
        clientBuilder.authenticator(Authenticator { _, response ->
            val request = response.request
            if (request.url.encodedPath.endsWith("/register-client") || request.header(RETRIED_AUTH_HEADER) != null) {
                return@Authenticator null
            }
            val recovered = runBlocking { Identity.registrar().onUnauthorized() }
            if (!recovered) return@Authenticator null
            request.newBuilder()
                .header("Authorization", "Bearer ${Identity.identity().apiKey}")
                .header(RETRIED_AUTH_HEADER, "1")
                .build()
        })
```

Add imports `okhttp3.Authenticator`, `kotlinx.coroutines.runBlocking`, `com.kreativekoala.surfsense.identity.Identity`, and inside the object `private const val RETRIED_AUTH_HEADER = "X-SurfSense-Auth-Retried"`. Leave the existing retry interceptor in place (Task 3 replaces it).

`MainActivity.kt`: delete the `ApiClient.initialize(this)` line (the Application does it now).

`MainViewModel.kt`:
- In `initialize()`, replace the `deviceId`/`deviceName` computation with `val deviceId = Identity.identity().clientId` and `val deviceName = Identity.deviceName(context)`; remove the `Settings` and `Build` imports if unused.
- Replace `registerDevice()` body with `Identity.registrar().ensureRegistered()` (ignore the boolean here; errors surface through the data refresh in Task 5). Remove `RegisterClientRequest` and `ApiClient.saveApiKey` usage and unused imports.

- [ ] **Step 6: Run tests and compile**

Run: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --console=plain 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`; the identity tests pass (11 tests). Confirm no remaining references: `grep -rn "ANDROID_ID\|saveApiKey\|setApiKey\|ApiClient.initialize" app/src/main/java` must print only `UsageSummaryWorker.kt` (fixed in Task 4).

- [ ] **Step 7: Commit**

```bash
cd ~/Documents/GitHub/SurfSense-android
git add android/app/src/main/java/com/kreativekoala/surfsense/identity android/app/src/test/java/com/kreativekoala/surfsense/identity android/app/src/main/java/com/kreativekoala/surfsense/ApiClient.kt android/app/src/main/java/com/kreativekoala/surfsense/Config.kt android/app/src/main/java/com/kreativekoala/surfsense/SurfSenseApp.kt android/app/src/main/java/com/kreativekoala/surfsense/MainActivity.kt android/app/src/main/java/com/kreativekoala/surfsense/viewmodel/MainViewModel.kt android/app/build.gradle.kts android/gradle/libs.versions.toml
git commit -m "android: random per-install identity, registration, re-register on 401" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Retry policy that does not hammer the server

**Files:**
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/net/RetryInterceptor.kt`
- Create: `android/app/src/test/java/com/kreativekoala/surfsense/net/RetryPolicyTest.kt`
- Create: `android/app/src/test/java/com/kreativekoala/surfsense/net/RetryInterceptorTest.kt`
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/ApiClient.kt`

**Interfaces:**
- Produces: `object RetryPolicy { const val MAX_ATTEMPTS = 3; fun shouldRetry(method: String, path: String, status: Int?, ioError: Boolean, attempt: Int): Boolean; fun delayMs(attempt: Int): Long }` and `class RetryInterceptor(sleep: (Long) -> Unit = { Thread.sleep(it) }) : okhttp3.Interceptor`.
- Rules: never more than 3 attempts in total; never retry `/complete-linking`; retry any request on a connection failure (`IOException`); retry only `GET` on HTTP 5xx; never retry 4xx (including 401, 403 and 429). Delays are 500 ms then 1000 ms.

- [ ] **Step 1: Write the failing tests**

`RetryPolicyTest.kt`:

```kotlin
package com.kreativekoala.surfsense.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test fun getRetriesOn5xxButStopsAtThreeAttempts() {
        assertTrue(RetryPolicy.shouldRetry("GET", "/x", 503, false, 1))
        assertTrue(RetryPolicy.shouldRetry("GET", "/x", 500, false, 2))
        assertFalse(RetryPolicy.shouldRetry("GET", "/x", 503, false, 3))
    }

    @Test fun postDoesNotRetryOn5xx() {
        assertFalse(RetryPolicy.shouldRetry("POST", "/submit-category-summary", 500, false, 1))
    }

    @Test fun neverRetries4xxIncludingAuthAndRateLimit() {
        for (code in listOf(400, 401, 403, 404, 429)) {
            assertFalse("GET $code", RetryPolicy.shouldRetry("GET", "/x", code, false, 1))
            assertFalse("POST $code", RetryPolicy.shouldRetry("POST", "/x", code, false, 1))
        }
    }

    @Test fun connectionFailuresRetryForAnyMethodExceptCompleteLinking() {
        assertTrue(RetryPolicy.shouldRetry("POST", "/get-category-mapping", null, true, 1))
        assertTrue(RetryPolicy.shouldRetry("GET", "/get-linked-clients", null, true, 2))
        assertFalse(RetryPolicy.shouldRetry("POST", "/complete-linking", null, true, 1))
        assertFalse(RetryPolicy.shouldRetry("POST", "/complete-linking", 503, false, 1))
    }

    @Test fun successIsNotRetried() {
        assertFalse(RetryPolicy.shouldRetry("GET", "/x", 200, false, 1))
    }

    @Test fun delaysDouble() {
        assertEquals(500L, RetryPolicy.delayMs(1))
        assertEquals(1000L, RetryPolicy.delayMs(2))
    }
}
```

`RetryInterceptorTest.kt`:

```kotlin
package com.kreativekoala.surfsense.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class RetryInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val sleeps = ArrayList<Long>()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder().addInterceptor(RetryInterceptor(sleep = { sleeps.add(it) })).build()
    }

    @After fun tearDown() { server.shutdown() }

    private fun get(path: String) = client.newCall(Request.Builder().url(server.url(path)).build()).execute()
    private fun post(path: String) = client.newCall(
        Request.Builder().url(server.url(path)).post("{}".toRequestBody(null)).build()
    ).execute()

    @Test fun getRetriesTransient5xxThenSucceeds() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(200, get("/health").code)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(500L, 1000L), sleeps)
    }

    @Test fun getGivesUpAfterThreeAttemptsAndReturnsTheLast5xx() {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertEquals(503, get("/health").code)
        assertEquals(3, server.requestCount)
    }

    @Test fun post5xxIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(500, post("/submit-category-summary").code)
        assertEquals(1, server.requestCount)
    }

    @Test fun clientErrorsAreNotRetried() {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(429, get("/x").code)
        assertEquals(1, server.requestCount)
    }

    @Test fun completeLinkingIsNeverRetriedEvenOnConnectionFailure() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200))
        var failed = false
        try { post("/complete-linking") } catch (e: IOException) { failed = true }
        assertTrue(failed)
        assertEquals(1, server.requestCount)
    }

    @Test fun otherPostsRetryAConnectionFailure() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(200, post("/get-category-mapping").code)
        assertEquals(2, server.requestCount)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.kreativekoala.surfsense.net.*' --console=plain 2>&1 | tail -12`
Expected: FAIL (unresolved `RetryPolicy`, `RetryInterceptor`).

- [ ] **Step 3: Implement `RetryInterceptor.kt`**

```kotlin
package com.kreativekoala.surfsense.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

object RetryPolicy {
    const val MAX_ATTEMPTS = 3

    /** [attempt] is the 1-based number of the attempt that just finished. */
    fun shouldRetry(method: String, path: String, status: Int?, ioError: Boolean, attempt: Int): Boolean {
        if (attempt >= MAX_ATTEMPTS) return false
        // A wrong code is a user error and the backend rate-limits this route (5 per 15 minutes).
        if (path.endsWith("/complete-linking")) return false
        if (ioError) return true
        if (status == null) return false
        return method.equals("GET", ignoreCase = true) && status in 500..599
    }

    fun delayMs(attempt: Int): Long = 500L shl (attempt - 1)
}

class RetryInterceptor(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 1
        while (true) {
            var failure: IOException? = null
            val response: Response? = try {
                chain.proceed(request)
            } catch (e: IOException) {
                failure = e
                null
            }
            val retry = RetryPolicy.shouldRetry(
                request.method, request.url.encodedPath, response?.code, response == null, attempt
            )
            if (!retry) return response ?: throw failure!!
            response?.close()
            sleep(RetryPolicy.delayMs(attempt))
            attempt++
        }
    }
}
```

In `ApiClient.kt`, delete the existing `// Retry interceptor` block (the `addInterceptor { chain -> var response ... while (!response.isSuccessful ...` lambda) and replace it with `clientBuilder.addInterceptor(RetryInterceptor())`, importing `com.kreativekoala.surfsense.net.RetryInterceptor`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -12`
Expected: `BUILD SUCCESSFUL`, all tests pass (identity + net).

- [ ] **Step 5: Commit**

```bash
cd ~/Documents/GitHub/SurfSense-android
git add android/app/src/main/java/com/kreativekoala/surfsense/net android/app/src/test/java/com/kreativekoala/surfsense/net android/app/src/main/java/com/kreativekoala/surfsense/ApiClient.kt
git commit -m "android: retry only connection failures and idempotent 5xx, never complete-linking" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Correct usage collection and a testable sync

**Files:**
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/usage/ForegroundTime.kt`
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/usage/Day.kt`
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/usage/CategoryMapper.kt`
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/usage/UsageSyncer.kt`
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/usage/UsageCollector.kt`
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/usage/UsageAccess.kt`
- Create: `android/app/src/test/java/com/kreativekoala/surfsense/usage/ForegroundTimeTest.kt`
- Create: `android/app/src/test/java/com/kreativekoala/surfsense/usage/DayTest.kt`
- Create: `android/app/src/test/java/com/kreativekoala/surfsense/usage/CategoryMapperTest.kt`
- Create: `android/app/src/test/java/com/kreativekoala/surfsense/usage/UsageSyncerTest.kt`
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/UsageSummaryWorker.kt`
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/repository/UsageRepository.kt` (remove `getUsageStats` if unreferenced)
- Modify: `android/app/src/main/AndroidManifest.xml` (`<queries>` for the HOME intent)

**Interfaces:**
- Consumes: `Identity.identity().clientId`, `Identity.registrar().ensureRegistered()` (Task 2); `ApiService.getAppCategoryMapping`, `ApiService.submitUsageReport` (existing).
- Produces:
  - `data class UsageEvent(val packageName: String, val type: Int, val timeMs: Long)`; `object ForegroundTime { fun secondsByPackage(events: List<UsageEvent>, startMs: Long, endMs: Long): Map<String, Int> }`.
  - `object Day { fun startOfDayMs(nowMs: Long, zone: TimeZone): Long; fun dayString(nowMs: Long, zone: TimeZone): String }`.
  - `class CategoryMapper(fetch: suspend (List<String>) -> Response<Map<String,String>>)` with `suspend fun map(packages: List<String>): CategoryMapper.Outcome` (`Mapped(categories)` or `Failed(retryable)`), `BATCH_SIZE = 100`.
  - `class UsageSyncer(...)` with `suspend fun run(): UsageSyncer.Outcome` (`SUCCESS`, `RETRY`, `FAIL`).

- [ ] **Step 1: Write the failing tests**

`ForegroundTimeTest.kt` (event types: resumed=1, paused=2, stopped=23, screen off=16, shutdown=26):

```kotlin
package com.kreativekoala.surfsense.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTimeTest {
    private val start = 1_000_000L
    private val end = start + 10 * 60_000L
    private fun ev(pkg: String, type: Int, atMin: Double) = UsageEvent(pkg, type, start + (atMin * 60_000).toLong())

    @Test fun pairsResumeAndPause() {
        val r = ForegroundTime.secondsByPackage(listOf(ev("a", 1, 1.0), ev("a", 2, 3.0)), start, end)
        assertEquals(mapOf("a" to 120), r)
    }

    @Test fun sumsSeveralSessionsAndSeparatesPackages() {
        val r = ForegroundTime.secondsByPackage(
            listOf(ev("a", 1, 0.0), ev("a", 2, 1.0), ev("b", 1, 1.0), ev("b", 2, 2.0), ev("a", 1, 5.0), ev("a", 2, 6.0)),
            start, end
        )
        assertEquals(mapOf("a" to 120, "b" to 60), r)
    }

    @Test fun anAppStillOpenAtTheEndIsClampedToNow() {
        assertEquals(mapOf("a" to 120), ForegroundTime.secondsByPackage(listOf(ev("a", 1, 8.0)), start, end))
    }

    @Test fun aPauseWithoutAResumeInTheWindowIsIgnored() {
        assertEquals(emptyMap<String, Int>(), ForegroundTime.secondsByPackage(listOf(ev("a", 2, 3.0)), start, end))
    }

    @Test fun screenOffClosesEveryOpenApp() {
        val r = ForegroundTime.secondsByPackage(listOf(ev("a", 1, 1.0), ev("b", 1, 2.0), ev("x", 16, 3.0)), start, end)
        assertEquals(mapOf("a" to 120, "b" to 60), r)
    }

    @Test fun deviceShutdownClosesEveryOpenApp() {
        assertEquals(mapOf("a" to 60), ForegroundTime.secondsByPackage(listOf(ev("a", 1, 1.0), ev("x", 26, 2.0)), start, end))
    }

    @Test fun duplicateResumeDoesNotRestartTheClockAndStoppedAfterPausedAddsNothing() {
        val r = ForegroundTime.secondsByPackage(
            listOf(ev("a", 1, 1.0), ev("a", 1, 2.0), ev("a", 2, 3.0), ev("a", 23, 3.1)), start, end
        )
        assertEquals(mapOf("a" to 120), r)
    }

    @Test fun unsortedInputIsHandledAndSubSecondSessionsDropped() {
        val r = ForegroundTime.secondsByPackage(
            listOf(ev("a", 2, 3.0), ev("a", 1, 1.0), UsageEvent("b", 1, start), UsageEvent("b", 2, start + 400)), start, end
        )
        assertEquals(mapOf("a" to 120), r)
    }

    @Test fun eventsOutsideTheWindowAreClamped() {
        val r = ForegroundTime.secondsByPackage(listOf(UsageEvent("a", 1, end - 30_000), UsageEvent("a", 2, end + 60_000)), start, end)
        assertEquals(mapOf("a" to 30), r)
    }
}
```

`DayTest.kt`:

```kotlin
package com.kreativekoala.surfsense.usage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class DayTest {
    private val la = TimeZone.getTimeZone("America/Los_Angeles")
    private fun ms(z: String, y: Int, m: Int, d: Int, h: Int, min: Int) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneId.of(z)).toInstant().toEpochMilli()

    @Test fun startOfDayIsLocalMidnight() {
        val now = ms("America/Los_Angeles", 2026, 10, 12, 23, 30)
        assertEquals(ms("America/Los_Angeles", 2026, 10, 12, 0, 0), Day.startOfDayMs(now, la))
    }

    @Test fun dayStringUsesTheLocalDateNotUtc() {
        // 23:30 in Los Angeles on Oct 12 is already Oct 13 in UTC
        val now = ms("America/Los_Angeles", 2026, 10, 12, 23, 30)
        assertEquals("2026-10-12", Day.dayString(now, la))
        assertEquals("2026-10-13", Day.dayString(now, TimeZone.getTimeZone("UTC")))
    }

    @Test fun midnightBelongsToTheNewDay() {
        val now = ms("America/Los_Angeles", 2026, 10, 13, 0, 0)
        assertEquals("2026-10-13", Day.dayString(now, la))
        assertEquals(now, Day.startOfDayMs(now, la))
    }

    @Test fun daylightSavingDayStartsAtLocalMidnight() {
        // US fall-back day is 2026-11-01 (25-hour day)
        val now = ms("America/Los_Angeles", 2026, 11, 1, 12, 0)
        assertEquals(ms("America/Los_Angeles", 2026, 11, 1, 0, 0), Day.startOfDayMs(now, la))
        assertEquals("2026-11-01", Day.dayString(now, la))
    }
}
```

`CategoryMapperTest.kt`:

```kotlin
package com.kreativekoala.surfsense.usage

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class CategoryMapperTest {
    private fun pkgs(n: Int) = (1..n).map { "com.app$it" }

    @Test fun sendsAtMost100PerRequestAndMergesTheAnswers() = runBlocking {
        val sizes = ArrayList<Int>()
        val mapper = CategoryMapper { chunk ->
            sizes.add(chunk.size)
            Response.success(chunk.associateWith { "News" })
        }
        val out = mapper.map(pkgs(250))
        assertEquals(listOf(100, 100, 50), sizes)
        assertEquals(250, (out as CategoryMapper.Outcome.Mapped).categories.size)
    }

    @Test fun noPackagesMeansNoRequests() = runBlocking {
        var calls = 0
        val mapper = CategoryMapper { calls++; Response.success(emptyMap()) }
        assertEquals(CategoryMapper.Outcome.Mapped(emptyMap()), mapper.map(emptyList()))
        assertEquals(0, calls)
    }

    @Test fun retryableFailuresAre429Server5xxAndIo() = runBlocking {
        for (code in listOf(429, 500, 503)) {
            val m = CategoryMapper { Response.error(code, "".toResponseBody(null)) }
            assertEquals(CategoryMapper.Outcome.Failed(retryable = true), m.map(pkgs(1)))
        }
        val io = CategoryMapper { throw IOException("offline") }
        assertEquals(CategoryMapper.Outcome.Failed(retryable = true), io.map(pkgs(1)))
    }

    @Test fun clientErrorsAreNotRetryable() = runBlocking {
        for (code in listOf(400, 401, 403)) {
            val m = CategoryMapper { Response.error(code, "".toResponseBody(null)) }
            assertEquals(CategoryMapper.Outcome.Failed(retryable = false), m.map(pkgs(1)))
        }
    }

    @Test fun aFailureInALaterChunkFailsTheWholeCall() = runBlocking {
        var n = 0
        val m = CategoryMapper { chunk ->
            if (++n == 2) Response.error(503, "".toResponseBody(null)) else Response.success(chunk.associateWith { "Other" })
        }
        assertTrue(m.map(pkgs(150)) is CategoryMapper.Outcome.Failed)
    }
}
```

`UsageSyncerTest.kt`:

```kotlin
package com.kreativekoala.surfsense.usage

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class UsageSyncerTest {
    private class Fixture(
        val usage: Map<String, Int> = mapOf("com.a" to 100, "com.b" to 50, "com.c" to 25),
        val registered: Boolean = true,
        val mapping: (List<String>) -> Response<Map<String, String>> =
            { Response.success(mapOf("com.a" to "News", "com.b" to "News")) },
        val submitReply: () -> Response<Unit> = { Response.success(Unit) },
    ) {
        var submitted: Pair<String, Map<String, Int>>? = null
        var collected = 0
        val syncer = UsageSyncer(
            collect = { collected++; usage },
            mapper = CategoryMapper { mapping(it) },
            submit = { day, summary -> submitted = day to summary; submitReply() },
            ensureRegistered = { registered },
            today = { "2026-10-12" },
        )
    }

    @Test fun aggregatesByCategoryAndUnmappedBecomesOther() = runBlocking {
        val f = Fixture()
        assertEquals(UsageSyncer.Outcome.SUCCESS, f.syncer.run())
        assertEquals("2026-10-12" to mapOf("News" to 150, "Other" to 25), f.submitted)
    }

    @Test fun notRegisteredRetriesWithoutCollecting() = runBlocking {
        val f = Fixture(registered = false)
        assertEquals(UsageSyncer.Outcome.RETRY, f.syncer.run())
        assertEquals(0, f.collected)
    }

    @Test fun noUsageSucceedsWithoutSubmitting() = runBlocking {
        val f = Fixture(usage = emptyMap())
        assertEquals(UsageSyncer.Outcome.SUCCESS, f.syncer.run())
        assertEquals(null, f.submitted)
    }

    @Test fun mappingFailuresMapToRetryOrFail() = runBlocking {
        val retry = Fixture(mapping = { Response.error(503, "".toResponseBody(null)) })
        assertEquals(UsageSyncer.Outcome.RETRY, retry.syncer.run())
        val fail = Fixture(mapping = { Response.error(400, "".toResponseBody(null)) })
        assertEquals(UsageSyncer.Outcome.FAIL, fail.syncer.run())
    }

    @Test fun submitOutcomes() = runBlocking {
        assertEquals(UsageSyncer.Outcome.RETRY, Fixture(submitReply = { Response.error(500, "".toResponseBody(null)) }).syncer.run())
        assertEquals(UsageSyncer.Outcome.RETRY, Fixture(submitReply = { Response.error(429, "".toResponseBody(null)) }).syncer.run())
        assertEquals(UsageSyncer.Outcome.FAIL, Fixture(submitReply = { Response.error(400, "".toResponseBody(null)) }).syncer.run())
        assertEquals(UsageSyncer.Outcome.RETRY, Fixture(submitReply = { throw IOException("offline") }).syncer.run())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.kreativekoala.surfsense.usage.*' --console=plain 2>&1 | tail -12`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement the pure classes**

`ForegroundTime.kt`:

```kotlin
package com.kreativekoala.surfsense.usage

data class UsageEvent(val packageName: String, val type: Int, val timeMs: Long)

/**
 * Foreground time per package from UsageEvents, for the window [startMs, endMs].
 * Event type values are stable platform constants (see android.app.usage.UsageEvents.Event).
 */
object ForegroundTime {
    const val ACTIVITY_RESUMED = 1          // MOVE_TO_FOREGROUND
    const val ACTIVITY_PAUSED = 2           // MOVE_TO_BACKGROUND
    const val SCREEN_NON_INTERACTIVE = 16
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26

    fun secondsByPackage(events: List<UsageEvent>, startMs: Long, endMs: Long): Map<String, Int> {
        val openSince = HashMap<String, Long>()
        val totalMs = HashMap<String, Long>()

        fun close(pkg: String, at: Long) {
            val since = openSince.remove(pkg) ?: return
            val from = maxOf(since, startMs)
            val to = minOf(at, endMs)
            if (to > from) totalMs[pkg] = (totalMs[pkg] ?: 0L) + (to - from)
        }

        for (e in events.sortedBy { it.timeMs }) {
            when (e.type) {
                ACTIVITY_RESUMED -> if (!openSince.containsKey(e.packageName)) openSince[e.packageName] = e.timeMs
                ACTIVITY_PAUSED, ACTIVITY_STOPPED -> close(e.packageName, e.timeMs)
                SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN -> openSince.keys.toList().forEach { close(it, e.timeMs) }
            }
        }
        openSince.keys.toList().forEach { close(it, endMs) }

        return totalMs.mapValues { (it.value / 1000).toInt() }.filterValues { it > 0 }
    }
}
```

`Day.kt`:

```kotlin
package com.kreativekoala.surfsense.usage

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Day {
    fun startOfDayMs(nowMs: Long, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun dayString(nowMs: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(Date(nowMs))
}
```

`CategoryMapper.kt`:

```kotlin
package com.kreativekoala.surfsense.usage

import retrofit2.Response
import java.io.IOException

/** Looks up categories in batches the backend accepts (at most 100 per request). */
class CategoryMapper(
    private val fetch: suspend (List<String>) -> Response<Map<String, String>>,
) {
    sealed interface Outcome {
        data class Mapped(val categories: Map<String, String>) : Outcome
        data class Failed(val retryable: Boolean) : Outcome
    }

    suspend fun map(packages: List<String>): Outcome {
        val result = HashMap<String, String>()
        for (chunk in packages.chunked(BATCH_SIZE)) {
            val response = try {
                fetch(chunk)
            } catch (e: IOException) {
                return Outcome.Failed(retryable = true)
            }
            if (!response.isSuccessful) {
                val code = response.code()
                return Outcome.Failed(retryable = code == 429 || code >= 500)
            }
            result.putAll(response.body().orEmpty())
        }
        return Outcome.Mapped(result)
    }

    companion object {
        const val BATCH_SIZE = 100
    }
}
```

`UsageSyncer.kt`:

```kotlin
package com.kreativekoala.surfsense.usage

import retrofit2.Response
import java.io.IOException

class UsageSyncer(
    private val collect: () -> Map<String, Int>,
    private val mapper: CategoryMapper,
    private val submit: suspend (day: String, summary: Map<String, Int>) -> Response<Unit>,
    private val ensureRegistered: suspend () -> Boolean,
    private val today: () -> String,
) {
    enum class Outcome { SUCCESS, RETRY, FAIL }

    suspend fun run(): Outcome {
        if (!ensureRegistered()) return Outcome.RETRY

        val usage = collect()
        if (usage.isEmpty()) return Outcome.SUCCESS

        val mapping = when (val m = mapper.map(usage.keys.toList())) {
            is CategoryMapper.Outcome.Mapped -> m.categories
            is CategoryMapper.Outcome.Failed -> return if (m.retryable) Outcome.RETRY else Outcome.FAIL
        }

        val summary = HashMap<String, Int>()
        for ((pkg, seconds) in usage) {
            val category = mapping[pkg] ?: "Other"
            summary[category] = (summary[category] ?: 0) + seconds
        }

        val response = try {
            submit(today(), summary)
        } catch (e: IOException) {
            return Outcome.RETRY
        }
        val code = response.code()
        return when {
            response.isSuccessful -> Outcome.SUCCESS
            code == 429 || code >= 500 -> Outcome.RETRY
            else -> Outcome.FAIL
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -12`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Implement the Android glue and replace the worker**

`UsageAccess.kt`:

```kotlin
package com.kreativekoala.surfsense.usage

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

object UsageAccess {
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
```

`UsageCollector.kt`:

```kotlin
package com.kreativekoala.surfsense.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.TimeZone

class UsageCollector(private val context: Context) {

    /** Foreground seconds per package from local midnight until [nowMs]. */
    fun todaySeconds(nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Map<String, Int> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = Day.startOfDayMs(nowMs, zone)
        val events = manager.queryEvents(start, nowMs) ?: return emptyMap()

        val list = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            list.add(UsageEvent(event.packageName, event.eventType, event.timeStamp))
        }

        val excluded = systemSurfaces()
        return ForegroundTime.secondsByPackage(list, start, nowMs).filterKeys { it !in excluded }
    }

    /** Launchers and system UI are "foreground" all day but are not something the user spends time in. */
    private fun systemSurfaces(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = context.packageManager
            .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
        return (launchers + "com.android.systemui" + "android").toSet()
    }
}
```

`AndroidManifest.xml`: inside `<manifest>` (after the `<uses-permission>` lines, before `<application>`) add:

```xml
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.HOME" />
        </intent>
    </queries>
```

Replace the entire body of `UsageSummaryWorker.kt` with:

```kotlin
package com.kreativekoala.surfsense

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kreativekoala.surfsense.identity.Identity
import com.kreativekoala.surfsense.usage.CategoryMapper
import com.kreativekoala.surfsense.usage.Day
import com.kreativekoala.surfsense.usage.UsageAccess
import com.kreativekoala.surfsense.usage.UsageCollector
import com.kreativekoala.surfsense.usage.UsageSyncer
import timber.log.Timber
import java.util.TimeZone

class UsageSummaryWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Identity.init(applicationContext)
        // Without Usage Access there is nothing to read; succeed quietly so the schedule keeps running.
        if (!UsageAccess.isGranted(applicationContext)) return Result.success()

        val syncer = UsageSyncer(
            collect = { UsageCollector(applicationContext).todaySeconds() },
            mapper = CategoryMapper { ApiClient.create().getAppCategoryMapping(AppCategoryRequest(it)) },
            submit = { day, summary ->
                ApiClient.create().submitUsageReport(
                    UsageReportRequest(Identity.identity().clientId, day, summary.toMutableMap())
                )
            },
            ensureRegistered = { Identity.registrar().ensureRegistered() },
            today = { Day.dayString(System.currentTimeMillis(), TimeZone.getDefault()) },
        )

        return try {
            when (syncer.run()) {
                UsageSyncer.Outcome.SUCCESS -> Result.success()
                UsageSyncer.Outcome.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
                UsageSyncer.Outcome.FAIL -> Result.failure()
            }
        } catch (e: Exception) {
            Timber.e(e, "Usage sync crashed")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
```

In `MainActivity.kt`, make `hasUsageStatsPermission()` call `UsageAccess.isGranted(this)` and delete its private duplicate body (keep the function name so Task 6 can use it).

Remove `getUsageStats()` from `UsageRepository.kt` after confirming it is unused: `grep -rn "getUsageStats" app/src/main/java` must show only its own definition. If something calls it, leave it and report.

- [ ] **Step 6: Compile and run everything**

Run: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --console=plain 2>&1 | tail -12`
Expected: `BUILD SUCCESSFUL`. Check no category named `Uncategorized` remains outside strings: `grep -rn "Uncategorized" app/src/main/java`; if a UI mapping references it, leave it (`categoryColor` falls back to gray) and report.

- [ ] **Step 7: Commit**

```bash
cd ~/Documents/GitHub/SurfSense-android
git add android/app/src/main/java/com/kreativekoala/surfsense/usage android/app/src/test/java/com/kreativekoala/surfsense/usage android/app/src/main/java/com/kreativekoala/surfsense/UsageSummaryWorker.kt android/app/src/main/java/com/kreativekoala/surfsense/MainActivity.kt android/app/src/main/java/com/kreativekoala/surfsense/repository/UsageRepository.kt android/app/src/main/AndroidManifest.xml
git commit -m "android: event-based calendar-day usage, batched categories, testable sync worker" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Honest errors and a sync that waits for the worker

**Files:**
- Create: `android/app/src/main/java/com/kreativekoala/surfsense/net/ApiFailure.kt`
- Create: `android/app/src/test/java/com/kreativekoala/surfsense/net/ApiFailureTest.kt`
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/repository/UsageRepository.kt`
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/viewmodel/MainViewModel.kt`

**Interfaces:**
- Produces: `class ApiFailure(val code: Int, val serverMessage: String?) : Exception`; `fun <T> Response<T>.toFailure(): ApiFailure`; `object ErrorMessages { fun from(t: Throwable): String }`.
- Consumes: `UsageRepository` result wrappers (existing). Messages (exact): offline `No connection. Check your internet and try again.`; 401/403 `This device isn't recognized by the server. Close and reopen the app to reconnect.`; 429 `Too many requests. Try again in a minute.`; 5xx `The server is having trouble. Try again soon.`; a server-provided message (4xx) is shown as-is; otherwise `Something went wrong (<code>).`

- [ ] **Step 1: Write the failing test**

`ApiFailureTest.kt`:

```kotlin
package com.kreativekoala.surfsense.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

class ApiFailureTest {
    private fun err(code: Int, body: String) =
        Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType()))

    @Test fun parsesTheServersErrorField() {
        val f = err(400, """{"success":false,"error":"Invalid or expired code"}""").toFailure()
        assertEquals(400, f.code)
        assertEquals("Invalid or expired code", f.serverMessage)
    }

    @Test fun ignoresNonJsonOrMissingFields() {
        assertNull(err(502, "<html>bad gateway</html>").toFailure().serverMessage)
        assertNull(err(400, "{}").toFailure().serverMessage)
        assertNull(err(500, "").toFailure().serverMessage)
    }

    @Test fun messagesByCause() {
        assertEquals("No connection. Check your internet and try again.", ErrorMessages.from(IOException("x")))
        assertEquals("No connection. Check your internet and try again.", ErrorMessages.from(SocketTimeoutException()))
        assertEquals("This device isn't recognized by the server. Close and reopen the app to reconnect.", ErrorMessages.from(ApiFailure(401, null)))
        assertEquals("This device isn't recognized by the server. Close and reopen the app to reconnect.", ErrorMessages.from(ApiFailure(403, "Not authorized")))
        assertEquals("Too many requests. Try again in a minute.", ErrorMessages.from(ApiFailure(429, "Too many linking attempts, please try again later")))
        assertEquals("The server is having trouble. Try again soon.", ErrorMessages.from(ApiFailure(503, null)))
        assertEquals("Invalid or expired code", ErrorMessages.from(ApiFailure(400, "Invalid or expired code")))
        assertEquals("Something went wrong (404).", ErrorMessages.from(ApiFailure(404, null)))
        assertEquals("Something went wrong.", ErrorMessages.from(IllegalStateException("x")))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.kreativekoala.surfsense.net.ApiFailureTest' --console=plain 2>&1 | tail -10`
Expected: FAIL (unresolved).

- [ ] **Step 3: Implement `ApiFailure.kt`**

```kotlin
package com.kreativekoala.surfsense.net

import com.google.gson.JsonParser
import retrofit2.Response
import java.io.IOException

/** An HTTP error response from the backend. [serverMessage] is the `error` field of its JSON body, if any. */
class ApiFailure(val code: Int, val serverMessage: String?) : Exception("HTTP $code")

fun <T> Response<T>.toFailure(): ApiFailure {
    val message = try {
        val body = errorBody()?.string().orEmpty()
        val element = JsonParser.parseString(body)
        if (element.isJsonObject && element.asJsonObject.get("error")?.isJsonPrimitive == true) {
            element.asJsonObject.get("error").asString
        } else null
    } catch (e: Exception) {
        null
    }
    return ApiFailure(code(), message)
}

object ErrorMessages {
    fun from(t: Throwable): String = when {
        t is IOException -> "No connection. Check your internet and try again."
        t is ApiFailure -> when {
            t.code == 401 || t.code == 403 ->
                "This device isn't recognized by the server. Close and reopen the app to reconnect."
            t.code == 429 -> "Too many requests. Try again in a minute."
            t.code >= 500 -> "The server is having trouble. Try again soon."
            !t.serverMessage.isNullOrBlank() -> t.serverMessage
            else -> "Something went wrong (${t.code})."
        }
        else -> "Something went wrong."
    }
}
```

Note: a 429 message from the server is intentionally replaced by the fixed text above.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -10`
Expected: PASS.

- [ ] **Step 5: Use it in the repository and view model**

In `UsageRepository.kt`, replace each `Result.failure(Exception("...: ${response.code()}"))` with `Result.failure(response.toFailure())` (import `com.kreativekoala.surfsense.net.toFailure`). In `linkDevice`, the `body.success == false` case stays `Result.failure(Exception("Failed to link device"))`. Leave the `catch (e: Exception)` branches as they are (an `IOException` passes through as itself).

In `MainViewModel.kt`:
- Import `com.kreativekoala.surfsense.net.ErrorMessages`, `androidx.work.WorkInfo`, `kotlinx.coroutines.flow.first`, `kotlinx.coroutines.withTimeoutOrNull`.
- In `refreshData()`, after computing `selfResult` and `linkedResult`, if `selfResult.isFailure` set the error to `ErrorMessages.from(selfResult.exceptionOrNull()!!)` (keep going so the UI still renders what it has):
  `val firstError = selfResult.exceptionOrNull() ?: linkedResult.exceptionOrNull()` and, in the final state update, add `error = firstError?.let { ErrorMessages.from(it) }`.
- In `linkDevice()`, set `linkingError = result.exceptionOrNull()?.let { ErrorMessages.from(it) } ?: "Failed to link device"`.
- Replace `syncNow()` with:

```kotlin
    fun syncNow() {
        val context = getApplication<Application>()
        val syncRequest = OneTimeWorkRequestBuilder<UsageSummaryWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(syncRequest)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val finished = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                workManager.getWorkInfoByIdFlow(syncRequest.id).first { it != null && it.state.isFinished }
            }
            refreshData()
            if (finished?.state == WorkInfo.State.FAILED) {
                _uiState.value = _uiState.value.copy(
                    error = "Couldn't sync. Check that Usage Access is allowed and you are online."
                )
            }
        }
    }
```

and add `private const val SYNC_TIMEOUT_MS = 60_000L` in the companion object. Note: `refreshData()` starts its own coroutine and clears `error`; set the FAILED error after `refreshData()` returns by re-applying it inside `refreshData`'s completion. If the ordering makes the message disappear in practice, make `refreshData` take an optional `extraError: String?` parameter and merge it into its final state update.

- [ ] **Step 6: Compile and test**

Run: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
cd ~/Documents/GitHub/SurfSense-android
git add android/app/src/main/java/com/kreativekoala/surfsense/net/ApiFailure.kt android/app/src/test/java/com/kreativekoala/surfsense/net/ApiFailureTest.kt android/app/src/main/java/com/kreativekoala/surfsense/repository/UsageRepository.kt android/app/src/main/java/com/kreativekoala/surfsense/viewmodel/MainViewModel.kt
git commit -m "android: readable errors, link-code message, sync waits for the worker" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Permissions, backup and a plain-language disclosure

**Files:**
- Delete: `android/app/src/main/java/com/kreativekoala/surfsense/NotificationListener.kt`, `NotificationAdapter.kt`, `NotificationData.kt`, `android/app/src/main/res/layout/item_notification.xml`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/res/xml/backup_rules.xml`, `android/app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/java/com/kreativekoala/surfsense/MainActivity.kt`

**Interfaces:**
- Consumes: `UsageAccess.isGranted` (Task 4).
- Findings this task acts on: `NotificationListener`/`NotificationAdapter`/`NotificationData` are unreferenced and not declared as a service in the manifest (dead code), while the manifest still declares `READ_NOTIFICATIONS` and `POST_NOTIFICATIONS`; a privacy tracker that asks for notification access draws Play review; Auto Backup is on with empty (sample) rules, so a restore could bring back an identity whose encrypted key cannot be decrypted on the new device; and `MainActivity` sends the user straight to Settings with no explanation.

- [ ] **Step 1: Confirm the notification code is dead**

Run:
```bash
cd ~/Documents/GitHub/SurfSense-android/android/app/src/main
grep -rn -E "NotificationListener|NotificationAdapter|NotificationData|item_notification|ItemNotification|POST_NOTIFICATIONS|NotificationManager|NotificationCompat|notify\(" . | grep -v -E "^./java/com/kreativekoala/surfsense/(NotificationListener|NotificationAdapter|NotificationData)\.kt" 
```
Expected: only the two manifest permission lines (and no posting of notifications anywhere). If anything else references these classes or posts a notification, STOP and report instead of deleting.

- [ ] **Step 2: Remove the dead code and unused permissions**

```bash
git rm java/com/kreativekoala/surfsense/NotificationListener.kt java/com/kreativekoala/surfsense/NotificationAdapter.kt java/com/kreativekoala/surfsense/NotificationData.kt res/layout/item_notification.xml
```
In `AndroidManifest.xml` delete the `READ_NOTIFICATIONS` and `POST_NOTIFICATIONS` `<uses-permission>` lines and set `android:allowBackup="false"` on `<application>`.

Replace `res/xml/backup_rules.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="sharedpref" path="." />
    <exclude domain="file" path="." />
    <exclude domain="database" path="." />
</full-backup-content>
```

Replace `res/xml/data_extraction_rules.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
    </device-transfer>
</data-extraction-rules>
```

- [ ] **Step 3: Add the disclosure strings and dialog**

In `res/values/strings.xml` add (English only; other locales fall back to it):

```xml
    <string name="usage_access_title">Allow usage access</string>
    <string name="usage_access_message">SurfSense reads how long each app is open today. App names are sent to the SurfSense server so they can be sorted into categories such as Social Media or News. Totals are stored under a random ID, not your name or email.\n\nNext, Android will open a settings screen where you can turn on Usage Access for SurfSense.</string>
    <string name="usage_access_continue">Continue</string>
    <string name="usage_access_not_now">Not now</string>
```

In `MainActivity.kt`, replace `requestUsageAccessPermission()` with:

```kotlin
    private fun requestUsageAccessPermission() {
        if (hasUsageStatsPermission()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.usage_access_title)
            .setMessage(R.string.usage_access_message)
            .setPositiveButton(R.string.usage_access_continue) { _, _ ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            .setNegativeButton(R.string.usage_access_not_now, null)
            .show()
    }
```

(Keep the activity's other imports; add `R` if not already imported: `com.kreativekoala.surfsense.R`.)

- [ ] **Step 4: Build and inspect the merged manifest**

Run:
```bash
cd ~/Documents/GitHub/SurfSense-android/android
export GRADLE_USER_HOME=$HOME/.gradle-surfsense LC_ALL=en_US.UTF-8
./gradlew :app:testDebugUnitTest :app:processReleaseMainManifest --console=plain 2>&1 | tail -8
M=$(find app/build/intermediates -name AndroidManifest.xml -path "*processReleaseMainManifest*" | head -1)
grep -E "uses-permission|allowBackup|dataExtractionRules|fullBackupContent|<queries" "$M"
```
Expected: `BUILD SUCCESSFUL`; the merged manifest lists only `INTERNET` and `PACKAGE_USAGE_STATS` permissions (plus any `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that AndroidX adds), `allowBackup="false"`, and the `<queries>` block. If a library adds an unexpected permission, report it.

- [ ] **Step 5: Commit**

```bash
cd ~/Documents/GitHub/SurfSense-android
git add -A android/app/src/main
git status --short | head      # only android/app/src/main paths may appear
git commit -m "android: drop unused notification code and permissions, disable backup, add usage-access disclosure" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```
(`git add -A` is limited to `android/app/src/main` inside this clean worktree; verify the status listing shows nothing else before committing.)

---

### Task 7: Signing, version, fastlane and changelogs

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/fastlane/Fastfile`, `android/fastlane/Appfile`
- Create: `android/fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` for `de-DE en-US es-ES fr-FR hi-IN it-IT ja-JP ko-KR pt-BR zh-CN`
- Modify: `docs/superpowers/specs/2026-10-12-surfsense-revival-and-redesign-design.md` (Part 5 versions)

**Interfaces:**
- Consumes: `~/.gradle/gradle.properties` keys `ANDROID_UPLOAD_*` (names verified present), the Play service-account JSON path used by `~/Documents/GitHub/SecureVox/android/fastlane/Appfile`.
- Produces: signed `app-release.aab` at `android/app/build/outputs/bundle/release/`, lanes `deploy_internal`, `deploy_staged` (production, `ROLLOUT` env, default `0.2`), versionName `10.0.0`.

- [ ] **Step 1: Find the live version codes**

Run:
```bash
cd ~/Documents/GitHub/SurfSense-android/android
KEY=$(grep -o 'json_key_file(.*)' ~/Documents/GitHub/SecureVox/android/fastlane/Appfile | sed -E 's/json_key_file\("?([^")]*)"?\)/\1/')
LC_ALL=en_US.UTF-8 fastlane run google_play_track_version_codes package_name:com.kreativekoala.surfsense track:production json_key:"$KEY" 2>&1 | tail -6
LC_ALL=en_US.UTF-8 fastlane run google_play_track_version_codes package_name:com.kreativekoala.surfsense track:internal json_key:"$KEY" 2>&1 | tail -4
```
Expected: lists the version codes on each track. Set `NEW_CODE = max(all listed codes) + 1` (at least 10). If the app is not accessible with this service account (permission error), STOP and ask the user: the account may need access to this app in the Play Console.

- [ ] **Step 2: Signing config and version**

In `android/app/build.gradle.kts`, set `versionCode = <NEW_CODE>` and `versionName = "10.0.0"`. Inside `android { ... }`, before `buildTypes`, add:

```kotlin
    signingConfigs {
        val storeFile = providers.gradleProperty("ANDROID_UPLOAD_STORE_FILE")
        val storePassword = providers.gradleProperty("ANDROID_UPLOAD_STORE_PASSWORD")
        val keyAlias = providers.gradleProperty("ANDROID_UPLOAD_KEY_ALIAS")
        val keyPassword = providers.gradleProperty("ANDROID_UPLOAD_KEY_PASSWORD")
        if (storeFile.isPresent && storePassword.isPresent && keyAlias.isPresent && keyPassword.isPresent) {
            create("release") {
                this.storeFile = file(storeFile.get())
                this.storePassword = storePassword.get()
                this.keyAlias = keyAlias.get()
                this.keyPassword = keyPassword.get()
            }
        }
    }
```

In `buildTypes { release { ... } }` add `signingConfigs.findByName("release")?.let { signingConfig = it }`. In `defaultConfig` add an optional dev knob so a verification build can coexist with the Play install:

```kotlin
        providers.gradleProperty("APP_ID_SUFFIX").orNull?.let { applicationIdSuffix = it }
```
(`applicationIdSuffix` belongs in `buildTypes.debug`/`release` or `defaultConfig`; if `defaultConfig` rejects it, put it inside both build types guarded the same way.)

Confirm no literal secrets: `grep -n -i "password" app/build.gradle.kts` must show only `providers.gradleProperty("ANDROID_UPLOAD_...PASSWORD")` and the `this.storePassword`/`this.keyPassword` assignments from them.

- [ ] **Step 3: Fastlane**

Create `android/fastlane/Appfile`: copy the `json_key_file(...)` line from `~/Documents/GitHub/SecureVox/android/fastlane/Appfile` unchanged and set `package_name("com.kreativekoala.surfsense")`. Do not print the key path into logs or commit messages; the file only references a path on this machine.

Create `android/fastlane/Fastfile`:

```ruby
default_platform(:android)

platform :android do
  desc "Upload to the internal track for smoke testing"
  lane :deploy_internal do
    upload_to_play_store(
      track: "internal",
      aab: "app/build/outputs/bundle/release/app-release.aab",
      skip_upload_metadata: true,
      skip_upload_images: true,
      skip_upload_screenshots: true,
      skip_upload_changelogs: false,
      release_status: "completed"
    )
  end

  desc "Validate the production upload with Google without publishing anything"
  lane :validate do
    upload_to_play_store(
      track: "production",
      aab: "app/build/outputs/bundle/release/app-release.aab",
      validate_only: true,
      skip_upload_metadata: true,
      skip_upload_images: true,
      skip_upload_screenshots: true,
      skip_upload_changelogs: false,
      release_status: "inProgress",
      rollout: "0.2"
    )
  end

  desc "Staged production rollout. ROLLOUT env sets the fraction (default 0.2). Store listing text is NOT touched."
  lane :deploy_staged do
    upload_to_play_store(
      track: "production",
      aab: "app/build/outputs/bundle/release/app-release.aab",
      skip_upload_metadata: true,
      skip_upload_images: true,
      skip_upload_screenshots: true,
      skip_upload_changelogs: false,
      release_status: "inProgress",
      rollout: ENV["ROLLOUT"] || "0.2"
    )
  end
end
```

(There is deliberately no lane that completes a 100% production release.)

- [ ] **Step 4: Changelogs**

Create `android/fastlane/metadata/android/<locale>/changelogs/<NEW_CODE>.txt` (replace `<NEW_CODE>` with the number from Step 1) with exactly these texts (each under 500 characters):

- en-US: `Syncing is fixed: your devices connect again and daily totals are more accurate. Removed permissions the app no longer uses.`
- de-DE: `Die Synchronisierung funktioniert wieder: Deine Geräte verbinden sich erneut und die Tagessummen sind genauer. Nicht mehr benötigte Berechtigungen wurden entfernt.`
- es-ES: `La sincronización vuelve a funcionar: tus dispositivos se conectan de nuevo y los totales diarios son más precisos. Se eliminaron permisos que la app ya no usa.`
- fr-FR: `La synchronisation fonctionne de nouveau : vos appareils se reconnectent et les totaux quotidiens sont plus précis. Des autorisations inutilisées ont été supprimées.`
- hi-IN: `सिंक फिर से काम करता है: आपके डिवाइस दोबारा जुड़ते हैं और दैनिक कुल समय अधिक सटीक है। जिन अनुमतियों का ऐप अब उपयोग नहीं करता, उन्हें हटा दिया गया है।`
- it-IT: `La sincronizzazione funziona di nuovo: i tuoi dispositivi si ricollegano e i totali giornalieri sono più precisi. Rimossi i permessi non più usati.`
- ja-JP: `同期が復旧しました。デバイスが再び接続され、1日の合計時間がより正確になりました。使われていない権限を削除しました。`
- ko-KR: `동기화가 복구되었습니다. 기기가 다시 연결되고 일일 합계가 더 정확해졌습니다. 더 이상 사용하지 않는 권한을 제거했습니다.`
- pt-BR: `A sincronização voltou a funcionar: seus dispositivos se conectam novamente e os totais diários estão mais precisos. Permissões que o app não usa mais foram removidas.`
- zh-CN: `已修复同步问题：设备可重新连接，每日总计更准确。并移除了应用不再使用的权限。`

- [ ] **Step 5: Update the spec's version line**

In the spec's Part 5, change the Android line to: `Android 10.0.0 (versionCode <NEW_CODE>) is the reliability release (this plan); the redesign and new features ship as 10.1.0 from a separate plan. Extension 4.0.0.`

- [ ] **Step 6: Build the signed bundle and verify it**

Run:
```bash
cd ~/Documents/GitHub/SurfSense-android/android
export GRADLE_USER_HOME=$HOME/.gradle-surfsense LC_ALL=en_US.UTF-8
./gradlew :app:testDebugUnitTest :app:bundleRelease --console=plain 2>&1 | tail -8
ls -la app/build/outputs/bundle/release/app-release.aab
jarsigner -verify -verbose:summary app/build/outputs/bundle/release/app-release.aab 2>&1 | tail -3
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab 2>&1 | grep -E "SHA256|Owner" | head -3
```
Expected: `BUILD SUCCESSFUL`; the AAB exists; `jarsigner` reports `jar verified`; the certificate SHA256 is printed. Compare that SHA256 with the upload certificate fingerprint of the other apps (the same upload key is shared; memory records it begins `ED:D1:37:83`). If it differs, STOP: do not upload.

- [ ] **Step 7: Commit**

```bash
cd ~/Documents/GitHub/SurfSense-android
git add android/app/build.gradle.kts android/fastlane docs/superpowers/specs/2026-10-12-surfsense-revival-and-redesign-design.md
git status --short | head
git commit -m "android: 10.0.0 signing from gradle properties, fastlane lanes, changelogs" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Verify on the real phone (checkpoint: needs the shared Pixel)

**Files:** none (verification only). Write findings to `docs/superpowers/plans/2026-10-13-android-device-verification.md` (a short dated log of what was run and observed) and commit it.

**Interfaces:**
- Consumes: a debug or release APK built with `-PAPP_ID_SUFFIX=.verify` (so it never clobbers a Play-installed copy), the live backend, Supabase REST via the service key fetched with the Supabase CLI (never printed).

- [ ] **Step 1: Get the phone safely**

Use `ListAgents` and `SendMessage` to ask every other local Claude session working on Android whether the Pixel is free for roughly 20 minutes; wait for consent. Then: `adb devices` (expect `41111FDAP0005H`), `adb shell dumpsys activity activities | grep -m1 mResumedActivity` (make sure nobody is mid-task), `adb shell dumpsys window | grep -m1 mDreamingLockscreen` (if the screen is locked and needs the fingerprint, STOP and ask the user). Never press Back blind; no uninstalls except `com.kreativekoala.surfsense.verify`.

- [ ] **Step 2: Install the verification build**

```bash
cd ~/Documents/GitHub/SurfSense-android/android
export GRADLE_USER_HOME=$HOME/.gradle-surfsense LC_ALL=en_US.UTF-8
./gradlew :app:assembleDebug -PAPP_ID_SUFFIX=.verify --console=plain 2>&1 | tail -4
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.kreativekoala.surfsense.verify GET_USAGE_STATS allow
```
Expected: `Success`. (If the Play copy is installed, the `.verify` id keeps them separate.)

- [ ] **Step 3: First-run flow**

Launch with `adb shell am start -n com.kreativekoala.surfsense.verify/com.kreativekoala.surfsense.MainActivity`. Take a screenshot (`adb exec-out screencap -p > /tmp/ss-1.png`, read it) and confirm: the app opens, shows no crash, and (with usage access already granted by `appops`) loads. Then revoke it (`adb shell appops set com.kreativekoala.surfsense.verify GET_USAGE_STATS deny`), force-stop and relaunch to confirm the plain-language disclosure dialog appears BEFORE Settings; tap "Not now". Re-grant it.

- [ ] **Step 4: Registration and sync against the live backend**

In the app tap Sync Now. Then verify server-side (key never printed):

```bash
P=rxwtuegccdhygbxvwzvo
KEY=$(supabase projects api-keys --project-ref $P -o json 2>/dev/null | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const k=JSON.parse(s).find(x=>x.name==="service_role");process.stdout.write(k?k.api_key:"")})')
curl -s "https://$P.supabase.co/rest/v1/extension_clients?select=client_id,client_type,client_name,created_at&order=created_at.desc&limit=3" -H "apikey: $KEY" -H "Authorization: Bearer $KEY"; echo
curl -s "https://$P.supabase.co/rest/v1/daily_summaries?select=user_id,day,summary&order=timestamp.desc&limit=2" -H "apikey: $KEY" -H "Authorization: Bearer $KEY"; echo
unset KEY
```
Expected: the newest client has `client_type":"ANDROID"` and a UUID-style `client_id`; the newest summary has today's local date and category keys from the allowed list (no `Uncategorized`).

- [ ] **Step 5: Accuracy against the OS's own numbers**

Compare the app's per-category totals to the system's per-package foreground time for today:
```bash
adb shell dumpsys usagestats | grep -A0 -E "package=.*totalTimeUsed" | head -40
```
For the 5 apps with the most time, compare the system `totalTimeUsed` with what this plan's calculator gets. Accept within 15% (the OS aggregate and event pairing differ slightly: screen-off handling, split-screen). Record the numbers. If the difference is larger than 15% for several apps, STOP and report; the event-pairing rules in Task 4 need adjusting (do not hand-tune to match one device).

- [ ] **Step 6: 401 recovery and reinstall recovery**

(a) Delete the device's row to simulate a wiped database: `curl -s -X DELETE "https://$P.supabase.co/rest/v1/extension_clients?client_id=eq.<id from step 4>" -H "apikey: $KEY" -H "Authorization: Bearer $KEY"` (re-fetch `$KEY` as above, unset after). Tap Sync Now; expect the data to reappear with the same client id (re-registered). (b) `adb shell pm clear com.kreativekoala.surfsense.verify`, relaunch, Sync Now; expect a NEW client id and working sync (previously a reinstalled app could never authenticate).

- [ ] **Step 7: Link with a synthetic second device**

Run a small node script in the scratchpad (not in the repo) that registers a device, calls `/initiate-linking`, prints the 6-digit code and waits. Ask the user to type that code into the phone's Link dialog (the one step that needs a human). Then confirm the linked device appears in the app and a summary submitted by the synthetic device shows in the combined view. Also enter a wrong code and confirm the message `Invalid or expired code` appears and `/complete-linking` was called exactly once for it (check the Fly logs: `flyctl logs -a usage-tracker-backend | tail -20`).

- [ ] **Step 8: Clean up and log**

`adb uninstall com.kreativekoala.surfsense.verify`; delete the `smoke-test`/synthetic rows (`client_name` in the verification names) via REST; write the findings log file and commit it. Tell the other sessions the Pixel is free.

---

### Task 9: Release (checkpoint: needs the user's explicit approval)

**Files:** none.

- [ ] **Step 1: Pre-flight (no publishing)**

From the worktree: `cd android && LC_ALL=en_US.UTF-8 fastlane android validate`. Expected: Google accepts the upload in validate-only mode. Re-check `git status` is clean and `git log origin/main..HEAD` lists only this plan's commits.

- [ ] **Step 2: Merge and push (user approval)**

Ask the user to approve merging branch `android-10` into `main` and pushing. Before merging, the main checkout has uncommitted changes in `android/app/src/main/java/com/kreativekoala/surfsense/Config.kt` and `extension/*`/`ios/*`. For `Config.kt`: run `git -C ~/Documents/GitHub/SurfSense diff -- android/app/src/main/java/com/kreativekoala/surfsense/Config.kt`; if the only change is the base URL line (the same URL this branch commits), run `git -C ~/Documents/GitHub/SurfSense restore android/app/src/main/java/com/kreativekoala/surfsense/Config.kt` so the fast-forward is possible; if the dirty diff contains anything else, STOP and ask. Then `git merge --ff-only android-10` and `git push origin main` (secrets scan must pass; never bypass).

- [ ] **Step 3: Staged production rollout (user approval, per release)**

Ask the user: "Publish 10.0.0 (<NEW_CODE>) to production as a 20% staged rollout?" Only on a clear yes: `cd android && ROLLOUT=0.2 LC_ALL=en_US.UTF-8 fastlane android deploy_staged`, then confirm with `fastlane run google_play_track_version_codes ... track:production`. Tell the user how to raise the rollout in Play Console, what to watch (Play vitals: crashes/ANRs; the failure reporter inbox), and that a published release cannot be halted, only superseded by a higher versionCode.

- [ ] **Step 4: Play Console items only the user can do**

Remind the user to update the Play Data safety form to match reality (app usage data and app names collected, sent to the server, linked to a random device ID not to the user; no notification access), and to confirm the Usage Access declaration text matches the new in-app disclosure.

---

## Self-review

**Spec coverage (Parts 2 and 5, Android).** Client fixes against real flows: identity/registration/401 recovery (Task 2), retry storm (Task 3), data correctness and the 100-item limit (Task 4), errors and sync UX (Task 5), permission/privacy surface (Task 6), real-device verification (Task 8). Release: signing from gradle properties, versions, lanes, changelogs (Task 7), staged rollout with approval (Task 9). Deliberately not in this plan (separate plan, 10.1.0): combined/per-device toggle and tide-line bar, category rollups with override, week-vs-last-week trends, local export, the redesign, removal of the dead `TrendsActivity`/MPAndroidChart screen, and the Compose/View UI. The spec's Android line said 10.0.0 includes the redesign; Task 7 step 5 updates it.

**Placeholder scan.** Every code step has complete code. `<NEW_CODE>` and `<locale>` are values the executor determines in Task 7 step 1 (a command is given); the Task 5 `refreshData` merge note describes a concrete fallback.

**Type consistency.** `DeviceIdentity.clientId/apiKey/reset`, `DeviceRegistrar.ensureRegistered/onUnauthorized`, `Identity.identity()/registrar()/deviceName()` are used with the same names in Tasks 2, 4 and 5. `CategoryMapper.Outcome.Mapped/Failed(retryable)` and `UsageSyncer.Outcome.SUCCESS/RETRY/FAIL` match between tests, classes and the worker. `ApiFailure(code, serverMessage)` / `ErrorMessages.from` match between tests and use. `RetryPolicy.shouldRetry(method, path, status, ioError, attempt)` matches tests and interceptor. `Config.CLIENT_TYPE` is `ANDROID` where `RegisterClientRequest` defaults use it.

**Review Focus.** 1: Task 2 `offlineFailsWithoutLosingTheId…`; 2: Task 4 `sendsAtMost100PerRequest…`; 3: Task 4 `DayTest`, `eventsOutsideTheWindowAreClamped`; 4: Task 2 `unauthorizedForgetsTheKey…` and Task 8 step 6; 5: Task 1 tests and smoke, Task 3 `completeLinkingIsNeverRetried…`, Task 5 `parsesTheServersErrorField`; 6: Task 4 worker guard, Task 6 dialog and Task 8 step 3; 7: Task 6 manifest/backup rules, verified by the merged-manifest check.

**Known risks the plan cannot remove.** Event-based foreground time can differ from Digital Wellbeing by a few percent (Task 8 step 5 measures it on one device and stops if it is off by more than 15%). `androidx.security:security-crypto` is deprecated (kept; the store rebuilds itself if the keystore key is lost). A timezone-traveling user's linked device may report under a different local date; devices are normally in one zone. The extension currently computes "today" in UTC, which will not line up with this app's local date in the evening; the extension plan must switch it to the local date.
