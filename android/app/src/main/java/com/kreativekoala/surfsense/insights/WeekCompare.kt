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
     * lastWeek = the 7 before that. thisWeekSeconds and lastWeekSeconds cover all 7 days (for the headline and
     * the ghost columns); lastWeekSeconds is null when no day of last week has an entry. perDay has 7 entries,
     * oldest to today, missing days 0.
     *
     * deltaPercent compares completed days only, like for like: the 6 days before today against the same 6
     * weekdays a week earlier (today, which is still running, and today-7 are left out). It is null unless last
     * week has an entry on at least [MIN_COMPARABLE_DAYS] of those 6 days, and null when their sum is 0, so a
     * user who started a few days ago (or a mostly failed load) does not see "+600%".
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
        val lastDone = lastKeys.subList(0, 6)
        val thisDone = thisKeys.subList(0, 6)
        val lastComparable = lastDone.sumOf { days[it] ?: 0 }
        val thisComparable = thisDone.sumOf { days[it] ?: 0 }
        val delta = if (lastDone.count { it in days } < MIN_COMPARABLE_DAYS || lastComparable == 0) null
        else Math.round((thisComparable - lastComparable) * 100.0 / lastComparable).toInt()
        return WeekComparison(thisTotal, lastTotal, delta, perDay)
    }

    private const val MIN_COMPARABLE_DAYS = 3
}
