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
