package com.kreativekoala.surfsense.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kreativekoala.surfsense.R

/**
 * Hanken Grotesk (OFL) variable font. Variation settings need API 26+; on API 24-25 they are
 * ignored and the file's default weight renders for every weight, which is acceptable.
 */
@OptIn(ExperimentalTextApi::class)
val HankenGrotesk = FontFamily(
    listOf(400, 500, 600).map { w ->
        Font(
            R.font.hanken_grotesk,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    }
)

private fun style(weight: FontWeight, size: Int, line: Int) = TextStyle(
    fontFamily = HankenGrotesk,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
)

val Typography = Typography(
    headlineLarge = style(FontWeight.SemiBold, 34, 40),
    titleLarge = style(FontWeight.SemiBold, 20, 26),
    titleMedium = style(FontWeight.SemiBold, 16, 24),
    bodyLarge = style(FontWeight.Normal, 16, 24),
    bodyMedium = style(FontWeight.Medium, 14, 20),
    bodySmall = style(FontWeight.Normal, 12, 16),
    labelLarge = style(FontWeight.Medium, 14, 20),
    labelMedium = style(FontWeight.Medium, 12, 16),
    labelSmall = style(FontWeight.Medium, 12, 16),
)

/** Tabular (fixed-width) digits so durations line up and do not jitter. */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
