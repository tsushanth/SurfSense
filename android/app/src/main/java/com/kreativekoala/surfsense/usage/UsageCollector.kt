package com.kreativekoala.surfsense.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.TimeZone

class UsageCollector(private val context: Context) {

    /** Foreground seconds per package from local midnight until [nowMs]. */
    fun todaySeconds(nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Map<String, Int> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = Day.startOfDayMs(nowMs, zone)
        // Query from six hours before local midnight so a session spanning midnight still contributes its
        // post-midnight part; ForegroundTime clamps everything to [start, nowMs].
        val events = manager.queryEvents(start - 6 * 60 * 60 * 1000L, nowMs) ?: return emptyMap()

        val list = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            list.add(UsageEvent(event.packageName, event.eventType, event.timeStamp, event.className ?: ""))
        }

        val excluded = systemSurfaces()
        return ForegroundTime.secondsByPackage(list, start, nowMs).filterKeys { it !in excluded }
    }

    /** Launchers and system UI are "foreground" all day but are not something the user spends time in. */
    private fun systemSurfaces(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = context.packageManager
            .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
        return (launchers + "com.android.systemui" + "android").toSet()
    }
}
