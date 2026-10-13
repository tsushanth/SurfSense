package com.kreativekoala.surfsense.ui.components

import androidx.compose.ui.graphics.Color
import com.kreativekoala.surfsense.insights.TideModel

/** One hue per category, identical in light and dark and on every platform. Hue means category. */
object CategoryPalette {
    private val HUES: Map<String, Color> = mapOf(
        "Social Media" to Color(0xFF3E7CB1),
        "Entertainment" to Color(0xFF8E5BB5),
        "Work/Productivity" to Color(0xFF2F7F73),
        "News" to Color(0xFFC98A2B),
        "Education" to Color(0xFF2A9DB0),
        "Finance" to Color(0xFF5A8F3C),
        "Health" to Color(0xFFC2577F),
        "Shopping" to Color(0xFFB8A12D),
        "Travel" to Color(0xFF5B5FC7),
        "Food" to Color(0xFFC8553D),
        "Other" to Color(0xFF8A979C),
    )

    /** Unknown or blank names resolve to the "Other" hue. */
    fun colorFor(category: String): Color = HUES.getValue(TideModel.canonicalCategory(category))
}
