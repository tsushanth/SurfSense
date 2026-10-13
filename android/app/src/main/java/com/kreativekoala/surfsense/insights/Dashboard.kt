package com.kreativekoala.surfsense.insights

import com.kreativekoala.surfsense.history.DeviceDay
import com.kreativekoala.surfsense.history.dayMinus
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One device's category totals for today. [byCategory] may be empty (device has not synced today). */
data class DeviceToday(
    val id: String,
    val name: String,
    val type: String,
    val isThisDevice: Boolean,
    val byCategory: Map<String, Int>,
)

/** Pure helpers behind the Today screen. */
object TodayModel {
    /** This device first, then linked devices in order, without duplicates or a second copy of this device. */
    fun orderDevices(self: DeviceToday, linked: List<DeviceToday>): List<DeviceToday> =
        listOf(self) + linked.filter { it.id != self.id }.distinctBy { it.id }

    /** The selected device id if it still exists, else null (All devices). */
    fun resolveScope(devices: List<DeviceToday>, scope: String?): String? =
        scope?.takeIf { s -> devices.any { it.id == s } }

    /** Category totals for [scope]: every device combined when null, else that device's (empty if unknown). */
    fun scopeCategories(devices: List<DeviceToday>, scope: String?): Map<String, Int> {
        val picked = if (scope == null) devices else devices.filter { it.id == scope }
        val out = LinkedHashMap<String, Int>()
        for (d in picked) for ((c, s) in d.byCategory) if (s > 0) out[c] = (out[c] ?: 0) + s
        return out
    }

    fun hasAnyData(devices: List<DeviceToday>): Boolean = devices.any { d -> d.byCategory.values.any { it > 0 } }

    /** Tide row for the scope header: its own total is the scale, so it is full width whenever it has data. */
    fun scopeRow(devices: List<DeviceToday>, scope: String?): TideRow {
        val cats = scopeCategories(devices, scope)
        return TideModel.row(scope ?: "All", cats, cats.values.sum())
    }

    /** One row per device, all on one shared scale (the largest device total), same order as [devices]. */
    fun deviceRows(devices: List<DeviceToday>): List<TideRow> =
        TideModel.rows(devices.map { it.name to it.byCategory }).drop(1)

    /** Category shown for an app: the user's override, else the server's answer, else null (unknown). */
    fun currentCategory(pkg: String, overrides: Map<String, String>, server: Map<String, String>): String? =
        overrides[pkg] ?: server[pkg]
}

/** One column of the week chart. [lastWeekTotal] is the same weekday a week earlier, null when that day has no data. */
data class WeekDay(
    val date: String,
    val byCategory: Map<String, Int>,
    val total: Int,
    val lastWeekTotal: Int?,
)

data class WeekView(
    val comparison: WeekComparison,
    val days: List<WeekDay>,
    /** Category totals over this week, all devices combined. */
    val categories: Map<String, Int>,
)

/** Pure helpers behind the Week screen. */
object WeekModel {
    /** Combines every device per day; only days with at least one row appear. */
    fun byDay(rows: List<DeviceDay>): Map<String, Map<String, Int>> {
        val out = HashMap<String, HashMap<String, Int>>()
        for (r in rows) {
            val day = out.getOrPut(r.date) { HashMap() }
            for ((c, s) in r.byCategory) if (s > 0) day[c] = (day[c] ?: 0) + s
        }
        return out
    }

    fun build(rows: List<DeviceDay>, today: String): WeekView {
        val perDay = byDay(rows)
        val totals = perDay.mapValues { it.value.values.sum() }
        val comparison = WeekCompare.compare(totals, today)
        val days = comparison.perDay.map { (date, total) ->
            WeekDay(date, perDay[date] ?: emptyMap(), total, totals[dayMinus(date, 7)])
        }
        val categories = HashMap<String, Int>()
        for (d in days) for ((c, s) in d.byCategory) categories[c] = (categories[c] ?: 0) + s
        return WeekView(comparison, days, categories)
    }

    /** Height scale shared by all columns and ghost columns; 0 when there is nothing to draw. */
    fun scale(days: List<WeekDay>): Int = days.maxOfOrNull { maxOf(it.total, it.lastWeekTotal ?: 0) } ?: 0

    private fun format(pattern: String, date: String, locale: Locale): String {
        val utc = TimeZone.getTimeZone("UTC")
        val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
        val out = SimpleDateFormat(pattern, locale).apply { timeZone = utc }
        return try {
            out.format(parse.parse(date)!!)
        } catch (e: Exception) {
            date
        }
    }

    /**
     * Single weekday letter ("T" for a Tuesday in English). Android's ICU-backed SimpleDateFormat
     * gives the narrow form for "EEEEE"; plain JVMs print the full name, so fall back to its first letter.
     */
    fun weekdayLetter(date: String, locale: Locale): String {
        val narrow = format("EEEEE", date, locale)
        if (narrow == date || narrow.codePointCount(0, narrow.length) <= 2) return narrow
        return String(Character.toChars(narrow.codePointAt(0))).uppercase(locale)
    }

    /** Full weekday name, for TalkBack. */
    fun weekdayName(date: String, locale: Locale): String = format("EEEE", date, locale)
}
