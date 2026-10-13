package com.kreativekoala.surfsense.insights

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class WeekComparison(
    val thisWeekSeconds: Int,
    val lastWeekSeconds: Int?,
    val deltaPercent: Int?,
    val perDay: List<Pair<String, Int>>,
)

/** Rolling 7 days ending today versus the 7 days before. Uses Calendar (no desugaring on minSdk 24). */
object WeekCompare {
    /**
     * [days] maps yyyy-MM-dd to total seconds (all devices). thisWeek = today and 6 earlier days;
     * lastWeek = the 7 before that. lastWeekSeconds is null when no day of last week has an entry;
     * deltaPercent is null when lastWeek is null or 0, else round((this-last)*100/last).
     * perDay has 7 entries, oldest to today, missing days 0.
     */
    fun compare(days: Map<String, Int>, today: String): WeekComparison {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        cal.time = fmt.parse(today)!!
        cal.add(Calendar.DAY_OF_MONTH, -13)
        val labels = ArrayList<String>(14)
        repeat(14) {
            labels.add(fmt.format(cal.time))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        val lastKeys = labels.subList(0, 7)
        val thisKeys = labels.subList(7, 14)
        val perDay = thisKeys.map { it to (days[it] ?: 0) }
        val thisTotal = perDay.sumOf { it.second }
        val lastTotal: Int? = if (lastKeys.any { it in days }) lastKeys.sumOf { days[it] ?: 0 } else null
        val delta = if (lastTotal == null || lastTotal == 0) null
        else Math.round((thisTotal - lastTotal) * 100.0 / lastTotal).toInt()
        return WeekComparison(thisTotal, lastTotal, delta, perDay)
    }
}
