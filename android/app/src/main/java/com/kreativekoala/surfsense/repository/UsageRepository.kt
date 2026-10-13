package com.kreativekoala.surfsense.repository

import android.content.Context
import timber.log.Timber
import com.kreativekoala.surfsense.ApiClient
import com.kreativekoala.surfsense.ApiService
import com.kreativekoala.surfsense.ClientInfoResponse
import com.kreativekoala.surfsense.LinkDeviceRequest
import com.kreativekoala.surfsense.LinkedClientsResponse
import com.kreativekoala.surfsense.SummaryEntry
import com.kreativekoala.surfsense.UnlinkDeviceRequest
import com.kreativekoala.surfsense.net.toFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kreativekoala.surfsense.usage.Day
import java.util.TimeZone
/**
 * Repository for handling all usage data and API operations
 */
class UsageRepository(private val context: Context) {
    private val apiService: ApiService = ApiClient.create()

    // MARK: - Summary History

    suspend fun getSummaryHistory(userId: String, day: String = getTodayDate()): Result<List<SummaryEntry>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getSummaryHistory(userId, day)
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(response.toFailure())
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
                Result.failure(response.toFailure())
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
                Result.failure(response.toFailure())
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
                Result.failure(response.toFailure())
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
                Result.failure(response.toFailure())
            }
        } catch (e: Exception) {
            Timber.e(e, "Error unlinking device")
            Result.failure(e)
        }
    }

    // MARK: - Helpers

    /**
     * Today's local date as yyyy-MM-dd. Built per call with the current default zone (thread-safe, follows a
     * time-zone change), the same way UsageSummaryWorker names the day it submits.
     */
    fun getTodayDate(): String = Day.dayString(System.currentTimeMillis(), TimeZone.getDefault())

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
