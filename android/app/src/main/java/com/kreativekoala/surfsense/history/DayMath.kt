package com.kreativekoala.surfsense.history

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** [day] (yyyy-MM-dd) minus [n] days. Calendar-based: no desugaring on minSdk 24. */
fun dayMinus(day: String, n: Int): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
    cal.time = fmt.parse(day)!!
    cal.add(Calendar.DAY_OF_MONTH, -n)
    return fmt.format(cal.time)
}
