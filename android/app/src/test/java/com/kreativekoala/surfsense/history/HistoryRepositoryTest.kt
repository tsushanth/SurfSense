package com.kreativekoala.surfsense.history

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import com.kreativekoala.surfsense.net.ApiFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HistoryRepositoryTest {
    private class FakeCache : HistoryRepository.PastDayCache {
        val store = HashMap<String, Map<String, Int>>()
        override fun get(deviceId: String, day: String) = store["$deviceId|$day"]
        override fun put(deviceId: String, day: String, byCategory: Map<String, Int>) {
            store["$deviceId|$day"] = byCategory
        }
    }

    private val today = "2026-10-13"

    @Test fun dayMinusBoundaries() {
        assertEquals("2026-10-12", dayMinus("2026-10-13", 1))
        assertEquals("2026-09-30", dayMinus("2026-10-01", 1))
        assertEquals("2025-12-31", dayMinus("2026-01-01", 1))
        assertEquals("2028-02-29", dayMinus("2028-03-01", 1))
        assertEquals("2027-02-28", dayMinus("2027-03-01", 1))
        assertEquals("2026-10-13", dayMinus("2026-10-13", 0))
        assertEquals("2025-12-25", dayMinus("2026-01-01", 7))
    }

    @Test fun cachedOldDaysAvoidFetchButTodayAndYesterdayAlwaysFetched() = runBlocking {
        val cache = FakeCache()
        cache.put("d1", "2026-10-13", mapOf("Stale" to 1))
        cache.put("d1", "2026-10-12", mapOf("Stale" to 1))
        cache.put("d1", "2026-10-11", mapOf("News" to 60))
        val fetched = java.util.Collections.synchronizedList(ArrayList<String>())
        val repo = HistoryRepository({ _, day -> fetched.add(day); Result.success(mapOf("Fresh" to 5)) }, cache) { today }
        val (rows, failed) = repo.load(listOf("d1" to "Phone"), 3)
        assertEquals(0, failed)
        assertEquals(listOf("2026-10-12", "2026-10-13"), fetched.sorted())
        assertEquals(listOf("2026-10-11", "2026-10-12", "2026-10-13"), rows.map { it.date })
        assertEquals(mapOf("News" to 60), rows[0].byCategory)
        assertEquals(mapOf("Fresh" to 5), rows[1].byCategory)
        assertEquals("Phone", rows[0].deviceName)
    }

    @Test fun failureCountedOthersReturnAndFailedNotCached() = runBlocking {
        val cache = FakeCache()
        val repo = HistoryRepository(
            { _, day -> if (day == "2026-10-10") Result.failure(RuntimeException("x")) else Result.success(mapOf("A" to 1)) },
            cache,
        ) { today }
        val (rows, failed) = repo.load(listOf("d1" to "P"), 5)
        assertEquals(1, failed)
        assertEquals(4, rows.size)
        assertTrue(rows.none { it.date == "2026-10-10" })
        assertTrue(cache.get("d1", "2026-10-10") == null)
    }

    @Test fun thrownExceptionCountsAsFailure() = runBlocking {
        val repo = HistoryRepository({ _, _ -> throw IllegalStateException("boom") }, FakeCache()) { today }
        val (rows, failed) = repo.load(listOf("d1" to "P"), 2)
        assertEquals(2, failed) // two distinct days
        assertTrue(rows.isEmpty())
    }

    @Test fun emptyPastDaysCachedAsKnownEmptyTodayAndYesterdayNot() = runBlocking {
        val cache = FakeCache()
        var n = 0
        val repo = HistoryRepository(
            { _, _ -> n++; if (n % 2 == 0) Result.success(null) else Result.success(emptyMap()) },
            cache,
        ) { today }
        val (rows, failed) = repo.load(listOf("d1" to "P"), 4)
        assertEquals(0, failed)
        assertTrue(rows.isEmpty())
        assertEquals(setOf("d1|2026-10-10", "d1|2026-10-11"), cache.store.keys)
        assertTrue(cache.store.values.all { it.isEmpty() })
    }

    @Test fun knownEmptyDayIsNotRefetched() = runBlocking {
        val cache = FakeCache()
        cache.put("d1", "2026-10-10", emptyMap())
        cache.put("d1", "2026-10-11", mapOf("A" to 4))
        val fetched = java.util.Collections.synchronizedList(ArrayList<String>())
        val repo = HistoryRepository({ _, day -> fetched.add(day); Result.success(mapOf("B" to 1)) }, cache) { today }
        val (rows, failed) = repo.load(listOf("d1" to "P"), 4)
        assertEquals(0, failed)
        assertEquals(listOf("2026-10-12", "2026-10-13"), fetched.sorted())
        assertEquals(listOf("2026-10-11", "2026-10-12", "2026-10-13"), rows.map { it.date })
    }

    @Test fun rateLimitedFetchRetriedOnceThenSucceeds() = runBlocking {
        val calls = AtomicInteger(0)
        val repo = HistoryRepository(
            { _, _ -> if (calls.incrementAndGet() == 1) Result.failure(ApiFailure(429, null)) else Result.success(mapOf("A" to 1)) },
            FakeCache(),
            retryDelayMs = 1,
        ) { today }
        val load = repo.loadDetailed(listOf("d1" to "P"), 1)
        assertEquals(2, calls.get())
        assertEquals(0, load.failedDays)
        assertFalse(load.rateLimited)
        assertEquals(1, load.rows.size)
    }

    @Test fun rateLimitedTwiceCountsFailedAndFlagsRateLimit() = runBlocking {
        val calls = AtomicInteger(0)
        val repo = HistoryRepository(
            { _, _ -> calls.incrementAndGet(); Result.failure(ApiFailure(429, null)) },
            FakeCache(),
            retryDelayMs = 1,
        ) { today }
        val load = repo.loadDetailed(listOf("d1" to "P"), 1)
        assertEquals(2, calls.get()) // one retry only
        assertEquals(1, load.failedDays)
        assertTrue(load.rateLimited)
    }

    @Test fun otherFailuresAreNotRetried() = runBlocking {
        val calls = AtomicInteger(0)
        val repo = HistoryRepository(
            { _, _ -> calls.incrementAndGet(); Result.failure(ApiFailure(500, null)) },
            FakeCache(),
            retryDelayMs = 1,
        ) { today }
        val load = repo.loadDetailed(listOf("d1" to "P"), 1)
        assertEquals(1, calls.get())
        assertEquals(1, load.failedDays)
        assertFalse(load.rateLimited)
    }

    @Test fun failedDaysCountsDistinctDatesNotDeviceDays() = runBlocking {
        val repo = HistoryRepository(
            { _, day -> if (day == "2026-10-12") Result.failure(RuntimeException("x")) else Result.success(mapOf("A" to 1)) },
            FakeCache(),
        ) { today }
        val (rows, failed) = repo.load(listOf("a" to "A", "b" to "B", "c" to "C"), 3)
        assertEquals(1, failed)
        assertEquals(6, rows.size)
    }

    @Test fun successfulOldDayIsCached() = runBlocking {
        val cache = FakeCache()
        val repo = HistoryRepository({ _, _ -> Result.success(mapOf("A" to 3)) }, cache) { today }
        repo.load(listOf("d1" to "P"), 4)
        assertEquals(mapOf("A" to 3), cache.get("d1", "2026-10-10"))
        assertEquals(mapOf("A" to 3), cache.get("d1", "2026-10-11"))
        assertTrue(cache.get("d1", "2026-10-12") == null)
        assertTrue(cache.get("d1", "2026-10-13") == null)
    }

    @Test fun rowsOrderedByDeviceThenDate() = runBlocking {
        val repo = HistoryRepository({ _, _ -> Result.success(mapOf("A" to 1)) }, FakeCache()) { today }
        val (rows, _) = repo.load(listOf("z" to "Zed", "a" to "Aye"), 2)
        assertEquals(
            listOf("z|2026-10-12", "z|2026-10-13", "a|2026-10-12", "a|2026-10-13"),
            rows.map { "${it.deviceId}|${it.date}" },
        )
    }

    @Test fun inFlightFetchesCappedAtThree() = runBlocking {
        val cur = AtomicInteger(0)
        val max = AtomicInteger(0)
        val repo = HistoryRepository({ _, _ ->
            val c = cur.incrementAndGet()
            max.updateAndGet { maxOf(it, c) }
            delay(20)
            cur.decrementAndGet()
            Result.success(mapOf("A" to 1))
        }, FakeCache()) { today }
        val (rows, failed) = repo.load(listOf("a" to "A", "b" to "B", "c" to "C"), 7)
        assertEquals(21, rows.size)
        assertEquals(0, failed)
        assertEquals(3, max.get())
    }
}
