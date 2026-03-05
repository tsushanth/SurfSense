package com.kreativekoala.surfsense

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Local storage manager for caching data and tracking usage history
 */
class LocalStorage private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "surfsense_local_storage"
        private const val KEY_CACHED_DEVICES = "cached_linked_devices"
        private const val KEY_CACHED_SUMMARIES = "cached_summaries"
        private const val KEY_LAST_FETCH_TIME = "last_fetch_time"
        private const val KEY_USAGE_HISTORY = "usage_history"
        private const val CACHE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

        @Volatile
        private var instance: LocalStorage? = null

        fun getInstance(context: Context): LocalStorage {
            return instance ?: synchronized(this) {
                instance ?: LocalStorage(context.applicationContext).also { instance = it }
            }
        }
    }

    // MARK: - Linked Devices Cache

    fun cacheLinkedDevices(devices: List<ClientInfo>) {
        val json = gson.toJson(devices)
        prefs.edit()
            .putString(KEY_CACHED_DEVICES, json)
            .putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getCachedLinkedDevices(): List<ClientInfo>? {
        val json = prefs.getString(KEY_CACHED_DEVICES, null) ?: return null
        val type = object : TypeToken<List<ClientInfo>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    fun clearCachedDevices() {
        prefs.edit().remove(KEY_CACHED_DEVICES).apply()
    }

    // MARK: - Usage Summary Cache

    fun cacheUsageSummary(summary: Map<String, Int>, deviceId: String, date: String) {
        val allSummaries = getAllCachedSummaries().toMutableMap()
        val key = "${deviceId}_$date"
        allSummaries[key] = summary
        prefs.edit().putString(KEY_CACHED_SUMMARIES, gson.toJson(allSummaries)).apply()
    }

    fun getCachedUsageSummary(deviceId: String, date: String): Map<String, Int>? {
        val allSummaries = getAllCachedSummaries()
        val key = "${deviceId}_$date"
        return allSummaries[key]
    }

    private fun getAllCachedSummaries(): Map<String, Map<String, Int>> {
        val json = prefs.getString(KEY_CACHED_SUMMARIES, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, Map<String, Int>>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // MARK: - Usage History (for trends)

    data class DailyUsage(
        val date: String,
        val totalMinutes: Int,
        val byCategory: Map<String, Int>
    )

    fun saveUsageHistory(usage: DailyUsage) {
        val history = getUsageHistory().toMutableList()

        // Remove old entry for same date if exists
        history.removeAll { it.date == usage.date }
        history.add(usage)

        // Keep only last 30 days, sorted by date descending
        val trimmedHistory = history
            .sortedByDescending { it.date }
            .take(30)

        prefs.edit().putString(KEY_USAGE_HISTORY, gson.toJson(trimmedHistory)).apply()
    }

    fun getUsageHistory(): List<DailyUsage> {
        val json = prefs.getString(KEY_USAGE_HISTORY, null) ?: return emptyList()
        val type = object : TypeToken<List<DailyUsage>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getUsageHistory(days: Int): List<DailyUsage> {
        return getUsageHistory().take(days)
    }

    // MARK: - Last Fetch Time

    fun getLastFetchTime(): Long {
        return prefs.getLong(KEY_LAST_FETCH_TIME, 0)
    }

    fun shouldRefresh(): Boolean {
        val lastFetch = getLastFetchTime()
        return System.currentTimeMillis() - lastFetch > CACHE_TIMEOUT_MS
    }

    // MARK: - Clear All Cache

    fun clearAllCache() {
        prefs.edit()
            .remove(KEY_CACHED_DEVICES)
            .remove(KEY_CACHED_SUMMARIES)
            .remove(KEY_LAST_FETCH_TIME)
            .remove(KEY_USAGE_HISTORY)
            .apply()
    }
}
