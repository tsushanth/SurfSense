package com.kreativekoala.surfsense.repository

import android.app.usage.UsageStatsManager
import android.content.Context
import timber.log.Timber
import com.kreativekoala.surfsense.ApiClient
import com.kreativekoala.surfsense.ApiService
import com.kreativekoala.surfsense.AppCategoryRequest
import com.kreativekoala.surfsense.ClientInfoResponse
import com.kreativekoala.surfsense.LinkDeviceRequest
import com.kreativekoala.surfsense.LinkedClientsResponse
import com.kreativekoala.surfsense.SummaryEntry
import com.kreativekoala.surfsense.UnlinkDeviceRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/**
 * Repository for handling all usage data and API operations
 */
class UsageRepository(private val context: Context) {
    private val apiService: ApiService = ApiClient.create()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // MARK: - Usage Stats

    suspend fun getUsageStats(): Result<Map<String, Long>> = withContext(Dispatchers.IO) {
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 24 * 60 * 60 * 1000 // Last 24 hours

            val usageStatsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                startTime,
                endTime
            )

            val usageMap = HashMap<String, Long>()
            usageStatsList?.forEach { stat ->
                val time = stat.totalTimeInForeground
                if (time > 0) {
                    usageMap[stat.packageName] = (usageMap[stat.packageName] ?: 0) + time
                }
            }

            Result.success(usageMap)
        } catch (e: Exception) {
            Timber.e(e, "Error getting usage stats")
            Result.failure(e)
        }
    }

    // MARK: - Category Mapping

    suspend fun getCategoryMapping(packages: List<String>): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getAppCategoryMapping(AppCategoryRequest(packages = packages))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get category mapping: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting category mapping")
            Result.failure(e)
        }
    }

    // MARK: - Summary History

    suspend fun getSummaryHistory(userId: String, day: String = getTodayDate()): Result<List<SummaryEntry>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getSummaryHistory(userId, day)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception("Failed to get summary history: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting summary history")
            Result.failure(e)
        }
    }

    // MARK: - Client Operations

    suspend fun getClientInfo(clientId: String, clientName: String): Result<ClientInfoResponse> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getClientInfo(clientId, clientName)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get client info: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting client info")
            Result.failure(e)
        }
    }

    suspend fun getLinkedClients(clientId: String): Result<LinkedClientsResponse> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getLinkedClients(clientId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get linked clients: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting linked clients")
            Result.failure(e)
        }
    }

    // MARK: - Device Linking

    suspend fun linkDevice(clientId: String, code: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.linkDevice(LinkDeviceRequest(clientId = clientId, code = code))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Failed to link device"))
                }
            } else {
                Result.failure(Exception("Failed to link device: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error linking device")
            Result.failure(e)
        }
    }

    suspend fun unlinkDevice(clientIdA: String, clientIdB: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.unlinkDevice(UnlinkDeviceRequest(clientIdA, clientIdB))
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Failed to unlink device: ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error unlinking device")
            Result.failure(e)
        }
    }

    // MARK: - Helpers

    fun getTodayDate(): String = dateFormat.format(Date())

    companion object {
        @Volatile
        private var instance: UsageRepository? = null

        fun getInstance(context: Context): UsageRepository {
            return instance ?: synchronized(this) {
                instance ?: UsageRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
