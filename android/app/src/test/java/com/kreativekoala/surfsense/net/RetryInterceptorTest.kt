package com.kreativekoala.surfsense.net

import okhttp3.Dns
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
import java.net.InetAddress

class RetryInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val sleeps = ArrayList<Long>()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder()
            // Test-only: isolates RetryPolicy from OkHttp's own retry; production keeps OkHttp's route fallback.
            .retryOnConnectionFailure(false)
            .dns(object : Dns {
                // MockWebServer listens on IPv4; with retryOnConnectionFailure(false) OkHttp will not fall back from ::1.
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            })
            .addInterceptor(RetryInterceptor(sleep = { sleeps.add(it) }))
            .build()
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

    @Test fun cancelledCallIsNotRetriedAndDoesNotSleep() {
        server.enqueue(MockResponse().setResponseCode(200))
        val cancelling = OkHttpClient.Builder()
            .addInterceptor { chain -> chain.call().cancel(); chain.proceed(chain.request()) }
            .addInterceptor(RetryInterceptor(sleep = { sleeps.add(it) }))
            .build()
        var failed = false
        try {
            cancelling.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        } catch (e: IOException) { failed = true }
        assertTrue(failed)
        assertTrue(sleeps.isEmpty())
        assertEquals(0, server.requestCount)
    }
}
