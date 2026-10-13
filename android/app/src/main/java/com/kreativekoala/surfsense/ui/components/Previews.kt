package com.kreativekoala.surfsense.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kreativekoala.surfsense.insights.TideModel
import com.kreativekoala.surfsense.ui.theme.SurfSenseTheme

@Preview(name = "light", showBackground = true)
@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "fontScale 2", fontScale = 2f, showBackground = true)
@Preview(name = "320dp", widthDp = 320, showBackground = true)
annotation class ThemePreviews

private val sample = TideModel.rows(
    listOf(
        "Pixel phone" to mapOf("Social Media" to 7800, "Work/Productivity" to 6480, "Other" to 1200, "Mystery" to 40),
        "Work laptop" to mapOf("Work/Productivity" to 9000, "News" to 900),
        "Tablet" to mapOf("Entertainment" to 30),
    )
)

@Composable
private fun Frame(content: @Composable () -> Unit) {
    SurfSenseTheme {
        Column(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) { content() }
    }
}

@ThemePreviews @Composable private fun TideLinePreview() = Frame {
    TideLine(sample[0].segments)
    TideLine(emptyList())
}

@ThemePreviews @Composable private fun DurationTextPreview() = Frame {
    DurationText(45, style = MaterialTheme.typography.headlineLarge)
    DurationText(22320, style = MaterialTheme.typography.headlineLarge)
}

@ThemePreviews @Composable private fun CategoryRowPreview() = Frame {
    CategoryRow("Social Media", 7800, 0.35f)
    CategoryRow("Work/Productivity", 6480, 0.29f)
    CategoryRow("Unknown thing", 30, 0.001f)
}

@ThemePreviews @Composable private fun DeviceRowPreview() = Frame {
    DeviceRow("Pixel phone", "ANDROID", sample[1], selected = true, onClick = {})
    DeviceRow("Work laptop", "CHROME_EXTENSION", sample[2], selected = false, onClick = {})
    DeviceRow("Tablet", "OTHER", sample[3], selected = false, onClick = {})
}
