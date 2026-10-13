package com.kreativekoala.surfsense.net

import com.kreativekoala.surfsense.identity.DeviceIdentity
import com.kreativekoala.surfsense.identity.DeviceRegistrar
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** On HTTP 401, re-register (unless another request already did) and retry the request once. */
class ReauthAuthenticator(
    private val identity: DeviceIdentity,
    private val registrar: DeviceRegistrar,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.url.encodedPath.endsWith("/register-client") || request.header(RETRIED_HEADER) != null) {
            return null
        }
        val usedKey = request.header("Authorization")?.removePrefix("Bearer ")
        val ok = runBlocking { registrar.onUnauthorized(usedKey) }
        if (!ok) return null
        val key = identity.apiKey ?: return null
        return request.newBuilder()
            .header("Authorization", "Bearer $key")
            .header(RETRIED_HEADER, "1")
            .build()
    }

    companion object {
        const val RETRIED_HEADER = "X-SurfSense-Auth-Retried"
    }
}
