package com.kreativekoala.surfsense.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kreativekoala.surfsense.ApiClient
import com.kreativekoala.surfsense.AppCategoryRequest
import com.kreativekoala.surfsense.Config
import com.kreativekoala.surfsense.InitiateLinkingRequest
import com.kreativekoala.surfsense.LocalStorage
import com.kreativekoala.surfsense.UsageSummaryWorker
import com.kreativekoala.surfsense.history.HistoryRepository
import com.kreativekoala.surfsense.history.PrefsPastDayCache
import com.kreativekoala.surfsense.history.fromUsageRepository
import com.kreativekoala.surfsense.identity.Identity
import com.kreativekoala.surfsense.insights.CsvExport
import com.kreativekoala.surfsense.insights.DeviceToday
import com.kreativekoala.surfsense.insights.ExportRows
import com.kreativekoala.surfsense.insights.TodayModel
import com.kreativekoala.surfsense.insights.WeekModel
import com.kreativekoala.surfsense.insights.WeekView
import com.kreativekoala.surfsense.net.ErrorMessages
import com.kreativekoala.surfsense.repository.UsageRepository
import com.kreativekoala.surfsense.usage.AppUsage
import com.kreativekoala.surfsense.usage.CategoryMapper
import com.kreativekoala.surfsense.usage.CategoryOverrides
import com.kreativekoala.surfsense.usage.UsageAccess
import com.kreativekoala.surfsense.usage.UsageCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/** What the last export attempt ended with; the UI turns each into a sentence. */
enum class ExportOutcome { Saved, NothingToExport, LoadFailed, SaveFailed }

/**
 * Settings export. [csv] and [fileName] are set once the data is ready; [pickerRequested] asks the UI to open the
 * system "save as" screen once, and the file is written when the user picks a place.
 */
data class ExportState(
    val preparing: Boolean = false,
    val csv: String? = null,
    val fileName: String? = null,
    val pickerRequested: Boolean = false,
    val outcome: ExportOutcome? = null,
)

/** Week screen state. [view] stays from the last successful load while a reload runs. */
data class WeekState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val view: WeekView? = null,
    /** Device-days that failed to load, capped at the 14 days in view. */
    val failedDays: Int = 0,
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
    val deviceName: String = "",
    /** This device first, then linked devices; devices that have not synced today have empty maps. */
    val todayByDevice: List<DeviceToday> = emptyList(),
    /** True once the first refresh finished (with or without errors), so the UI can tell "loading" from "empty". */
    val todayLoaded: Boolean = false,
    /** null = All devices, else a device id from [todayByDevice]. */
    val scope: String? = null,
    val week: WeekState = WeekState(),
    /** Top apps on this phone today, longest first. */
    val appsToday: List<AppUsage> = emptyList(),
    /** Server category per package; a package missing here has an unknown category (lookup failed or pending). */
    val appCategories: Map<String, String> = emptyMap(),
    val categoryOverrides: Map<String, String> = emptyMap(),
    val usageAccessGranted: Boolean = false,
    val export: ExportState = ExportState(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UsageRepository.getInstance(application)
    private val localStorage = LocalStorage.getInstance(application)
    private val apiService by lazy { ApiClient.create() }
    private val overrides by lazy { CategoryOverrides(CategoryOverrides.SharedPrefsStore(application)) }
    private val pastDays by lazy { PrefsPastDayCache(application) }
    private val history by lazy {
        HistoryRepository(fromUsageRepository(repository), pastDays) { repository.getTodayDate() }
    }
    private var weekJob: Job? = null
    private var appsJob: Job? = null
    private var exportJob: Job? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun initialize() {
        val context = getApplication<Application>()
        val deviceId = Identity.identity().clientId
        val deviceName = Identity.deviceName(context)

        _uiState.value = _uiState.value.copy(deviceId = deviceId, deviceName = deviceName)

        viewModelScope.launch {
            registerDevice()
            // Registration may have replaced a burned id; use the current one.
            _uiState.value = _uiState.value.copy(deviceId = Identity.identity().clientId)
            refreshData()
        }
    }

    private suspend fun registerDevice() {
        try {
            Identity.registrar().ensureRegistered()
        } catch (e: Exception) {
            Timber.e(e, "Device registration failed")
        }
    }

    fun refreshData(extraError: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val deviceId = Identity.identity().clientId
                val today = repository.getTodayDate()
                val allUsage = mutableMapOf<String, Map<String, Int>>()
                val devices = mutableListOf<LinkedDeviceUi>()

                // Fetch this device usage
                val selfResult = repository.getSummaryHistory(deviceId, today)
                val selfSummary = selfResult.getOrNull()
                    ?.firstOrNull { it.timestamp.startsWith(today) }
                    ?.summary

                val selfToday = DeviceToday(
                    id = deviceId,
                    name = _uiState.value.deviceName,
                    type = Config.CLIENT_TYPE,
                    isThisDevice = true,
                    byCategory = selfSummary ?: emptyMap(),
                )
                val linkedToday = mutableListOf<DeviceToday>()

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

                    linkedToday.add(DeviceToday(client.clientId, client.clientName, client.clientType, false, summary))
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

                val firstError = selfResult.exceptionOrNull() ?: linkedResult.exceptionOrNull()
                val todayByDevice = TodayModel.orderDevices(selfToday, linkedToday)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = firstError?.let { ErrorMessages.from(it) } ?: extraError,
                    thisDeviceUsage = thisDeviceUsage,
                    linkedDevices = devices,
                    unifiedUsage = unifiedUsage,
                    todayByDevice = todayByDevice,
                    todayLoaded = true,
                    scope = TodayModel.resolveScope(todayByDevice, _uiState.value.scope),
                )
            } catch (e: Exception) {
                Timber.e(e, "Error refreshing data")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    todayLoaded = true,
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
            val result = repository.linkDevice(Identity.identity().clientId, code)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isLinking = false, linkingSuccess = true)
                refreshData()
            } else {
                _uiState.value = _uiState.value.copy(
                    isLinking = false,
                    linkingError = result.exceptionOrNull()?.let { ErrorMessages.from(it) } ?: "Failed to link device"
                )
            }
        }
    }

    fun unlinkDevice(targetDeviceId: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.unlinkDevice(Identity.identity().clientId, targetDeviceId)
            onComplete(result.isSuccess)
            if (result.isSuccess) {
                _uiState.update { s ->
                    val today = s.todayByDevice.filter { it.id != targetDeviceId }
                    s.copy(
                        linkedDevices = s.linkedDevices.filter { it.id != targetDeviceId },
                        todayByDevice = today,
                        scope = TodayModel.resolveScope(today, s.scope),
                    )
                }
            }
        }
    }

    fun generateLinkCode() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGeneratingCode = true, generatedCode = null)
            try {
                val response = apiService.initiateLinking(
                    InitiateLinkingRequest(Identity.identity().clientId)
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
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(syncRequest)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val finished = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                workManager.getWorkInfoByIdFlow(syncRequest.id).first { it != null && it.state.isFinished }
            }
            val syncError = if (finished?.state == WorkInfo.State.FAILED) {
                "Couldn't sync. Check that Usage Access is allowed and you are online."
            } else null
            refreshData(extraError = syncError)
            loadApps()
            if (_uiState.value.week.loaded) loadWeek()
        }
    }

    /** null shows every device combined; an unknown id also falls back to all devices. */
    fun selectScope(id: String?) {
        _uiState.update { it.copy(scope = TodayModel.resolveScope(it.todayByDevice, id)) }
    }

    /** This week vs last week, all devices combined (this device + linked), 14 days of history. */
    fun loadWeek() {
        if (weekJob?.isActive == true) return
        weekJob = viewModelScope.launch {
            _uiState.update { it.copy(week = it.week.copy(loading = true)) }
            val devices = historyDevices()
            val today = repository.getTodayDate()
            val week = try {
                val (rows, failed) = withContext(Dispatchers.IO) { history.load(devices, WEEK_HISTORY_DAYS) }
                WeekState(loaded = true, view = WeekModel.build(rows, today), failedDays = failed.coerceAtMost(WEEK_HISTORY_DAYS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Week load failed")
                _uiState.value.week.copy(loading = false, loaded = true, failedDays = WEEK_HISTORY_DAYS)
            }
            _uiState.update { it.copy(week = week) }
        }
    }

    /** This device then linked devices as (id, name), for history loads. Falls back to the last known list when offline. */
    private suspend fun historyDevices(): List<Pair<String, String>> {
        val selfId = Identity.identity().clientId
        val linked = repository.getLinkedClients(selfId).getOrNull()?.data?.clients
            ?.filter { it.clientId != selfId }
            ?.distinctBy { it.clientId }
            ?.map { it.clientId to it.clientName }
            ?: _uiState.value.todayByDevice.filter { !it.isThisDevice }.map { it.id to it.name }
        return listOf(selfId to _uiState.value.deviceName) + linked
    }

    /**
     * Loads the last [EXPORT_DAYS] days for this device and linked devices and builds the CSV. Nothing is exported
     * when some days failed to load (a silently partial file would look complete) or when there is no data.
     */
    fun prepareExport() {
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            _uiState.update { it.copy(export = ExportState(preparing = true)) }
            val next = try {
                val devices = historyDevices()
                val (rows, failed) = withContext(Dispatchers.IO) { history.load(devices, EXPORT_DAYS) }
                val exportRows = ExportRows.from(rows)
                when {
                    failed > 0 -> ExportState(outcome = ExportOutcome.LoadFailed)
                    exportRows.isEmpty() -> ExportState(outcome = ExportOutcome.NothingToExport)
                    else -> ExportState(
                        csv = CsvExport.build(exportRows),
                        fileName = ExportRows.fileName(repository.getTodayDate()),
                        pickerRequested = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Export load failed")
                ExportState(outcome = ExportOutcome.LoadFailed)
            }
            _uiState.update { it.copy(export = next) }
        }
    }

    /** The UI opened the save screen; do not open it again on recomposition or rotation. */
    fun exportPickerShown() {
        _uiState.update { it.copy(export = it.export.copy(pickerRequested = false)) }
    }

    /** Writes the prepared CSV to [uri] (from the save screen). A null [uri] means the user backed out. */
    fun writeExport(uri: Uri?) {
        val csv = _uiState.value.export.csv
        if (uri == null || csv == null) {
            _uiState.update { it.copy(export = ExportState()) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(export = it.export.copy(preparing = true)) }
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: return@withContext false
                    out.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (e: Exception) {
                    Timber.e(e, "Export write failed")
                    false
                }
            }
            _uiState.update {
                it.copy(export = ExportState(outcome = if (ok) ExportOutcome.Saved else ExportOutcome.SaveFailed))
            }
        }
    }

    fun clearExportOutcome() {
        _uiState.update { it.copy(export = it.export.copy(outcome = null)) }
    }

    /** Reads saved category overrides without touching usage or the network (for Settings). */
    fun loadOverrides() {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { overrides.all() }
            _uiState.update { it.copy(categoryOverrides = saved) }
        }
    }

    /** Top apps on this phone today, with their category from overrides, then the server. */
    fun loadApps() {
        if (appsJob?.isActive == true) return
        appsJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val granted = UsageAccess.isGranted(app)
            val (apps, saved) = withContext(Dispatchers.IO) {
                val list = if (granted) {
                    try {
                        UsageCollector(app).todayApps().take(APPS_SHOWN)
                    } catch (e: Exception) {
                        Timber.e(e, "Reading app usage failed")
                        emptyList()
                    }
                } else emptyList()
                list to overrides.all()
            }
            _uiState.update { it.copy(usageAccessGranted = granted, appsToday = apps, categoryOverrides = saved) }

            val known = _uiState.value.appCategories
            val missing = apps.map { it.packageName }.filter { it !in known }
            if (missing.isEmpty()) return@launch
            val mapper = CategoryMapper { apiService.getAppCategoryMapping(AppCategoryRequest(it)) }
            val outcome = try {
                mapper.map(missing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Category lookup failed")
                null
            }
            // Same rule as the sync: a package the server does not know counts as "Other".
            if (outcome is CategoryMapper.Outcome.Mapped) {
                val answered = missing.associateWith { outcome.categories[it] ?: "Other" }
                _uiState.update { it.copy(appCategories = it.appCategories + answered) }
            }
        }
    }

    /** null or blank [category] resets [pkg] to the automatic category. Then syncs once so totals follow. */
    fun setCategoryOverride(pkg: String, category: String?) {
        if (category.isNullOrBlank()) overrides.remove(pkg) else overrides.set(pkg, category)
        _uiState.update { it.copy(categoryOverrides = overrides.all()) }
        syncNow()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun getUsageHistory(days: Int): List<LocalStorage.DailyUsage> {
        return localStorage.getUsageHistory(days)
    }

    /** Deletes everything this phone stores about usage: cached summaries, cached past days and category overrides. */
    fun resetAllData() {
        localStorage.clearAllCache()
        pastDays.clear()
        overrides.all().keys.forEach { overrides.remove(it) }
        _uiState.value = MainUiState(
            deviceId = _uiState.value.deviceId,
            deviceName = _uiState.value.deviceName
        )
        refreshData()
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
        private const val SYNC_TIMEOUT_MS = 60_000L
        private const val WEEK_HISTORY_DAYS = 14
        private const val APPS_SHOWN = 15
        private const val EXPORT_DAYS = 30

        fun formatSeconds(totalSeconds: Int): String {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            return when {
                hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                hours > 0 -> "${hours}h"
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
