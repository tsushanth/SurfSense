package com.kreativekoala.surfsense.ui.components

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kreativekoala.surfsense.insights.TideSegment

/** True when the system animation scale is 0 (Remove animations / reduce motion). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
}

/** "Social Media 2h 10m, Work/Productivity 1h 48m" for TalkBack. */
fun tideDescription(segments: List<TideSegment>, lessThanMinute: String = "<1m"): String =
    segments.joinToString(", ") { "${it.category} ${formatDuration(it.seconds, lessThanMinute)}" }

@Composable
fun TideLine(
    segments: List<TideSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    emphasizeLargest: Boolean = true,
) {
    val reduceMotion = rememberReduceMotion()
    var started by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) { started = true }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = if (reduceMotion) tween(0) else tween(600),
        label = "tide",
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val largest = segments.maxByOrNull { it.seconds }
    val colors = segments.map { CategoryPalette.colorFor(it.category) }
    val description = tideDescription(segments)
    val corner = 4.dp

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(corner))
            .semantics { contentDescription = description }
    ) {
        drawRect(track)
        val gap = 1.dp.toPx()
        var x = 0f
        segments.forEachIndexed { i, seg ->
            val w = size.width * seg.fraction.coerceIn(0f, 1f) * progress
            if (w <= 0f) return@forEachIndexed
            val alpha = if (emphasizeLargest && seg !== largest) 0.55f else 1f
            val drawW = if (i < segments.lastIndex) (w - gap).coerceAtLeast(0f) else w
            if (drawW > 0f) {
                drawRect(colors[i].copy(alpha = alpha), Offset(x, 0f), Size(drawW, size.height))
            }
            x += w
        }
    }
}
