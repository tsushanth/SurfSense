package com.kreativekoala.surfsense.insights

import com.kreativekoala.surfsense.history.DeviceDay

object ExportRows {
    /**
     * One row per device, day and category with time above zero. Two different devices with the same name get the
     * first 6 characters of their id appended, so their rows stay apart in the file.
     */
    fun from(days: List<DeviceDay>): List<ExportRow> {
        val idsByName = days.groupBy { it.deviceName }.mapValues { (_, v) -> v.map { it.deviceId }.toSet() }
        return days.flatMap { day ->
            val label = if ((idsByName[day.deviceName]?.size ?: 0) > 1) {
                "${day.deviceName} (${day.deviceId.take(6)})"
            } else day.deviceName
            day.byCategory.filter { it.value > 0 }.map { (cat, secs) -> ExportRow(day.date, label, cat, secs) }
        }
    }

    /** surfsense-YYYY-MM-DD.csv for [today] in yyyy-MM-dd (device-local date). */
    fun fileName(today: String): String = "surfsense-$today.csv"
}
