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
