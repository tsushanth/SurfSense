package com.kreativekoala.surfsense

import android.app.usage.UsageStatsManager
import android.content.Context
import android.provider.Settings
import timber.log.Timber
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class UsageSummaryWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val apiService by lazy { ApiClient.create() }
    private val deviceId by lazy {
        Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }

    override suspend fun doWork(): Result {
        return try {
            // 1. Get raw usage stats for today (returns Map<String, Long>)
            val usageData = getUsageStats()
            if (usageData.isEmpty()) return Result.success()

            // 2. Convert to seconds (now Map<String, Int>)
            val formattedData = usageData.mapValues { (_, ms) -> (ms / 1000).toInt() }
            val packageNames = formattedData.keys.toList()

            // 3. Get category mapping
            val categoryMap = getCategoryMapping(packageNames) ?: return Result.retry()

            // 4. Aggregate by category
            val categorySummary = aggregateByCategory(formattedData, categoryMap)

            // 5. Submit report directly (queryUsageStats INTERVAL_DAILY returns
            //    the full day's totals, so we replace rather than merge to avoid
            //    double-counting on each worker run)
            submitUsageReport(categorySummary.toMutableMap())

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Failed to process usage data")
            Result.retry()
        }
    }

    private fun getUsageStats(): Map<String, Long> {
        val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE)
                as UsageStatsManager

        val endTime = System.currentTimeMillis()
        val startTime = endTime - 24 * 60 * 60 * 1000 // 24 hours — ensures today's daily bucket is included

        return usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )
            ?.filter { it.totalTimeInForeground > 0 }
            ?.associate { it.packageName to it.totalTimeInForeground }
            ?: emptyMap()
    }

    private suspend fun getCategoryMapping(packages: List<String>): Map<String, String>? {
        return apiService.getAppCategoryMapping(AppCategoryRequest(packages))
            .takeIf { it.isSuccessful }
            ?.body()
    }

    private fun aggregateByCategory(
        usageData: Map<String, Int>,
        categoryMap: Map<String, String>
    ): Map<String, Int> {
        return usageData.map { (pkg, seconds) ->
            val category = categoryMap[pkg] ?: "Uncategorized"
            category to seconds
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.sum() }
    }

    private suspend fun submitUsageReport(summary: MutableMap<String, Int>): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        return apiService.submitUsageReport(UsageReportRequest(deviceId, today, summary))
            .isSuccessful
    }
}
