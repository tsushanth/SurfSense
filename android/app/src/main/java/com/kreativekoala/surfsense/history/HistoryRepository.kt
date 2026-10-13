package com.kreativekoala.surfsense.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class DeviceDay(
    val deviceId: String,
    val deviceName: String,
    val date: String,
    val byCategory: Map<String, Int>,
)

class HistoryRepository(
    private val fetchDay: suspend (deviceId: String, day: String) -> Result<Map<String, Int>?>,
    private val cache: PastDayCache,
    private val today: () -> String,
) {
    interface PastDayCache {
        fun get(deviceId: String, day: String): Map<String, Int>?
        fun put(deviceId: String, day: String, byCategory: Map<String, Int>)
    }

    private sealed interface Outcome {
        data class Row(val row: DeviceDay) : Outcome
        object Empty : Outcome
        object Failed : Outcome
    }

    /**
     * Last [days] days ending today, for each device. Days before yesterday are served from cache when present
     * and cached after a successful non-empty fetch; today and yesterday are always re-fetched.
     * At most 4 fetches in flight. Returns (rows by device order then date ascending, failedCount).
     */
    suspend fun load(devices: List<Pair<String, String>>, days: Int): Pair<List<DeviceDay>, Int> {
        val todayStr = today()
        val yesterday = dayMinus(todayStr, 1)
        val dayList = (days - 1 downTo 0).map { dayMinus(todayStr, it) }
        val gate = Semaphore(4)
        val outcomes = coroutineScope {
            devices.flatMap { (id, name) ->
                dayList.map { day ->
                    async {
                        val cacheable = day < yesterday // yyyy-MM-dd sorts lexicographically
                        val hit = if (cacheable) cache.get(id, day) else null
                        if (hit != null) return@async Outcome.Row(DeviceDay(id, name, day, hit))
                        val result = try {
                            gate.withPermit { fetchDay(id, day) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                        val map = result.getOrElse { return@async Outcome.Failed }
                        if (map.isNullOrEmpty()) return@async Outcome.Empty
                        if (cacheable) cache.put(id, day, map)
                        Outcome.Row(DeviceDay(id, name, day, map))
                    }
                }
            }.awaitAll()
        }
        return outcomes.filterIsInstance<Outcome.Row>().map { it.row } to outcomes.count { it is Outcome.Failed }
    }
}
