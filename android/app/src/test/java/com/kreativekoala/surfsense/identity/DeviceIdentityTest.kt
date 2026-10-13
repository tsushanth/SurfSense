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
