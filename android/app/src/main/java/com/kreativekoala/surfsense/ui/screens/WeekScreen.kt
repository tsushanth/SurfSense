package com.kreativekoala.surfsense.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kreativekoala.surfsense.R
import com.kreativekoala.surfsense.history.DeviceDay
import com.kreativekoala.surfsense.insights.WeekDay
import com.kreativekoala.surfsense.insights.WeekModel
import com.kreativekoala.surfsense.insights.WeekView
import com.kreativekoala.surfsense.ui.components.CategoryPalette
import com.kreativekoala.surfsense.ui.components.CategoryRow
import com.kreativekoala.surfsense.ui.components.formatDuration
import com.kreativekoala.surfsense.ui.theme.SurfSenseTheme
import com.kreativekoala.surfsense.viewmodel.WeekState
import java.util.Locale
import kotlin.math.abs

/** This week (the 7 days ending today) against the 7 days before, every device combined. */
@Composable
fun WeekScreen(week: WeekState, onLoad: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { if (!week.loaded && !week.loading) onLoad() }
    val view = week.view

    // Ratings: the user is looking at a real week of their own data (the app's value). RatingKit decides whether to ask:
    // it needs several such days and a few days of use first. A failed day load silences it for a while.
    val ratingContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(view?.comparison?.thisWeekSeconds, week.failedDays) {
        if (week.failedDays > 0) {
            com.kreativekoala.ratingkit.RatingKit.reportError()
        } else if ((view?.comparison?.thisWeekSeconds ?: 0) > 0 && com.kreativekoala.surfsense.RatingMoments.markToday(ratingContext)) {
            ratingContext.findRatingActivity()?.let { com.kreativekoala.ratingkit.RatingKit.trackAction(it) }
        }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        if (view == null) {
            item {
                if (!week.loaded || week.loading) SkeletonLines() else FailedLine(week.failedDays, onLoad)
            }
            return@LazyColumn
        }

        item { WeekHeader(view) }
        if (view.comparison.thisWeekSeconds == 0) {
            item {
                Text(
                    stringResource(R.string.week_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            item { WeekColumns(view.days, Modifier.padding(top = 20.dp)) }
            if (view.days.any { it.lastWeekTotal != null }) {
                item {
                    Text(
                        stringResource(R.string.week_ghost_legend),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (week.failedDays > 0) item { FailedLine(week.failedDays, onLoad, Modifier.padding(top = 12.dp)) }

        val cats = view.categories.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        if (cats.isNotEmpty()) {
            item { Hairline(Modifier.padding(vertical = 12.dp)) }
            val total = cats.sumOf { it.value }
            items(cats, key = { it.key }) { (cat, secs) ->
                CategoryRow(cat, secs, if (total > 0) secs.toFloat() / total else 0f)
            }
        }
    }
}

@Composable
private fun WeekHeader(view: WeekView) {
    val c = view.comparison
    val duration = formatDuration(c.thisWeekSeconds)
    Text(
        headlineSentence(stringResource(R.string.week_total, duration), duration),
        style = headlineStyle(),
    )
    val delta = c.deltaPercent ?: return
    Text(
        when {
            delta > 0 -> stringResource(R.string.week_more, delta)
            delta < 0 -> stringResource(R.string.week_less, abs(delta))
            else -> stringResource(R.string.week_same)
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun FailedLine(failedDays: Int, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val n = failedDays.coerceAtLeast(1)
    Column(modifier) {
        ErrorLine(LocalContext.current.resources.getQuantityString(R.plurals.week_failed, n, n))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.try_again))
        }
    }
}

/**
 * Seven stacked columns, oldest to today, on one scale. Behind each column a faint outline shows the same
 * weekday last week. Largest category in a column is at full strength, the rest at 55%, as in the tide line.
 */
@Composable
private fun WeekColumns(days: List<WeekDay>, modifier: Modifier = Modifier) {
    val scale = WeekModel.scale(days)
    val locale = Locale.getDefault()
    val ghost = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEachIndexed { index, day ->
            val isToday = index == days.lastIndex
            val name = WeekModel.weekdayName(day.date, locale)
            val description = day.lastWeekTotal?.let {
                stringResource(R.string.week_day_cd_last, name, formatDuration(day.total), formatDuration(it))
            } ?: stringResource(R.string.week_day_cd, name, formatDuration(day.total))
            val segments = day.byCategory.entries.filter { it.value > 0 }.sortedByDescending { it.value }
            val colors = segments.map { CategoryPalette.colorFor(it.key) }
            Column(
                Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                ) {
                    val corner = CornerRadius(3.dp.toPx())
                    // Baseline so empty days still read as a day, not a missing column.
                    drawRect(track, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
                    if (scale <= 0) return@Canvas
                    day.lastWeekTotal?.let { last ->
                        val h = size.height * last / scale
                        if (h > 0f) {
                            val stroke = 1.dp.toPx()
                            drawRoundRect(
                                ghost.copy(alpha = 0.6f),
                                topLeft = Offset(stroke / 2, size.height - h + stroke / 2),
                                size = Size(size.width - stroke, h - stroke),
                                cornerRadius = corner,
                                style = Stroke(stroke),
                            )
                        }
                    }
                    val inset = size.width * 0.2f
                    val gap = 1.dp.toPx()
                    var bottom = size.height
                    segments.forEachIndexed { i, seg ->
                        val h = size.height * seg.value / scale
                        val drawH = (h - gap).coerceAtLeast(0f)
                        if (drawH > 0f) {
                            drawRect(
                                colors[i].copy(alpha = if (i == 0) 1f else 0.55f),
                                topLeft = Offset(inset, bottom - h + gap),
                                size = Size(size.width - 2 * inset, drawH),
                            )
                        }
                        bottom -= h
                    }
                }
                Text(
                    WeekModel.weekdayLetter(day.date, locale),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private val previewWeek = WeekState(
    loaded = true,
    view = WeekModel.build(
        (0 until 14).flatMap { i ->
            val day = com.kreativekoala.surfsense.history.dayMinus("2026-10-13", i)
            if (i == 3) emptyList() else listOf(
                DeviceDay("p", "Pixel", day, mapOf("Social Media" to 3000 + i * 300, "News" to 900)),
                DeviceDay("l", "Laptop", day, mapOf("Work/Productivity" to 7000 - i * 200)),
            )
        },
        "2026-10-13",
    ),
    failedDays = 2,
)

@Preview(name = "Week", showBackground = true, heightDp = 1000)
@Preview(name = "Week 320dp 200%", showBackground = true, widthDp = 320, fontScale = 2f, heightDp = 1800)
@Composable
private fun WeekScreenPreview() {
    SurfSenseTheme {
        WeekScreen(previewWeek, {}, Modifier.background(MaterialTheme.colorScheme.background))
    }
}

@Preview(name = "Week loading 320dp 200%", showBackground = true, widthDp = 320, fontScale = 2f)
@Composable
private fun WeekLoadingPreview() {
    SurfSenseTheme {
        WeekScreen(WeekState(loading = true), {}, Modifier.background(MaterialTheme.colorScheme.background))
    }
}

private tailrec fun android.content.Context.findRatingActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findRatingActivity()
    else -> null
}
