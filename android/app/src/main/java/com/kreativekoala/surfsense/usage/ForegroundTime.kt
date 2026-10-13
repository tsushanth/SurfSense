package com.kreativekoala.surfsense.usage

data class UsageEvent(val packageName: String, val type: Int, val timeMs: Long, val className: String = "")

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
        // Events are per activity: track the set of open activities per package, and the session start.
        val openActivities = HashMap<String, MutableSet<String>>()
        val sessionStart = HashMap<String, Long>()
        val totalMs = HashMap<String, Long>()

        fun closeSession(pkg: String, at: Long) {
            val since = sessionStart.remove(pkg) ?: return
            val from = maxOf(since, startMs)
            val to = minOf(at, endMs)
            if (to > from) totalMs[pkg] = (totalMs[pkg] ?: 0L) + (to - from)
        }

        fun closeAll(at: Long) {
            sessionStart.keys.toList().forEach { closeSession(it, at) }
            openActivities.clear()
        }

        for (e in events.sortedBy { it.timeMs }) {
            when (e.type) {
                ACTIVITY_RESUMED -> {
                    val open = openActivities.getOrPut(e.packageName) { HashSet() }
                    if (open.isEmpty()) sessionStart[e.packageName] = e.timeMs
                    open.add(e.className)
                }
                ACTIVITY_PAUSED, ACTIVITY_STOPPED -> {
                    val open = openActivities[e.packageName]
                    if (open != null && open.isNotEmpty()) {
                        open.remove(e.className)
                        if (open.isEmpty()) closeSession(e.packageName, e.timeMs)
                    }
                }
                SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN -> closeAll(e.timeMs)
            }
        }
        closeAll(endMs)

        return totalMs.mapValues { (it.value / 1000).toInt() }.filterValues { it > 0 }
    }
}
