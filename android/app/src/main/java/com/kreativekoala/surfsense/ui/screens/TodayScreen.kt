package com.kreativekoala.surfsense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kreativekoala.surfsense.R
import com.kreativekoala.surfsense.insights.DeviceToday
import com.kreativekoala.surfsense.insights.TideModel
import com.kreativekoala.surfsense.insights.TodayModel
import com.kreativekoala.surfsense.ui.components.CategoryPalette
import com.kreativekoala.surfsense.ui.components.CategoryRow
import com.kreativekoala.surfsense.ui.components.DeviceRow
import com.kreativekoala.surfsense.ui.components.DurationText
import com.kreativekoala.surfsense.ui.components.TideLine
import com.kreativekoala.surfsense.ui.components.formatDuration
import com.kreativekoala.surfsense.ui.theme.SurfSenseTheme
import com.kreativekoala.surfsense.usage.AppUsage
import com.kreativekoala.surfsense.viewmodel.MainUiState
import kotlinx.coroutines.launch

/**
 * Today: one sentence total, the combined tide line, a row per device on a shared scale, the categories for the
 * selected scope, and the apps on this phone (tap one to change its category).
 */
@Composable
fun TodayScreen(
    state: MainUiState,
    onSelectScope: (String?) -> Unit,
    onSyncNow: () -> Unit,
    onLoadApps: () -> Unit,
    onSetCategory: (pkg: String, category: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLoadApps() }
    var sheetPkg by rememberSaveable { mutableStateOf<String?>(null) }

    val devices = state.todayByDevice
    val scope = TodayModel.resolveScope(devices, state.scope)
    val thisId = devices.firstOrNull { it.isThisDevice }?.id
    val multi = devices.size > 1
    val showApps = state.usageAccessGranted && state.appsToday.isNotEmpty() && (scope == null || scope == thisId)

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        state.error?.let { item { ErrorLine(it, Modifier.padding(bottom = 16.dp)) } }

        when {
            !state.todayLoaded -> item { SkeletonLines() }
            !TodayModel.hasAnyData(devices) -> item { EmptyToday(syncing = state.isLoading, onSyncNow = onSyncNow) }
            else -> {
                item { TodayHeader(devices, scope, thisId) }
                if (multi) {
                    item { ScopeChips(devices, scope, onSelectScope) }
                }
                item {
                    TideLine(
                        TodayModel.scopeRow(devices, scope).segments,
                        Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                }
                if (multi) {
                    val rows = TodayModel.deviceRows(devices)
                    items(devices.size, key = { "device-" + devices[it].id }) { i ->
                        val d = devices[i]
                        DeviceRow(
                            name = d.name,
                            type = d.type,
                            row = rows[i],
                            selected = scope == d.id,
                            onClick = { onSelectScope(if (scope == d.id) null else d.id) },
                        )
                    }
                }
                item { Hairline(Modifier.padding(vertical = 12.dp)) }

                val cats = TodayModel.scopeCategories(devices, scope).entries.sortedByDescending { it.value }
                val total = cats.sumOf { it.value }
                if (cats.isEmpty()) {
                    val name = devices.firstOrNull { it.id == scope }?.name.orEmpty()
                    item {
                        Text(
                            stringResource(R.string.today_device_empty, name),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(cats, key = { "cat-" + it.key }) { (cat, secs) ->
                        CategoryRow(cat, secs, if (total > 0) secs.toFloat() / total else 0f)
                    }
                }

                if (showApps) {
                    item { AppsHeader() }
                    items(state.appsToday, key = { "app-" + it.packageName }) { app ->
                        AppRow(
                            app = app,
                            category = TodayModel.currentCategory(app.packageName, state.categoryOverrides, state.appCategories),
                            overridden = app.packageName in state.categoryOverrides,
                            onClick = { sheetPkg = app.packageName },
                        )
                    }
                }
            }
        }
    }

    val sheetApp = state.appsToday.firstOrNull { it.packageName == sheetPkg }
    if (sheetApp != null) {
        CategorySheet(
            app = sheetApp,
            current = TodayModel.currentCategory(sheetApp.packageName, state.categoryOverrides, state.appCategories),
            overridden = sheetApp.packageName in state.categoryOverrides,
            onPick = { onSetCategory(sheetApp.packageName, it) },
            onDismiss = { sheetPkg = null },
        )
    }
}

@Composable
private fun TodayHeader(devices: List<DeviceToday>, scope: String?, thisId: String?) {
    val total = TodayModel.scopeCategories(devices, scope).values.sum()
    val duration = formatDuration(total)
    val resources = LocalContext.current.resources
    val sentence = when {
        scope == null && devices.size > 1 ->
            resources.getQuantityString(R.plurals.today_total_devices, devices.size, duration, devices.size)
        scope == null || scope == thisId -> stringResource(R.string.today_total_phone, duration)
        else -> stringResource(R.string.today_total_device, duration, devices.first { it.id == scope }.name)
    }
    Text(headlineSentence(sentence, duration), style = headlineStyle())
}

@Composable
private fun ScopeChips(devices: List<DeviceToday>, scope: String?, onSelectScope: (String?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = scope == null,
            onClick = { onSelectScope(null) },
            label = { Text(stringResource(R.string.scope_all_devices), maxLines = 1) },
        )
        devices.forEach { d ->
            FilterChip(
                selected = scope == d.id,
                onClick = { onSelectScope(d.id) },
                label = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun EmptyToday(syncing: Boolean, onSyncNow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.today_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(onClick = onSyncNow, enabled = !syncing, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.sync_now))
        }
    }
}

@Composable
private fun AppsHeader() {
    Column(Modifier.padding(top = 24.dp, bottom = 4.dp)) {
        Hairline(Modifier.padding(bottom = 16.dp))
        Text(
            stringResource(R.string.apps_on_this_phone),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.apps_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun categoryLabel(category: String?, overridden: Boolean): String = when {
    category == null -> stringResource(R.string.category_unknown)
    overridden -> stringResource(R.string.category_set_by_you, category)
    else -> category
}

@Composable
private fun AppRow(app: AppUsage, category: String?, overridden: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CategoryDot(category)
                Text(
                    categoryLabel(category, overridden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DurationText(
            app.seconds,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CategoryDot(category: String?) {
    val color = if (category == null) MaterialTheme.colorScheme.outlineVariant else CategoryPalette.colorFor(category)
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(
    app: AppUsage,
    current: String?,
    overridden: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun choose(category: String?) {
        // Re-picking the category already shown changes nothing, so it does not trigger a sync.
        val unchanged = category != null && category == current
        if (!unchanged) onPick(category)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item {
                Text(
                    stringResource(R.string.change_category),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(TideModel.CANONICAL) { cat ->
                val selected = current != null && TideModel.canonicalCategory(current) == cat
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { choose(cat) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    CategoryDot(cat)
                    Text(cat, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (overridden) {
                item {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { choose(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.use_automatic_category))
                    }
                }
            }
        }
    }
}

private val previewDevices = listOf(
    DeviceToday("p", "Pixel 8", "ANDROID", true, mapOf("Social Media" to 7800, "Work/Productivity" to 4200, "News" to 900)),
    DeviceToday("l", "Work laptop", "CHROME_EXTENSION", false, mapOf("Work/Productivity" to 6480, "Entertainment" to 1500)),
    DeviceToday("t", "Tablet", "ANDROID", false, emptyMap()),
)

private val previewState = MainUiState(
    todayLoaded = true,
    todayByDevice = previewDevices,
    usageAccessGranted = true,
    appsToday = listOf(
        AppUsage("com.instagram.android", "Instagram", 5400),
        AppUsage("com.google.android.gm", "Gmail", 2700),
        AppUsage("org.example.reader", "org.example.reader", 40),
    ),
    appCategories = mapOf("com.instagram.android" to "Social Media", "com.google.android.gm" to "Work/Productivity"),
    categoryOverrides = mapOf("com.google.android.gm" to "News"),
)

@Preview(name = "Today", showBackground = true, heightDp = 1400)
@Preview(name = "Today 320dp 200%", showBackground = true, widthDp = 320, fontScale = 2f, heightDp = 2400)
@Composable
private fun TodayScreenPreview() {
    SurfSenseTheme {
        TodayScreen(previewState, {}, {}, {}, { _, _ -> }, Modifier.background(MaterialTheme.colorScheme.background))
    }
}

@Preview(name = "Today empty 320dp 200%", showBackground = true, widthDp = 320, fontScale = 2f)
@Composable
private fun TodayEmptyPreview() {
    SurfSenseTheme {
        TodayScreen(
            MainUiState(todayLoaded = true, todayByDevice = listOf(previewDevices[2].copy(isThisDevice = true))),
            {}, {}, {}, { _, _ -> },
            Modifier.background(MaterialTheme.colorScheme.background),
        )
    }
}
