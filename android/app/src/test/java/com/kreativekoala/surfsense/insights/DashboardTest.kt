package com.kreativekoala.surfsense.insights

import com.kreativekoala.surfsense.history.DeviceDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DashboardTest {
    private val phone = DeviceToday("p", "Pixel", "ANDROID", true, mapOf("Social Media" to 600, "News" to 60))
    private val laptop = DeviceToday("l", "Laptop", "CHROME_EXTENSION", false, mapOf("News" to 300, "Games" to 40))
    private val idle = DeviceToday("t", "Tablet", "ANDROID", false, emptyMap())
    private val devices = listOf(phone, laptop, idle)

    @Test fun orderPutsThisDeviceFirstAndDropsDuplicates() {
        val ordered = TodayModel.orderDevices(phone, listOf(laptop, phone.copy(isThisDevice = false), laptop, idle))
        assertEquals(listOf("p", "l", "t"), ordered.map { it.id })
    }

    @Test fun scopeFallsBackToAllWhenDeviceDisappears() {
        assertEquals("l", TodayModel.resolveScope(devices, "l"))
        assertNull(TodayModel.resolveScope(devices, "gone"))
        assertNull(TodayModel.resolveScope(devices, null))
    }

    @Test fun scopeCategoriesCombineOrPick() {
        assertEquals(mapOf("Social Media" to 600, "News" to 360, "Games" to 40), TodayModel.scopeCategories(devices, null))
        assertEquals(mapOf("News" to 300, "Games" to 40), TodayModel.scopeCategories(devices, "l"))
        assertEquals(emptyMap<String, Int>(), TodayModel.scopeCategories(devices, "t"))
    }

    @Test fun emptyDevicesHaveNoDataAndZeroWidthRows() {
        assertFalse(TodayModel.hasAnyData(listOf(idle, idle.copy(id = "x", byCategory = mapOf("News" to 0)))))
        assertTrue(TodayModel.hasAnyData(devices))
        val row = TodayModel.scopeRow(listOf(idle), null)
        assertEquals(0, row.totalSeconds)
        assertTrue(row.segments.isEmpty())
    }

    @Test fun deviceRowsShareTheLargestDeviceScale() {
        val rows = TodayModel.deviceRows(devices)
        assertEquals(listOf("Pixel", "Laptop", "Tablet"), rows.map { it.label })
        assertEquals(1f, rows[0].segments.sumOf { it.fraction.toDouble() }.toFloat(), 0.001f)
        assertEquals(340f / 660f, rows[1].segments.sumOf { it.fraction.toDouble() }.toFloat(), 0.001f)
        assertEquals(0, rows[2].totalSeconds)
    }

    @Test fun overrideWinsThenServerThenUnknown() {
        val server = mapOf("a" to "News", "b" to "Social Media")
        assertEquals("Health", TodayModel.currentCategory("a", mapOf("a" to "Health"), server))
        assertEquals("Social Media", TodayModel.currentCategory("b", emptyMap(), server))
        assertNull(TodayModel.currentCategory("c", emptyMap(), server))
    }

    @Test fun weekCombinesDevicesPerDayAndAddsGhostTotals() {
        val rows = listOf(
            DeviceDay("p", "Pixel", "2026-10-13", mapOf("News" to 100)),
            DeviceDay("l", "Laptop", "2026-10-13", mapOf("News" to 50, "Food" to 10)),
            DeviceDay("p", "Pixel", "2026-10-07", mapOf("Social Media" to 200)),
            DeviceDay("p", "Pixel", "2026-10-06", mapOf("Social Media" to 120)), // last week, same weekday as 10-13
            DeviceDay("l", "Laptop", "2026-09-30", mapOf("News" to 999)), // last week too: 10-07 minus 7
        )
        val v = WeekModel.build(rows, "2026-10-13")
        assertEquals(7, v.days.size)
        assertEquals("2026-10-07", v.days.first().date)
        assertEquals("2026-10-13", v.days.last().date)
        assertEquals(mapOf("News" to 150, "Food" to 10), v.days.last().byCategory)
        assertEquals(160, v.days.last().total)
        assertEquals(120, v.days.last().lastWeekTotal)
        assertEquals(999, v.days.first().lastWeekTotal)
        assertNull(v.days[1].lastWeekTotal)
        assertEquals(360, v.comparison.thisWeekSeconds)
        assertEquals(1119, v.comparison.lastWeekSeconds)
        assertEquals(mapOf("News" to 150, "Food" to 10, "Social Media" to 200), v.categories)
        assertEquals(999, WeekModel.scale(v.days))
    }

    @Test fun emptyWeekHasNoDeltaAndZeroScale() {
        val v = WeekModel.build(emptyList(), "2026-03-01")
        assertEquals(0, v.comparison.thisWeekSeconds)
        assertNull(v.comparison.lastWeekSeconds)
        assertNull(v.comparison.deltaPercent)
        assertEquals("2026-02-23", v.days.first().date)
        assertTrue(v.days.all { it.total == 0 && it.lastWeekTotal == null })
        assertEquals(0, WeekModel.scale(v.days))
    }

    @Test fun weekdayLabels() {
        assertEquals("T", WeekModel.weekdayLetter("2026-10-13", Locale.US))
        assertEquals("Tuesday", WeekModel.weekdayName("2026-10-13", Locale.US))
        assertEquals("bad", WeekModel.weekdayLetter("bad", Locale.US))
    }
}
