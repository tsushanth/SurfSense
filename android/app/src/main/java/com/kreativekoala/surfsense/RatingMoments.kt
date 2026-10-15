package com.kreativekoala.surfsense

import android.content.Context
import java.time.LocalDate

/** At most one rating "moment" per calendar day, however often the Week screen is reopened. */
object RatingMoments {
    private const val PREFS = "surfsense_rating"
    private const val KEY_LAST_DAY = "last_moment_day"

    fun markToday(context: Context): Boolean {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (p.getString(KEY_LAST_DAY, null) == today) return false
        p.edit().putString(KEY_LAST_DAY, today).apply()
        return true
    }
}
