package com.kreativekoala.surfsense.usage

data class UsageEvent(val packageName: String, val type: Int, val timeMs: Long)

/**
 * Foreground time per package from UsageEvents, for the window [startMs, endMs].
 * Event type values are stable platform constants (see android.app.usage.UsageEvents.Event).
 */
object ForegroundTime {
    const val ACTIVITY_RESUMED = 1          // MOVE_TO_FOREGROUND
    const val ACTIVITY_PAUSED = 2           // MOVE_TO_BACKGROUND
    const val SCREEN_NON_INTERACTIVE = 16
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26

    fun secondsByPackage(events: List<UsageEvent>, startMs: Long, endMs: Long): Map<String, Int> {
        val openSince = HashMap<String, Long>()
        val totalMs = HashMap<String, Long>()

        fun close(pkg: String, at: Long) {
            val since = openSince.remove(pkg) ?: return
            val from = maxOf(since, startMs)
            val to = minOf(at, endMs)
            if (to > from) totalMs[pkg] = (totalMs[pkg] ?: 0L) + (to - from)
        }

        for (e in events.sortedBy { it.timeMs }) {
            when (e.type) {
                ACTIVITY_RESUMED -> if (!openSince.containsKey(e.packageName)) openSince[e.packageName] = e.timeMs
                ACTIVITY_PAUSED, ACTIVITY_STOPPED -> close(e.packageName, e.timeMs)
                SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN -> openSince.keys.toList().forEach { close(it, e.timeMs) }
            }
        }
        openSince.keys.toList().forEach { close(it, endMs) }

        return totalMs.mapValues { (it.value / 1000).toInt() }.filterValues { it > 0 }
    }
}
