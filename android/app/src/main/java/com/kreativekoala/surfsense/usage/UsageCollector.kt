package com.kreativekoala.surfsense.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.TimeZone

/** One app's foreground time today, with its user-facing name (the package name when it can't be read). */
data class AppUsage(val packageName: String, val label: String, val seconds: Int)

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
            toUsageEvent(event.packageName, event.eventType, event.timeStamp, event.className)?.let(list::add)
        }

        val excluded = systemSurfaces()
        return ForegroundTime.secondsByPackage(list, start, nowMs).filterKeys { it !in excluded }
    }

    /** [todaySeconds] joined with app labels, longest first. */
    fun todayApps(nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): List<AppUsage> {
        val pm = context.packageManager
        return todaySeconds(nowMs, zone)
            .map { (pkg, seconds) -> AppUsage(pkg, labelFor(pm, pkg), seconds) }
            .sortedWith(compareByDescending<AppUsage> { it.seconds }.thenBy { it.label })
    }

    /** Falls back to the package name when the app is uninstalled or not visible to us. */
    private fun labelFor(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().ifBlank { pkg }
    } catch (e: Exception) {
        pkg
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
