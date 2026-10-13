package com.kreativekoala.surfsense.identity

import com.kreativekoala.surfsense.Config
import com.kreativekoala.surfsense.RegisterClientRequest
import com.kreativekoala.surfsense.RegisterClientResponse
import com.google.gson.JsonParseException
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

    /**
     * The server rejected [usedKey] (HTTP 401): forget it and register again. If another request already
     * replaced the stored key, keep that one instead of wiping it.
     */
    suspend fun onUnauthorized(usedKey: String?): Boolean = mutex.withLock {
        val current = identity.apiKey
        if (current != null && current != usedKey) return@withLock true
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
            } catch (e: JsonParseException) {
                return false
            }
            if (!response.isSuccessful) return false
            val body = response.body() ?: return false
            val key = body.apiKey
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
