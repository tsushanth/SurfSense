package com.kreativekoala.surfsense.viewmodel

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kreativekoala.surfsense.ApiClient
import com.kreativekoala.surfsense.Config
import com.kreativekoala.surfsense.InitiateLinkingRequest
import com.kreativekoala.surfsense.LocalStorage
import com.kreativekoala.surfsense.RegisterClientRequest
import com.kreativekoala.surfsense.UsageSummaryWorker
import com.kreativekoala.surfsense.repository.UsageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

data class CategoryUsageUi(
    val category: String,
    val seconds: Int,
    val color: String
)

data class DeviceUsageUi(
    val totalTime: String,
    val categories: List<CategoryUsageUi>,
    val mostUsedCategory: String,
    val mostUsedTime: String
)

data class LinkedDeviceUi(
    val id: String,
    val name: String,
    val type: String,
    val usage: DeviceUsageUi
)

data class MainUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val thisDeviceUsage: DeviceUsageUi? = null,
    val linkedDevices: List<LinkedDeviceUi> = emptyList(),
    val unifiedUsage: DeviceUsageUi? = null,
    val isLinking: Boolean = false,
    val linkingSuccess: Boolean = false,
    val linkingError: String? = null,
    val generatedCode: String? = null,
    val isGeneratingCode: Boolean = false,
    val deviceId: String = "",
    val deviceName: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UsageRepository.getInstance(application)
    private val localStorage = LocalStorage.getInstance(application)
    private val apiService by lazy { ApiClient.create() }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun initialize() {
        val context = getApplication<Application>()
        val deviceId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        )
        val deviceName = Settings.Global.getString(context.contentResolver, "device_name")
            ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

        _uiState.value = _uiState.value.copy(deviceId = deviceId, deviceName = deviceName)

        viewModelScope.launch {
            registerDevice()
            refreshData()
        }
    }

    private suspend fun registerDevice() {
        try {
            val state = _uiState.value
            val response = apiService.registerClient(
                RegisterClientRequest(
                    clientId = state.deviceId,
                    clientType = Config.CLIENT_TYPE,
                    clientName = state.deviceName
                )
            )
            if (response.isSuccessful) {
                response.body()?.apiKey?.let { key ->
                    ApiClient.saveApiKey(getApplication(), key)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Device registration failed")
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val deviceId = _uiState.value.deviceId
                val today = repository.getTodayDate()
                val allUsage = mutableMapOf<String, Map<String, Int>>()
                val devices = mutableListOf<LinkedDeviceUi>()

                // Fetch this device usage
                val selfResult = repository.getSummaryHistory(deviceId, today)
                val selfSummary = selfResult.getOrNull()
                    ?.firstOrNull { it.timestamp.startsWith(today) }
                    ?.summary

                val thisDeviceUsage = selfSummary?.takeIf { it.isNotEmpty() }?.let {
                    allUsage[deviceId] = it
                    buildDeviceUsage(it)
                }

                // Fetch linked devices
                val linkedResult = repository.getLinkedClients(deviceId)
                val clients = linkedResult.getOrNull()?.data?.clients
                    ?.distinctBy { it.clientId }
                    ?.filter { it.clientId != deviceId }
                    ?: emptyList()

                for (client in clients) {
                    val summaryResult = repository.getSummaryHistory(client.clientId, today)
                    val summary = summaryResult.getOrNull()
                        ?.firstOrNull { it.timestamp.startsWith(today) }
                        ?.summary ?: emptyMap()

                    if (summary.isNotEmpty()) {
                        allUsage[client.clientId] = summary
                    }

                    devices.add(
                        LinkedDeviceUi(
                            id = client.clientId,
                            name = client.clientName,
                            type = client.clientType,
                            usage = buildDeviceUsage(summary)
                        )
                    )
                }

                // Build unified usage from linked devices only (exclude this device)
                val linkedUsage = allUsage.filterKeys { it != deviceId }
                val unifiedUsage = if (linkedUsage.isNotEmpty()) {
                    val merged = mutableMapOf<String, Int>()
                    linkedUsage.values.forEach { summary ->
                        summary.forEach { (cat, secs) ->
                            merged[cat] = (merged[cat] ?: 0) + secs
                        }
                    }
                    buildDeviceUsage(merged)
                } else null

                // Save aggregated usage to history for trends
                if (allUsage.isNotEmpty()) {
                    val aggregatedMinutes = mutableMapOf<String, Int>()
                    allUsage.values.forEach { summary ->
                        summary.forEach { (cat, secs) ->
                            aggregatedMinutes[cat] = (aggregatedMinutes[cat] ?: 0) + secs / 60
                        }
                    }
                    localStorage.saveUsageHistory(
                        LocalStorage.DailyUsage(
                            date = today,
                            totalMinutes = aggregatedMinutes.values.sum(),
                            byCategory = aggregatedMinutes
                        )
                    )
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    thisDeviceUsage = thisDeviceUsage,
                    linkedDevices = devices,
                    unifiedUsage = unifiedUsage
                )
            } catch (e: Exception) {
                Timber.e(e, "Error refreshing data")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load data"
                )
            }
        }
    }

    fun linkDevice(code: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLinking = true, linkingError = null, linkingSuccess = false
            )
            val result = repository.linkDevice(_uiState.value.deviceId, code)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isLinking = false, linkingSuccess = true)
                refreshData()
            } else {
                _uiState.value = _uiState.value.copy(
                    isLinking = false,
                    linkingError = result.exceptionOrNull()?.message ?: "Failed to link device"
                )
            }
        }
    }

    fun unlinkDevice(targetDeviceId: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.unlinkDevice(_uiState.value.deviceId, targetDeviceId)
            onComplete(result.isSuccess)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    linkedDevices = _uiState.value.linkedDevices.filter { it.id != targetDeviceId }
                )
            }
        }
    }

    fun generateLinkCode() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGeneratingCode = true, generatedCode = null)
            try {
                val response = apiService.initiateLinking(
                    InitiateLinkingRequest(_uiState.value.deviceId)
                )
                if (response.isSuccessful && response.body()?.success == true) {
                    _uiState.value = _uiState.value.copy(
                        isGeneratingCode = false,
                        generatedCode = response.body()?.code
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isGeneratingCode = false, error = "Failed to generate code"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isGeneratingCode = false, error = "Failed to generate code"
                )
            }
        }
    }

    fun clearLinkingState() {
        _uiState.value = _uiState.value.copy(
            linkingSuccess = false, linkingError = null, generatedCode = null
        )
    }

    fun syncNow() {
        val context = getApplication<Application>()
        val syncRequest = OneTimeWorkRequestBuilder<UsageSummaryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(syncRequest)

        // Observe the work and refresh when it completes
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            // Give the worker time to collect, categorize, and submit
            kotlinx.coroutines.delay(5000)
            refreshData()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun getUsageHistory(days: Int): List<LocalStorage.DailyUsage> {
        return localStorage.getUsageHistory(days)
    }

    fun resetAllData() {
        localStorage.clearAllCache()
        _uiState.value = MainUiState(
            deviceId = _uiState.value.deviceId,
            deviceName = _uiState.value.deviceName
        )
    }

    private fun buildDeviceUsage(summary: Map<String, Int>): DeviceUsageUi {
        val totalSeconds = summary.values.sum()
        val categories = summary.entries
            .sortedByDescending { it.value }
            .map { (cat, secs) ->
                CategoryUsageUi(category = cat, seconds = secs, color = categoryColor(cat))
            }
        val mostUsed = categories.firstOrNull()
        return DeviceUsageUi(
            totalTime = formatSeconds(totalSeconds),
            categories = categories,
            mostUsedCategory = mostUsed?.category ?: "None",
            mostUsedTime = mostUsed?.let { formatSeconds(it.seconds) } ?: "0m"
        )
    }

    companion object {
        fun formatSeconds(totalSeconds: Int): String {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            return when {
                hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                hours > 0 -> "${hours}h"
                else -> "${minutes}m"
            }
        }

        fun formatMinutes(minutes: Int): String {
            return when {
                minutes >= 60 -> {
                    val h = minutes / 60
                    val m = minutes % 60
                    if (m > 0) "${h}h ${m}m" else "${h}h"
                }
                else -> "${minutes}m"
            }
        }

        fun categoryColor(category: String): String {
            return when (category.lowercase()) {
                "social", "social media" -> "blue"
                "productivity", "work/productivity" -> "green"
                "entertainment" -> "purple"
                "news" -> "orange"
                "education" -> "cyan"
                "finance" -> "green"
                "health" -> "pink"
                "shopping" -> "yellow"
                "travel" -> "indigo"
                "food" -> "red"
                else -> "gray"
            }
        }
    }
}
