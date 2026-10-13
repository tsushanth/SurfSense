package com.kreativekoala.surfsense.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.kreativekoala.surfsense.Config
import com.kreativekoala.surfsense.R
import com.kreativekoala.surfsense.ui.theme.SurfSenseTheme
import com.kreativekoala.surfsense.viewmodel.ExportOutcome
import com.kreativekoala.surfsense.viewmodel.ExportState
import com.kreativekoala.surfsense.viewmodel.MainUiState

/** Label for [pkg] from the package manager; the package name when the app is gone or hidden. */
internal fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().ifBlank { pkg }
} catch (e: Exception) {
    pkg
}

@Composable
fun SettingsScreen(
    state: MainUiState,
    onLoadOverrides: () -> Unit,
    onRemoveOverride: (pkg: String) -> Unit,
    onExport: () -> Unit,
    onExportPickerShown: () -> Unit,
    onExportWrite: (Uri?) -> Unit,
    onExportOutcomeShown: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showReset by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        } ?: "-"
    }

    LaunchedEffect(Unit) { onLoadOverrides() }
    ExportEffects(state.export, onExportPickerShown, onExportWrite, onExportOutcomeShown)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Section(stringResource(R.string.settings_section_sync)) {
            TextRow(stringResource(R.string.settings_sync_interval, Config.USAGE_SYNC_INTERVAL_MINUTES.toInt()))
        }

        Section(stringResource(R.string.settings_section_device)) {
            ValueRow(stringResource(R.string.settings_device_name), state.deviceName)
            Hairline()
            ValueRow(stringResource(R.string.settings_device_id), state.deviceId.take(8) + "…")
        }

        if (state.categoryOverrides.isNotEmpty()) {
            Section(stringResource(R.string.settings_section_overrides)) {
                val rows = remember(state.categoryOverrides) {
                    state.categoryOverrides.entries
                        .map { (pkg, cat) -> Triple(pkg, appLabel(context, pkg), cat) }
                        .sortedBy { it.second.lowercase() }
                }
                rows.forEachIndexed { i, (pkg, label, category) ->
                    if (i > 0) Hairline()
                    OverrideRow(label, category, onRemove = { onRemoveOverride(pkg) })
                }
            }
        }

        Section(stringResource(R.string.settings_section_data)) {
            ExportRow(state.export, onExport)
            Hairline()
            ClickRow(
                stringResource(R.string.reset_data),
                color = MaterialTheme.colorScheme.error,
                onClick = { showReset = true },
            )
        }

        Section(stringResource(R.string.section_about)) {
            ClickRow(stringResource(R.string.about), onClick = { showAbout = true })
            Hairline()
            ValueRow(stringResource(R.string.version), versionName)
            Hairline()
            LinkRow(stringResource(R.string.privacy_policy_label), Config.privacyPolicyURL)
            Hairline()
            LinkRow(stringResource(R.string.terms_label), Config.termsOfServiceURL)
        }
    }

    if (showReset) {
        val doneText = stringResource(R.string.data_reset)
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.reset_data_title)) },
            text = { Text(stringResource(R.string.reset_data_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReset()
                        showReset = false
                        Toast.makeText(context, doneText, Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

/** Opens the system save screen once the CSV is ready, and shows "Exported" when the file was written. */
@Composable
private fun ExportEffects(
    export: ExportState,
    onPickerShown: () -> Unit,
    onWrite: (Uri?) -> Unit,
    onOutcomeShown: () -> Unit,
) {
    val context = LocalContext.current
    val savedText = stringResource(R.string.export_done)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        onWrite(uri)
    }
    LaunchedEffect(export.pickerRequested) {
        val name = export.fileName
        if (export.pickerRequested && name != null) {
            onPickerShown()
            try {
                launcher.launch(name)
            } catch (e: ActivityNotFoundException) {
                onWrite(null)
            }
        }
    }
    LaunchedEffect(export.outcome) {
        if (export.outcome == ExportOutcome.Saved) {
            Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
            onOutcomeShown()
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        SectionTitle(title)
        content()
    }
}

@Composable
private fun TextRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/** Label on the left, value on the right; wraps under each other when space runs out. */
@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ClickRow(
    label: String,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun LinkRow(label: String, url: String) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (_: Exception) {
                }
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ExportRow(export: ExportState, onExport: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = !export.preparing, role = Role.Button, onClick = onExport)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.export_data),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(if (export.preparing) R.string.export_preparing else R.string.export_data_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (export.preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        when (export.outcome) {
            ExportOutcome.NothingToExport -> Text(
                stringResource(R.string.export_nothing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ExportOutcome.LoadFailed -> ErrorLine(stringResource(R.string.export_load_failed), Modifier.padding(bottom = 8.dp))
            ExportOutcome.Busy -> ErrorLine(stringResource(R.string.export_busy), Modifier.padding(bottom = 8.dp))
            ExportOutcome.SaveFailed -> ErrorLine(stringResource(R.string.export_save_failed), Modifier.padding(bottom = 8.dp))
            ExportOutcome.Saved, null -> Unit
        }
    }
}

@Composable
private fun OverrideRow(appName: String, category: String, onRemove: () -> Unit) {
    val removeDescription = stringResource(R.string.override_remove_cd, appName)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(appName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = removeDescription },
        ) { Text(stringResource(R.string.remove)) }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.about_font_credit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

private val previewSettingsState = MainUiState(
    deviceId = "3f9c1a7e-0000-4000-8000-000000000000",
    deviceName = "Pixel 8 Pro",
    categoryOverrides = mapOf("com.google.android.gm" to "Work/Productivity", "org.example.reader" to "News"),
    export = ExportState(outcome = ExportOutcome.LoadFailed),
)

@Preview(name = "Settings", showBackground = true, heightDp = 1400)
@Preview(name = "Settings 320dp 200%", showBackground = true, widthDp = 320, fontScale = 2f, heightDp = 2600)
@Composable
private fun SettingsScreenPreview() {
    SurfSenseTheme {
        SettingsScreen(
            previewSettingsState, {}, {}, {}, {}, {}, {}, {},
            Modifier.background(MaterialTheme.colorScheme.background),
        )
    }
}

@Preview(name = "Settings exporting 320dp 200%", showBackground = true, widthDp = 320, fontScale = 2f, heightDp = 2000)
@Composable
private fun SettingsExportingPreview() {
    SurfSenseTheme {
        SettingsScreen(
            MainUiState(deviceId = "abc", deviceName = "Phone", export = ExportState(preparing = true)),
            {}, {}, {}, {}, {}, {}, {},
            Modifier.background(MaterialTheme.colorScheme.background),
        )
    }
}

@Preview(name = "About 320dp 200%", showBackground = true, widthDp = 320, fontScale = 2f, heightDp = 900)
@Composable
private fun AboutPreview() {
    SurfSenseTheme { AboutDialog(onDismiss = {}) }
}
