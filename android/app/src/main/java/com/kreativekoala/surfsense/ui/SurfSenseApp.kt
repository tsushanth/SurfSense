package com.kreativekoala.surfsense.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kreativekoala.surfsense.Config
import com.kreativekoala.surfsense.LocalStorage
import com.kreativekoala.surfsense.viewmodel.*
import java.text.SimpleDateFormat
import java.util.Locale

// region Navigation

enum class Tab(val label: String, val icon: ImageVector, val outlinedIcon: ImageVector) {
    ThisDevice("This Device", Icons.Filled.PhoneAndroid, Icons.Outlined.PhoneAndroid),
    LinkedDevices("Linked Devices", Icons.Filled.Devices, Icons.Outlined.Devices),
    Trends("Trends", Icons.AutoMirrored.Filled.TrendingUp, Icons.AutoMirrored.Outlined.TrendingUp),
    Settings("Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurfSenseApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(Tab.LinkedDevices) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(selectedTab.label) },
                actions = {
                    if (selectedTab != Tab.Settings) {
                        IconButton(onClick = { viewModel.refreshData() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                if (selectedTab == tab) tab.icon else tab.outlinedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label, maxLines = 1, fontSize = 11.sp) }
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
                    Text("Loading usage data...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (uiState.thisDeviceUsage != null) {
            UsageCard(title = "Today's Usage", usage = uiState.thisDeviceUsage)
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
                    "No Usage Data Yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap \"Sync Now\" to collect and upload your device's usage data. This requires Usage Access permission.",
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
                Text("Sync Now", fontWeight = FontWeight.SemiBold)
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
            UsageCard(title = "Linked Devices Usage", usage = unified)
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
                    Text("No Linked Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Add your first device to start tracking usage across all your devices",
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
            Text("Add Device", fontWeight = FontWeight.SemiBold)
        }

        OutlinedButton(
            onClick = { showShareDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Share, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share This Device", fontWeight = FontWeight.SemiBold)
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
            title = { Text("Remove Device") },
            text = { Text("Are you sure you want to remove ${device.name}? This device will no longer be able to track your usage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unlinkDevice(device.id) { }
                        deviceToUnlink = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnlink = null }) { Text("Cancel") }
            }
        )
    }
}

// endregion

// region Trends Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(viewModel: MainViewModel) {
    val periods = listOf("7 Days" to 7, "14 Days" to 14, "30 Days" to 30)
    var selectedIndex by remember { mutableIntStateOf(0) }
    val days = periods[selectedIndex].second
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
            periods.forEachIndexed { index, (label, _) ->
                SegmentedButton(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(index, periods.size)
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
                Text("No Usage Data Yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Start tracking your usage to see trends over time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Summary Card
            SummaryCard(history, periods[selectedIndex].first)

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
                Text("Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(periodLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatBox("Total", MainViewModel.formatMinutes(totalMinutes), Icons.Default.Schedule, Color(0xFF3B82F6), Modifier.weight(1f))
                StatBox("Daily Avg", MainViewModel.formatMinutes(avgMinutes), Icons.Default.BarChart, Color(0xFF10B981), Modifier.weight(1f))
                StatBox("Peak Day", MainViewModel.formatMinutes(peakMinutes), Icons.Default.ArrowUpward, Color(0xFFF97316), Modifier.weight(1f))
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
            Text("Daily Usage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            Text("Category Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Daily Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                            top?.let { "${it.key}: ${MainViewModel.formatMinutes(it.value)}" } ?: "No data",
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
        SectionCard("Data Sync") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Auto Sync", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = true, onCheckedChange = {})
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Sync Interval", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${Config.USAGE_SYNC_INTERVAL_MINUTES} minutes", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Device Info Section
        SectionCard("Device") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Device ID", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    uiState.deviceId.take(8) + "...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TextFormat, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Device Name", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(uiState.deviceName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        // About Section
        SectionCard("About") {
            SettingsRow(Icons.Default.Info, "About") { showAboutDialog = true }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Numbers, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Version", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(versionName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SettingsLinkRow(Icons.Default.PrivacyTip, "Privacy Policy", Config.privacyPolicyURL)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SettingsLinkRow(Icons.Default.Description, "Terms of Service", Config.termsOfServiceURL)
        }

        // Danger Zone
        SectionCard("Data") {
            TextButton(
                onClick = { showResetDialog = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset All Data")
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset All Data?") },
            text = { Text("This action cannot be undone. All your local data will be deleted and devices will be unlinked.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetAllData()
                        showResetDialog = false
                        Toast.makeText(context, "Data reset", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("Done") }
            },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.BarChart, null, Modifier.size(60.dp), tint = Color(0xFF3B82F6))
                    Text("SurfSense", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Track your digital wellness across all your devices",
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
                Text("Total screen time today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Category Breakdown
            if (usage.categories.isNotEmpty()) {
                Text("Category Breakdown", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                Text("Quick Stats", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row {
                    Text("Most used category", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
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
        title = { Text("Add Device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (linkingSuccess) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(48.dp).align(Alignment.CenterHorizontally), tint = Color(0xFF10B981))
                    Text("Device linked successfully!", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Enter the ${Config.LINKING_CODE_LENGTH}-digit code from the device you want to link")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { if (it.length <= Config.LINKING_CODE_LENGTH && it.all { c -> c.isDigit() }) code = it },
                        label = { Text("Device Code") },
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
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                TextButton(
                    onClick = { onLink(code) },
                    enabled = code.length == Config.LINKING_CODE_LENGTH && !isLinking
                ) {
                    if (isLinking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Link Device")
                }
            }
        },
        dismissButton = {
            if (!linkingSuccess) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share This Device") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Generate a code that others can use to link and monitor this device's usage", style = MaterialTheme.typography.bodySmall)

                if (code != null) {
                    Text("Your sharing code:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        code,
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3B82F6),
                        letterSpacing = 4.sp
                    )
                    Text(
                        "Code expires in ${Config.LINKING_CODE_EXPIRY_MINUTES} minutes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SurfSense Code", code))
                            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy") }
                        Button(onClick = onGenerate, enabled = !isGenerating) { Text("New Code") }
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
                        Text(if (isGenerating) "Generating..." else "Generate Sharing Code")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
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
