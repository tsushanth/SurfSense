package com.kreativekoala.surfsense.net

import com.kreativekoala.surfsense.RegisterClientRequest
import com.kreativekoala.surfsense.RegisterClientResponse
import com.kreativekoala.surfsense.identity.DeviceIdentity
import com.kreativekoala.surfsense.identity.DeviceRegistrar
import com.kreativekoala.surfsense.identity.FakeSecretStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class ReauthAuthenticatorTest {
    private lateinit var server: MockWebServer
    private val identity = DeviceIdentity(FakeSecretStore(), newId = { "install-1-cccc" })
    private var registerCalls = 0
    private var registerReply: () -> Response<RegisterClientResponse> =
        { Response.success(RegisterClientResponse(true, "new-key", null)) }
    private val registrar = DeviceRegistrar(identity, { "Test Phone" }) {
        registerCalls++
        registerReply()
    }
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val b = chain.request().newBuilder()
                identity.apiKey?.let { b.header("Authorization", "Bearer $it") }
                chain.proceed(b.build())
            }
            .authenticator(ReauthAuthenticator(identity, registrar))
            .build()
    }

    @After fun tearDown() { server.shutdown() }

    private fun get(path: String) =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.code }

    @Test fun staleKeyIsReplacedAndRequestRetriedOnce() {
        identity.apiKey = "stale"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(200, get("/summary"))
        assertEquals(1, registerCalls)
        assertEquals(2, server.requestCount)
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        val retry = server.takeRequest()
        assertEquals("Bearer new-key", retry.getHeader("Authorization"))
        assertEquals("1", retry.getHeader("X-SurfSense-Auth-Retried"))
    }

    @Test fun noKeyAtAllRegistersAndRetries() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(200, get("/summary"))
        assertEquals(1, registerCalls)
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new-key", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun secondUnauthorizedIsReturnedWithoutLooping() {
        identity.apiKey = "stale"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, get("/summary"))
        assertEquals(2, server.requestCount)
        assertEquals(1, registerCalls)
    }

    @Test fun registerClientPathIsNeverReauthenticated() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, get("/api/register-client"))
        assertEquals(0, registerCalls)
        assertEquals(1, server.requestCount)
    }

    @Test fun failedRegistrationReturnsOriginal401() {
        identity.apiKey = "stale"
        registerReply = { Response.error(500, "".toResponseBody(null)) }
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, get("/summary"))
        assertEquals(1, server.requestCount)
    }

    @Test fun registrationIoFailureReturnsOriginal401() {
        identity.apiKey = "stale"
        registerReply = { throw IOException("offline") }
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, get("/summary"))
        assertEquals(1, server.requestCount)
    }

    @Test fun keyRefreshedByAnotherRequestIsReusedWithoutRegistering() {
        identity.apiKey = "stale"
        // Simulate a concurrent request refreshing the key after our request was sent.
        server.dispatcher = object : okhttp3.mockwebserver.QueueDispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") == "Bearer stale") identity.apiKey = "fresh"
                return super.dispatch(request)
            }
        }.also { it.enqueueResponse(MockResponse().setResponseCode(401)); it.enqueueResponse(MockResponse().setResponseCode(200)) }
        assertEquals(200, get("/summary"))
        assertEquals(0, registerCalls)
        server.takeRequest()
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }
}
