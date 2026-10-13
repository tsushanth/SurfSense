package com.kreativekoala.surfsense.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kreativekoala.surfsense.R
import com.kreativekoala.surfsense.ui.screens.DevicesScreen
import com.kreativekoala.surfsense.ui.screens.SettingsScreen
import com.kreativekoala.surfsense.ui.screens.TodayScreen
import com.kreativekoala.surfsense.ui.screens.WeekScreen
import com.kreativekoala.surfsense.viewmodel.MainViewModel

enum class Tab(@StringRes val labelRes: Int, val icon: ImageVector, val outlinedIcon: ImageVector) {
    Today(R.string.tab_today, Icons.Filled.Today, Icons.Outlined.Today),
    Week(R.string.tab_week, Icons.Filled.BarChart, Icons.Outlined.BarChart),
    Devices(R.string.tab_linked_devices, Icons.Filled.Devices, Icons.Outlined.Devices),
    Settings(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

/** Navigation shell only: top bar titled with the tab, a refresh action on Today, Week and Devices, four tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurfSenseApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Today) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(selectedTab.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (selectedTab != Tab.Settings) {
                        IconButton(onClick = {
                            when (selectedTab) {
                                Tab.Week -> viewModel.loadWeek()
                                Tab.Today -> {
                                    viewModel.refreshData()
                                    viewModel.loadApps()
                                }
                                else -> viewModel.refreshData()
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        // The label below names the tab; the icon is decorative.
                        icon = { Icon(if (selectedTab == tab) tab.icon else tab.outlinedIcon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                Tab.Today -> TodayScreen(
                    state = uiState,
                    onSelectScope = viewModel::selectScope,
                    onSyncNow = viewModel::syncNow,
                    onLoadApps = viewModel::loadApps,
                    onSetCategory = viewModel::setCategoryOverride,
                )
                Tab.Week -> WeekScreen(uiState.week, onLoad = viewModel::loadWeek)
                Tab.Devices -> DevicesScreen(
                    state = uiState,
                    onLink = viewModel::linkDevice,
                    onGenerateCode = viewModel::generateLinkCode,
                    onUnlink = viewModel::unlinkDevice,
                    onClearLinking = viewModel::clearLinkingState,
                )
                Tab.Settings -> SettingsScreen(
                    state = uiState,
                    onLoadOverrides = viewModel::loadOverrides,
                    onRemoveOverride = { pkg -> viewModel.setCategoryOverride(pkg, null) },
                    onExport = viewModel::prepareExport,
                    onExportPickerShown = viewModel::exportPickerShown,
                    onExportWrite = viewModel::writeExport,
                    onExportOutcomeShown = viewModel::clearExportOutcome,
                    onReset = viewModel::resetAllData,
                )
            }
        }
    }
}
