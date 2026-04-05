package com.kreativekoala.surfsense.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kreativekoala.surfsense.Config
import com.kreativekoala.surfsense.LocalStorage
import com.kreativekoala.surfsense.R
import com.kreativekoala.surfsense.viewmodel.*
import java.text.SimpleDateFormat
import java.util.Locale

// region Navigation

enum class Tab(@StringRes val labelRes: Int, val icon: ImageVector, val outlinedIcon: ImageVector) {
    ThisDevice(R.string.tab_this_device, Icons.Filled.PhoneAndroid, Icons.Outlined.PhoneAndroid),
    LinkedDevices(R.string.tab_linked_devices, Icons.Filled.Devices, Icons.Outlined.Devices),
    Trends(R.string.tab_trends, Icons.AutoMirrored.Filled.TrendingUp, Icons.AutoMirrored.Outlined.TrendingUp),
    Settings(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurfSenseApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(Tab.LinkedDevices) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(selectedTab.labelRes)) },
                actions = {
                    if (selectedTab != Tab.Settings) {
                        IconButton(onClick = { viewModel.refreshData() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val label = stringResource(tab.labelRes)
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                if (selectedTab == tab) tab.icon else tab.outlinedIcon,
                                contentDescription = label
                            )
                        },
                        label = { Text(label, maxLines = 1, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            when (selectedTab) {
                Tab.ThisDevice -> ThisDeviceScreen(uiState, viewModel)
                Tab.LinkedDevices -> LinkedDevicesScreen(uiState, viewModel)
                Tab.Trends -> TrendsScreen(viewModel)
                Tab.Settings -> SettingsScreen(uiState, viewModel)
            }
        }
    }
}

// endregion

// region This Device Screen

@Composable
fun ThisDeviceScreen(uiState: MainUiState, viewModel: MainViewModel) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (uiState.isLoading) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.loading_usage_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (uiState.thisDeviceUsage != null) {
            UsageCard(title = stringResource(R.string.today_usage_title), usage = uiState.thisDeviceUsage)
        } else {
            // Empty state
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.no_usage_data_yet),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.no_usage_data_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }

        // Sync Now button
        if (!uiState.isLoading) {
            Button(
                onClick = { viewModel.syncNow() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Sync, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sync_now), fontWeight = FontWeight.SemiBold)
            }
        }

        uiState.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// endregion

// region Linked Devices Screen

@Composable
fun LinkedDevicesScreen(uiState: MainUiState, viewModel: MainViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var deviceToUnlink by remember { mutableStateOf<LinkedDeviceUi?>(null) }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Unified Usage Card (linked devices only)
        uiState.unifiedUsage?.let { unified ->
            UsageCard(title = stringResource(R.string.linked_devices_usage_title), usage = unified)
        }

        // Loading / Error / Device List
        if (uiState.isLoading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.linkedDevices.isEmpty()) {
            // Empty state
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Link, null, Modifier.size(48.dp), tint = Color.Gray)
                    Text(stringResource(R.string.no_linked_devices), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.no_linked_devices_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            uiState.linkedDevices.forEach { device ->
                DeviceCard(device = device, onRemove = { deviceToUnlink = device })
            }
        }

        // Action Buttons
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_device), fontWeight = FontWeight.SemiBold)
        }

        OutlinedButton(
            onClick = { showShareDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Share, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.share_this_device), fontWeight = FontWeight.SemiBold)
        }
    }

    // Add Device Dialog
    if (showAddDialog) {
        AddDeviceDialog(
            isLinking = uiState.isLinking,
            linkingSuccess = uiState.linkingSuccess,
            linkingError = uiState.linkingError,
            onLink = { code -> viewModel.linkDevice(code) },
            onDismiss = {
                showAddDialog = false
                viewModel.clearLinkingState()
            }
        )
    }

    // Share Device Dialog
    if (showShareDialog) {
        ShareDeviceDialog(
            code = uiState.generatedCode,
            isGenerating = uiState.isGeneratingCode,
            onGenerate = { viewModel.generateLinkCode() },
            onDismiss = {
                showShareDialog = false
                viewModel.clearLinkingState()
            }
        )
    }

    // Unlink Confirmation
    deviceToUnlink?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToUnlink = null },
            title = { Text(stringResource(R.string.remove_device_title)) },
            text = { Text(stringResource(R.string.remove_device_message, device.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unlinkDevice(device.id) { }
                        deviceToUnlink = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnlink = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

// endregion

// region Trends Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(viewModel: MainViewModel) {
    val periodLabels = listOf(
        stringResource(R.string.period_7_days),
        stringResource(R.string.period_14_days),
        stringResource(R.string.period_30_days)
    )
    val periodDays = listOf(7, 14, 30)
    var selectedIndex by remember { mutableIntStateOf(0) }
    val days = periodDays[selectedIndex]
    val history = remember(selectedIndex) { viewModel.getUsageHistory(days) }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Period Selector
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            periodLabels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(index, periodLabels.size)
                ) { Text(label) }
            }
        }

        if (history.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.TrendingUp, null, Modifier.size(60.dp), tint = Color.Gray)
                Text(stringResource(R.string.no_usage_data_trends), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.no_usage_data_trends_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Summary Card
            SummaryCard(history, periodLabels[selectedIndex])

            // Daily Usage Bar Chart
            DailyUsageChart(history)

            // Category Breakdown
            CategoryBreakdownCard(history)

            // Daily Details
            DailyDetailsCard(history)
        }
    }
}

@Composable
private fun SummaryCard(history: List<LocalStorage.DailyUsage>, periodLabel: String) {
    val totalMinutes = history.sumOf { it.totalMinutes }
    val avgMinutes = if (history.isNotEmpty()) totalMinutes / history.size else 0
    val peakMinutes = history.maxOfOrNull { it.totalMinutes } ?: 0

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.summary), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(periodLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatBox(stringResource(R.string.stat_total), MainViewModel.formatMinutes(totalMinutes), Icons.Default.Schedule, Color(0xFF3B82F6), Modifier.weight(1f))
                StatBox(stringResource(R.string.stat_daily_avg), MainViewModel.formatMinutes(avgMinutes), Icons.Default.BarChart, Color(0xFF10B981), Modifier.weight(1f))
                StatBox(stringResource(R.string.stat_peak_day), MainViewModel.formatMinutes(peakMinutes), Icons.Default.ArrowUpward, Color(0xFFF97316), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatBox(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DailyUsageChart(history: List<LocalStorage.DailyUsage>) {
    val reversed = history.reversed()
    val maxMinutes = reversed.maxOfOrNull { it.totalMinutes } ?: 1

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.daily_usage), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                reversed.forEach { day ->
                    val fraction = day.totalMinutes.toFloat() / maxMinutes
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(Color(0xFF3B82F6))
                        )
                    }
                }
            }
            // Date labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                reversed.forEach { day ->
                    Text(
                        formatDateShort(day.date),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryBreakdownCard(history: List<LocalStorage.DailyUsage>) {
    val totals = mutableMapOf<String, Int>()
    history.forEach { day -> day.byCategory.forEach { (cat, min) -> totals[cat] = (totals[cat] ?: 0) + min } }
    val sorted = totals.entries.sortedByDescending { it.value }
    val maxVal = sorted.firstOrNull()?.value ?: 1

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.category_breakdown), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            sorted.forEach { (category, minutes) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(colorForCategory(category)))
                    Spacer(Modifier.width(8.dp))
                    Text(category, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(MainViewModel.formatMinutes(minutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Progress bar
                Box(
                    Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(minutes.toFloat() / maxVal)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(colorForCategory(category))
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyDetailsCard(history: List<LocalStorage.DailyUsage>) {
    val noDataText = stringResource(R.string.no_data)
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.daily_details), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            history.take(7).forEachIndexed { index, day ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(formatDateFull(day.date), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        val top = day.byCategory.maxByOrNull { it.value }
                        Text(
                            top?.let { "${it.key}: ${MainViewModel.formatMinutes(it.value)}" } ?: noDataText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        MainViewModel.formatMinutes(day.totalMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF3B82F6),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (index < minOf(6, history.size - 1)) {
                    HorizontalDivider()
                }
            }
        }
    }
}

// endregion

// region Settings Screen

@Composable
fun SettingsScreen(uiState: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) { null }
    }
    val versionName = packageInfo?.versionName ?: "1.0"

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Data Sync Section
        SectionCard(stringResource(R.string.section_data_sync)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.auto_sync), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = true, onCheckedChange = {})
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.sync_interval), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.sync_interval_value, Config.USAGE_SYNC_INTERVAL_MINUTES.toInt()), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Device Info Section
        SectionCard(stringResource(R.string.section_device)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.device_id), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    uiState.deviceId.take(8) + "\u2026",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TextFormat, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.device_name), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(uiState.deviceName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        // About Section
        SectionCard(stringResource(R.string.section_about)) {
            SettingsRow(Icons.Default.Info, stringResource(R.string.about)) { showAboutDialog = true }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Numbers, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.version), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(versionName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SettingsLinkRow(Icons.Default.PrivacyTip, stringResource(R.string.privacy_policy), Config.privacyPolicyURL)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SettingsLinkRow(Icons.Default.Description, stringResource(R.string.terms_of_service), Config.termsOfServiceURL)
        }

        // Danger Zone
        SectionCard(stringResource(R.string.section_data)) {
            TextButton(
                onClick = { showResetDialog = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reset_all_data))
            }
        }
    }

    if (showResetDialog) {
        val dataResetText = stringResource(R.string.data_reset)
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_all_data_title)) },
            text = { Text(stringResource(R.string.reset_all_data_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetAllData()
                        showResetDialog = false
                        Toast.makeText(context, dataResetText, Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text(stringResource(R.string.done)) }
            },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.BarChart, null, Modifier.size(60.dp), tint = Color(0xFF3B82F6))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.about_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SettingsLinkRow(icon: ImageVector, label: String, url: String) {
    val context = LocalContext.current
    Surface(
        onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
        },
        color = Color.Transparent
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
    }
}

// endregion

// region Shared Components

@Composable
fun UsageCard(title: String, usage: DeviceUsageUi) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            // Total time
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    usage.totalTime,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3B82F6)
                )
                Text(stringResource(R.string.total_screen_time_today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Category Breakdown
            if (usage.categories.isNotEmpty()) {
                Text(stringResource(R.string.category_breakdown), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                usage.categories.forEach { cat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(colorForName(cat.color)))
                        Spacer(Modifier.width(8.dp))
                        Text(cat.category, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(MainViewModel.formatSeconds(cat.seconds), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Quick Stats
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.quick_stats), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row {
                    Text(stringResource(R.string.most_used_category), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(usage.mostUsedCategory, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text(usage.mostUsedTime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: LinkedDeviceUi, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                when {
                    device.type.contains("CHROME", true) -> Icons.Default.Language
                    device.type.contains("IOS", true) || device.type.contains("IPHONE", true) -> Icons.Default.PhoneIphone
                    device.type.contains("ANDROID", true) -> Icons.Default.PhoneAndroid
                    else -> Icons.Default.Devices
                },
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(device.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                device.usage.totalTime,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// endregion

// region Dialogs

@Composable
fun AddDeviceDialog(
    isLinking: Boolean,
    linkingSuccess: Boolean,
    linkingError: String?,
    onLink: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_device)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (linkingSuccess) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(48.dp).align(Alignment.CenterHorizontally), tint = Color(0xFF10B981))
                    Text(stringResource(R.string.device_linked_success), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.enter_code_prompt, Config.LINKING_CODE_LENGTH))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { if (it.length <= Config.LINKING_CODE_LENGTH && it.all { c -> c.isDigit() }) code = it },
                        label = { Text(stringResource(R.string.device_code_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            textAlign = TextAlign.Center,
                            fontSize = 24.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp
                        )
                    )
                    linkingError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (linkingSuccess) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            } else {
                TextButton(
                    onClick = { onLink(code) },
                    enabled = code.length == Config.LINKING_CODE_LENGTH && !isLinking
                ) {
                    if (isLinking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.link_device))
                }
            }
        },
        dismissButton = {
            if (!linkingSuccess) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

@Composable
fun ShareDeviceDialog(
    code: String?,
    isGenerating: Boolean,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val codeCopiedText = stringResource(R.string.code_copied)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_this_device)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.share_device_description), style = MaterialTheme.typography.bodySmall)

                if (code != null) {
                    Text(stringResource(R.string.your_sharing_code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        code,
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3B82F6),
                        letterSpacing = 4.sp
                    )
                    Text(
                        stringResource(R.string.code_expires_in, Config.LINKING_CODE_EXPIRY_MINUTES),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SurfSense Code", code))
                            Toast.makeText(context, codeCopiedText, Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.copy)) }
                        Button(onClick = onGenerate, enabled = !isGenerating) { Text(stringResource(R.string.new_code)) }
                    }
                } else {
                    Button(
                        onClick = onGenerate,
                        enabled = !isGenerating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isGenerating) stringResource(R.string.generating) else stringResource(R.string.generate_sharing_code))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        }
    )
}

// endregion

// region Utilities

fun colorForName(name: String): Color {
    return when (name.lowercase()) {
        "blue" -> Color(0xFF3B82F6)
        "green" -> Color(0xFF10B981)
        "purple" -> Color(0xFF8B5CF6)
        "orange" -> Color(0xFFF97316)
        "cyan" -> Color(0xFF06B6D4)
        "pink" -> Color(0xFFEC4899)
        "yellow" -> Color(0xFFEAB308)
        "indigo" -> Color(0xFF6366F1)
        "red" -> Color(0xFFEF4444)
        else -> Color(0xFF6B7280)
    }
}

fun colorForCategory(category: String): Color {
    return colorForName(MainViewModel.categoryColor(category))
}

private fun formatDateShort(dateString: String): String {
    return try {
        val input = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val output = SimpleDateFormat("MM/dd", Locale.US)
        input.parse(dateString)?.let { output.format(it) } ?: dateString
    } catch (e: Exception) { dateString }
}

private fun formatDateFull(dateString: String): String {
    return try {
        val input = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val output = SimpleDateFormat("EEEE, MMM d", Locale.US)
        input.parse(dateString)?.let { output.format(it) } ?: dateString
    } catch (e: Exception) { dateString }
}

// endregion
