package com.kreativekoala.surfsense.insights

import java.util.Locale

/** One category's share of a tide line. [fraction] is of the row's scale, 0..1. */
data class TideSegment(val category: String, val seconds: Int, val fraction: Float)

data class TideRow(val label: String, val totalSeconds: Int, val segments: List<TideSegment>)

/** Pure geometry for the tide-line bars: which segments, how wide, on which scale. */
object TideModel {
    /** The 11 palette categories, in display order. These names are what the server and overrides use. */
    val CANONICAL = listOf(
        "Social Media", "Entertainment", "Work/Productivity", "News", "Education",
        "Finance", "Health", "Shopping", "Travel", "Food", "Other",
    )
    private val BY_LOWER = CANONICAL.associateBy { it.lowercase(Locale.ROOT) } + mapOf(
        "social" to "Social Media",
        "productivity" to "Work/Productivity",
        "work" to "Work/Productivity",
    )

    /**
     * Segments sorted by seconds desc (zero-second categories are dropped).
     * fraction = seconds / [scaleSeconds], clamped to 0..1; scaleSeconds <= 0 gives all fractions 0.
     */
    fun row(label: String, byCategory: Map<String, Int>, scaleSeconds: Int): TideRow {
        val segments = byCategory.entries
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (cat, secs) ->
                val f = if (scaleSeconds <= 0) 0f else (secs.toFloat() / scaleSeconds).coerceIn(0f, 1f)
                TideSegment(cat, secs, f)
            }
        return TideRow(label, segments.sumOf { it.seconds }, segments)
    }

    /**
     * Returns a first row labelled "All" (every device's categories combined, scale = its own total so
     * it is full width whenever it has data), followed by one row per device in input order. Device
     * rows share one scale: the largest device total, so their lengths are comparable.
     */
    fun rows(devices: List<Pair<String, Map<String, Int>>>): List<TideRow> {
        val combined = HashMap<String, Int>()
        for ((_, cats) in devices) for ((c, s) in cats) if (s > 0) combined[c] = (combined[c] ?: 0) + s
        val allRow = row("All", combined, combined.values.sum())
        val deviceTotals = devices.map { (_, cats) -> cats.values.filter { it > 0 }.sum() }
        val scale = deviceTotals.maxOrNull() ?: 0
        return listOf(allRow) + devices.map { (label, cats) -> row(label, cats, scale) }
    }

    /** Maps a server/user category name to a palette key; unknown or blank becomes "Other". */
    fun canonicalCategory(raw: String): String =
        BY_LOWER[raw.trim().lowercase(Locale.ROOT)] ?: "Other"
}
