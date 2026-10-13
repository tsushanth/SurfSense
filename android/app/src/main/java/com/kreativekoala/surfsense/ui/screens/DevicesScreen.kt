package com.kreativekoala.surfsense.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kreativekoala.surfsense.Config
import com.kreativekoala.surfsense.R
import com.kreativekoala.surfsense.ui.components.DurationText
import com.kreativekoala.surfsense.ui.components.deviceIconFor
import com.kreativekoala.surfsense.ui.theme.SurfSenseTheme
import com.kreativekoala.surfsense.ui.theme.tabularFigures
import com.kreativekoala.surfsense.viewmodel.DeviceUsageUi
import com.kreativekoala.surfsense.viewmodel.CategoryUsageUi
import com.kreativekoala.surfsense.viewmodel.LinkedDeviceUi
import com.kreativekoala.surfsense.viewmodel.MainUiState

/**
 * Linked devices: one quiet row per device with a remove action, then "Add a device" (enter a code from another
 * device) and "Share this device" (create a code for another device).
 */
@Composable
fun DevicesScreen(
    state: MainUiState,
    onLink: (code: String) -> Unit,
    onGenerateCode: () -> Unit,
    onUnlink: (deviceId: String, onComplete: (Boolean) -> Unit) -> Unit,
    onClearLinking: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showShare by rememberSaveable { mutableStateOf(false) }
    var unlinkId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        state.error?.let { ErrorLine(it, Modifier.padding(bottom = 16.dp)) }

        SectionTitle(stringResource(R.string.devices_section_linked))
        when {
            state.isLoading && state.linkedDevices.isEmpty() -> SkeletonLines(Modifier.padding(top = 8.dp))
            state.linkedDevices.isEmpty() -> Text(
                stringResource(R.string.devices_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            else -> state.linkedDevices.forEach { device ->
                LinkedDeviceRow(device, onRemove = { unlinkId = device.id })
            }
        }

        Hairline(Modifier.padding(vertical = 16.dp))

        Button(
            onClick = { showAdd = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.devices_add)) }
        Spacer(Modifier.size(8.dp))
        OutlinedButton(
            onClick = { showShare = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.devices_share)) }
    }

    if (showAdd) {
        val addedText = stringResource(R.string.device_added)
        val close = {
            showAdd = false
            onClearLinking()
        }
        LaunchedEffect(state.linkingSuccess) {
            if (state.linkingSuccess) {
                Toast.makeText(context, addedText, Toast.LENGTH_SHORT).show()
                close()
            }
        }
        AddDeviceDialog(
            isLinking = state.isLinking,
            linkingError = state.linkingError,
            onLink = onLink,
            onDismiss = close,
        )
    }

    if (showShare) {
        ShareDeviceDialog(
            code = state.generatedCode,
            isGenerating = state.isGeneratingCode,
            onGenerate = onGenerateCode,
            onDismiss = {
                showShare = false
                onClearLinking()
            },
        )
    }

    val unlinking = state.linkedDevices.firstOrNull { it.id == unlinkId }
    if (unlinking != null) {
        val appContext = context.applicationContext
        val removedText = stringResource(R.string.device_removed)
        val failedText = stringResource(R.string.device_remove_failed, unlinking.name)
        AlertDialog(
            onDismissRequest = { unlinkId = null },
            title = { Text(stringResource(R.string.device_remove_title, unlinking.name)) },
            text = { Text(stringResource(R.string.device_remove_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnlink(unlinking.id) { ok ->
                            Toast.makeText(appContext, if (ok) removedText else failedText, Toast.LENGTH_LONG).show()
                        }
                        unlinkId = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { unlinkId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun deviceTypeLabel(type: String): String = stringResource(
    when {
        type.contains("CHROME", true) -> R.string.device_type_browser
        type.contains("IOS", true) || type.contains("IPHONE", true) -> R.string.device_type_ios
        type.contains("ANDROID", true) -> R.string.device_type_android
        else -> R.string.device_type_other
    }
)

@Composable
private fun LinkedDeviceRow(device: LinkedDeviceUi, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            deviceIconFor(device.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                deviceTypeLabel(device.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DurationText(
            device.usage.categories.sumOf { it.seconds },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.device_remove_cd, device.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddDeviceDialog(
    isLinking: Boolean,
    linkingError: String?,
    onLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val ready = code.length == Config.LINKING_CODE_LENGTH && !isLinking

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.add_device_prompt, Config.LINKING_CODE_LENGTH))
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= Config.LINKING_CODE_LENGTH && it.all { c -> c.isDigit() }) code = it },
                    label = { Text(stringResource(R.string.add_device_code_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = linkingError != null,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        textAlign = TextAlign.Center,
                        fontSize = 24.sp,
                        letterSpacing = 4.sp,
                    ).tabularFigures(),
                )
                linkingError?.let { ErrorLine(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onLink(code) }, enabled = ready) {
                if (isLinking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.add_device_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ShareDeviceDialog(
    code: String?,
    isGenerating: Boolean,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val copiedText = stringResource(R.string.code_copied)
    var attempted by rememberSaveable { mutableStateOf(false) }
    val generate = {
        attempted = true
        onGenerate()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_share)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.share_device_prompt))
                if (code != null) {
                    val spoken = remember(code) { code.toList().joinToString(" ") }
                    Text(
                        code,
                        style = MaterialTheme.typography.displaySmall.tabularFigures(),
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 4.sp,
                        modifier = Modifier.semantics { contentDescription = spoken },
                    )
                    Text(
                        stringResource(R.string.share_expires, Config.LINKING_CODE_EXPIRY_MINUTES),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SurfSense Code", code))
                            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.copy)) }
                    TextButton(
                        onClick = generate,
                        enabled = !isGenerating,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.share_new_code)) }
                } else {
                    Button(
                        onClick = generate,
                        enabled = !isGenerating,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(if (isGenerating) R.string.share_creating else R.string.share_create_code))
                    }
                    if (attempted && !isGenerating) ErrorLine(stringResource(R.string.share_code_failed))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

private fun previewDevice(id: String, name: String, type: String, seconds: Int) = LinkedDeviceUi(
    id = id,
    name = name,
    type = type,
    usage = DeviceUsageUi("", listOf(CategoryUsageUi("Work/Productivity", seconds, "")), "", ""),
)

private val previewDevicesState = MainUiState(
    linkedDevices = listOf(
        previewDevice("l1", "Work laptop (Chrome)", "CHROME_EXTENSION", 7500),
        previewDevice("p2", "Sam's Pixel 8 Pro with a very long name", "ANDROID", 1620),
        previewDevice("t3", "Tablet", "OTHER", 0),
    ),
)

@Preview(name = "Devices", showBackground = true)
@Preview(name = "Devices 320dp 200%", showBackground = true, widthDp = 320, fontScale = 2f, heightDp = 1400)
@Composable
private fun DevicesScreenPreview() {
    SurfSenseTheme {
        DevicesScreen(previewDevicesState, {}, {}, { _, _ -> }, {}, Modifier.background(MaterialTheme.colorScheme.background))
    }
}

@Preview(name = "Devices empty 320dp 200%", showBackground = true, widthDp = 320, fontScale = 2f)
@Composable
private fun DevicesEmptyPreview() {
    SurfSenseTheme {
        DevicesScreen(MainUiState(), {}, {}, { _, _ -> }, {}, Modifier.background(MaterialTheme.colorScheme.background))
    }
}

@Preview(name = "Share code 320dp 200%", showBackground = true, widthDp = 320, fontScale = 2f, heightDp = 900)
@Composable
private fun ShareDialogPreview() {
    SurfSenseTheme { ShareDeviceDialog(code = "482913", isGenerating = false, onGenerate = {}, onDismiss = {}) }
}
