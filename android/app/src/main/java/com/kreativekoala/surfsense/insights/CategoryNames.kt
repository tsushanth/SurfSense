package com.kreativekoala.surfsense.insights

import android.content.Context
import androidx.annotation.StringRes
import com.kreativekoala.surfsense.R

/**
 * Display names for categories. The canonical English names ("Social Media", "Work/Productivity", ...) are storage and
 * sync keys shared with the backend and the browser extension: they are never changed or shown by themselves. Anything
 * the user reads goes through [resFor] / [label] so it follows the app language.
 */
object CategoryNames {
    private val RES: Map<String, Int> = mapOf(
        "Social Media" to R.string.category_social_media,
        "Entertainment" to R.string.category_entertainment,
        "Work/Productivity" to R.string.category_work_productivity,
        "News" to R.string.category_news,
        "Education" to R.string.category_education,
        "Finance" to R.string.category_finance,
        "Health" to R.string.category_health,
        "Shopping" to R.string.category_shopping,
        "Travel" to R.string.category_travel,
        "Food" to R.string.category_food,
        "Other" to R.string.category_other,
    )

    /** The canonical keys that have a display name; kept equal to [TideModel.CANONICAL] by a unit test. */
    val keys: Set<String> get() = RES.keys

    /** String resource for a canonical key or a server alias ("social", "work"); null for an unknown category. */
    @StringRes
    fun resFor(raw: String): Int? = TideModel.knownCategory(raw)?.let { RES[it] }

    /** Localized name; an unknown category (a server value we have no translation for) is shown as received. */
    fun label(context: Context, raw: String): String = resFor(raw)?.let { context.getString(it) } ?: raw
}
