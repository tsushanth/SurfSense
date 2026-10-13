package com.kreativekoala.surfsense.history

import com.kreativekoala.surfsense.net.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class DeviceDay(
    val deviceId: String,
    val deviceName: String,
    val date: String,
    val byCategory: Map<String, Int>,
)

/** Result of a history load. [failedDays] counts distinct dates with at least one failed fetch. */
data class HistoryLoad(
    val rows: List<DeviceDay>,
    val failedDays: Int,
    /** True when at least one fetch still failed with HTTP 429 after its single retry. */
    val rateLimited: Boolean,
)

class HistoryRepository(
    private val fetchDay: suspend (deviceId: String, day: String) -> Result<Map<String, Int>?>,
    private val cache: PastDayCache,
    private val retryDelayMs: Long = RATE_LIMIT_RETRY_DELAY_MS,
    private val today: () -> String,
) {
    interface PastDayCache {
        /** null = not cached; an empty map = known to have no data (cached negative result). */
        fun get(deviceId: String, day: String): Map<String, Int>?
        /** An empty [byCategory] records the day as known empty. */
        fun put(deviceId: String, day: String, byCategory: Map<String, Int>)
    }

    private sealed interface Outcome {
        data class Row(val row: DeviceDay) : Outcome
        object Empty : Outcome
        data class Failed(val day: String, val rateLimited: Boolean) : Outcome
    }

    /**
     * Last [days] days ending today, for each device. Days before yesterday are final (Android and the extension
     * only ever submit the current day), so they are served from cache when present and cached after any
     * successful fetch, including an empty one. Today and yesterday are always re-fetched.
     * At most [MAX_IN_FLIGHT] fetches in flight (the backend allows 100 requests a minute per IP); an HTTP 429 is
     * retried once after [retryDelayMs], then counted as failed.
     * Returns (rows by device order then date ascending, number of distinct days with at least one failed fetch).
     */
    suspend fun load(devices: List<Pair<String, String>>, days: Int): Pair<List<DeviceDay>, Int> =
        loadDetailed(devices, days).let { it.rows to it.failedDays }

    /** Same as [load], and also says whether failures were caused by the backend's rate limit. */
    suspend fun loadDetailed(devices: List<Pair<String, String>>, days: Int): HistoryLoad {
        val todayStr = today()
        val yesterday = dayMinus(todayStr, 1)
        val dayList = (days - 1 downTo 0).map { dayMinus(todayStr, it) }
        val gate = Semaphore(MAX_IN_FLIGHT)
        val outcomes = coroutineScope {
            devices.flatMap { (id, name) ->
                dayList.map { day ->
                    async {
                        val cacheable = day < yesterday // yyyy-MM-dd sorts lexicographically
                        val hit = if (cacheable) cache.get(id, day) else null
                        if (hit != null) {
                            return@async if (hit.isEmpty()) Outcome.Empty else Outcome.Row(DeviceDay(id, name, day, hit))
                        }
                        var result = fetchOnce(gate, id, day)
                        if (result.isRateLimited()) {
                            delay(retryDelayMs) // outside the gate, so other fetches are not held up by the wait
                            result = fetchOnce(gate, id, day)
                        }
                        val map = result.getOrElse { return@async Outcome.Failed(day, result.isRateLimited()) }
                        if (map.isNullOrEmpty()) {
                            if (cacheable) cache.put(id, day, emptyMap())
                            return@async Outcome.Empty
                        }
                        if (cacheable) cache.put(id, day, map)
                        Outcome.Row(DeviceDay(id, name, day, map))
                    }
                }
            }.awaitAll()
        }
        val failed = outcomes.filterIsInstance<Outcome.Failed>()
        return HistoryLoad(
            rows = outcomes.filterIsInstance<Outcome.Row>().map { it.row },
            failedDays = failed.map { it.day }.distinct().size,
            rateLimited = failed.any { it.rateLimited },
        )
    }

    private suspend fun fetchOnce(gate: Semaphore, id: String, day: String): Result<Map<String, Int>?> = try {
        gate.withPermit { fetchDay(id, day) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun Result<*>.isRateLimited(): Boolean = (exceptionOrNull() as? ApiFailure)?.code == 429

    companion object {
        const val MAX_IN_FLIGHT = 3
        const val RATE_LIMIT_RETRY_DELAY_MS = 2_000L
    }
}
