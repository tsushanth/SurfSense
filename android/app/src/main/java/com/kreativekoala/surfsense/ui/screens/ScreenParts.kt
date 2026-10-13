package com.kreativekoala.surfsense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kreativekoala.surfsense.R

/**
 * A sentence such as "6h 12m on your 3 devices today" where the duration is set large with tabular figures.
 * The duration is a format argument, so translations can put it anywhere in the sentence.
 */
@Composable
internal fun headlineSentence(sentence: String, duration: String): AnnotatedString {
    val big = MaterialTheme.typography.headlineLarge
    val at = sentence.indexOf(duration)
    return buildAnnotatedString {
        if (at < 0) {
            append(sentence)
            return@buildAnnotatedString
        }
        append(sentence.substring(0, at))
        withStyle(
            SpanStyle(
                fontSize = big.fontSize,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                color = MaterialTheme.colorScheme.onSurface,
            )
        ) { append(duration) }
        append(sentence.substring(at + duration.length))
    }
}

/** Style for [headlineSentence]: body text at section size with the large line height so wrapped lines never overlap. */
@Composable
internal fun headlineStyle() = MaterialTheme.typography.titleLarge.copy(
    lineHeight = MaterialTheme.typography.headlineLarge.lineHeight,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Section title for Devices and Settings: titleMedium, announced as a heading. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/** Loading placeholder: quiet grey lines in the shape of the content, no spinner. */
@Composable
internal fun SkeletonLines(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.loading)
    val shape = RoundedCornerShape(4.dp)
    val color = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth(0.7f).height(32.dp).background(color, shape))
        Box(Modifier.fillMaxWidth().height(28.dp).background(color, shape))
        Box(Modifier.fillMaxWidth(0.5f).height(16.dp).background(color, shape))
        Box(Modifier.fillMaxWidth(0.6f).height(16.dp).background(color, shape))
    }
}
