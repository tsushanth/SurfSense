package com.kreativekoala.surfsense.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeekCompareTest {
    private val today = "2026-10-13"

    @Test fun perDayIsSevenEntriesOldestToToday() {
        val r = WeekCompare.compare(mapOf("2026-10-13" to 5, "2026-10-07" to 9), today)
        assertEquals(7, r.perDay.size)
        assertEquals("2026-10-07" to 9, r.perDay.first())
        assertEquals("2026-10-13" to 5, r.perDay.last())
        assertEquals(0, r.perDay[1].second)
        assertEquals(14, r.thisWeekSeconds)
    }

    @Test fun lastWeekEmptyGivesNulls() {
        val r = WeekCompare.compare(mapOf("2026-10-13" to 100), today)
        assertEquals(100, r.thisWeekSeconds)
        assertNull(r.lastWeekSeconds)
        assertNull(r.deltaPercent)
    }

    @Test fun noDataAtAll() {
        val r = WeekCompare.compare(emptyMap(), today)
        assertEquals(0, r.thisWeekSeconds)
        assertNull(r.lastWeekSeconds)
        assertNull(r.deltaPercent)
    }

    @Test fun positiveDelta() {
        val r = WeekCompare.compare(mapOf("2026-10-13" to 3600, "2026-10-06" to 2400), today)
        assertEquals(2400, r.lastWeekSeconds)
        assertEquals(50, r.deltaPercent)
    }

    @Test fun negativeDelta() {
        val r = WeekCompare.compare(mapOf("2026-10-13" to 1000, "2026-10-01" to 2000), today)
        assertEquals(-50, r.deltaPercent)
    }

    @Test fun deltaRounds() {
        // (100-300)*100/300 = -66.67 -> -67
        val r = WeekCompare.compare(mapOf("2026-10-13" to 100, "2026-10-05" to 300), today)
        assertEquals(-67, r.deltaPercent)
    }

    @Test fun lastWeekPresentButZeroSecondsGivesNullDelta() {
        val r = WeekCompare.compare(mapOf("2026-10-13" to 100, "2026-10-05" to 0), today)
        assertEquals(0, r.lastWeekSeconds)
        assertNull(r.deltaPercent)
    }

    @Test fun windowBoundaries() {
        // 10-07 is first day of this week, 10-06 last day of last week, 09-30 is first of last week, 09-29 outside.
        val r = WeekCompare.compare(
            mapOf("2026-10-07" to 1, "2026-10-06" to 10, "2026-09-30" to 100, "2026-09-29" to 1000, "2026-10-14" to 5000),
            today,
        )
        assertEquals(1, r.thisWeekSeconds)
        assertEquals(110, r.lastWeekSeconds)
    }

    @Test fun crossesMonthAndYear() {
        val r = WeekCompare.compare(mapOf("2027-01-02" to 7, "2026-12-27" to 3), "2027-01-02")
        assertEquals("2026-12-27", r.perDay.first().first)
        assertEquals(10, r.thisWeekSeconds)
    }
}
