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
