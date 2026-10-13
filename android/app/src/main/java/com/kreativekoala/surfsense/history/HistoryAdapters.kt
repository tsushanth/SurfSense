package com.kreativekoala.surfsense.history

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kreativekoala.surfsense.repository.UsageRepository

/** Maps the first summary entry of /get-summary-history; empty list or empty summary means no data (null). */
fun fromUsageRepository(repo: UsageRepository): suspend (String, String) -> Result<Map<String, Int>?> =
    { deviceId, day ->
        repo.getSummaryHistory(deviceId, day).map { list ->
            list.firstOrNull()?.summary?.takeIf { it.isNotEmpty() }
        }
    }

/** SharedPreferences-backed cache, key "deviceId|day", trimmed to the newest 40 distinct days on put. */
class PrefsPastDayCache(context: Context) : HistoryRepository.PastDayCache {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("surfsense_past_days", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<Map<String, Int>>() {}.type

    @Synchronized
    override fun get(deviceId: String, day: String): Map<String, Int>? {
        val json = prefs.getString("$deviceId|$day", null) ?: return null
        return try {
            gson.fromJson<Map<String, Int>>(json, type)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    override fun put(deviceId: String, day: String, byCategory: Map<String, Int>) {
        val editor = prefs.edit().putString("$deviceId|$day", gson.toJson(byCategory))
        val keep = (prefs.all.keys + "$deviceId|$day")
            .mapNotNull { it.substringAfterLast('|', "").takeIf { d -> d.isNotEmpty() } }
            .distinct().sortedDescending().take(MAX_DAYS).toSet()
        prefs.all.keys.forEach { k ->
            if (k.substringAfterLast('|', "") !in keep) editor.remove(k)
        }
        editor.apply()
    }

    /** Deletes every cached day (used by "Reset all data"). */
    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object { const val MAX_DAYS = 40 }
}
