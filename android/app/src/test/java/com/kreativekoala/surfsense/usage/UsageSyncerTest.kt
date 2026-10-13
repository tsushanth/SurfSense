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
        applyOverrides: (Map<String, String>, Collection<String>) -> Map<String, String> = { m, _ -> m },
    ) {
        var submitted: Pair<String, Map<String, Int>>? = null
        var collected = 0
        var submitCalls = 0
        val syncer = UsageSyncer(
            clock = { 1_000L },
            collect = { collected++; usage },
            mapper = CategoryMapper { mapping(it) },
            submit = { day, summary -> submitCalls++; submitted = day to summary; submitReply() },
            ensureRegistered = { registered },
            dayOf = { "2026-10-12" },
            applyOverrides = applyOverrides,
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

    @Test fun usesOneInstantForCollectionAndDay() = runBlocking {
        var tick = 0L
        var collectedAt = -1L
        var dayAt = -1L
        val syncer = UsageSyncer(
            clock = { ++tick },
            collect = { collectedAt = it; mapOf("com.a" to 10) },
            mapper = CategoryMapper { Response.success(mapOf("com.a" to "News")) },
            submit = { _, _ -> Response.success(Unit) },
            ensureRegistered = { true },
            dayOf = { dayAt = it; "d" },
        )
        assertEquals(UsageSyncer.Outcome.SUCCESS, syncer.run())
        assertEquals(1L, collectedAt)
        assertEquals(collectedAt, dayAt)
        assertEquals(1L, tick)
    }

    @Test fun nothingIsSubmittedWhenMappingFails() = runBlocking {
        for (code in listOf(503, 429, 400)) {
            val f = Fixture(mapping = { Response.error(code, "".toResponseBody(null)) })
            f.syncer.run()
            assertEquals(0, f.submitCalls)
        }
    }

    @Test fun mapping429Retries() = runBlocking {
        val f = Fixture(mapping = { Response.error(429, "".toResponseBody(null)) })
        assertEquals(UsageSyncer.Outcome.RETRY, f.syncer.run())
    }

    @Test fun overrideWinsOverServerMapping() = runBlocking {
        val f = Fixture(
            usage = mapOf("com.x" to 40, "com.b" to 10),
            mapping = { Response.success(mapOf("com.x" to "Social Media", "com.b" to "Food")) },
            applyOverrides = { m, _ -> m + ("com.x" to "News") },
        )
        assertEquals(UsageSyncer.Outcome.SUCCESS, f.syncer.run())
        assertEquals("2026-10-12" to mapOf("News" to 40, "Food" to 10), f.submitted)
    }
}
