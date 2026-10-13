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

    /** [secs] on each of the 14 days ending [today], with overrides. */
    private fun days(secs: Int, vararg overrides: Pair<String, Int>): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        for (i in 0 until 14) m[com.kreativekoala.surfsense.history.dayMinus(today, i)] = secs
        m.putAll(overrides)
        return m
    }

    @Test fun flatUsageWithPartialTodayIsZeroPercent() {
        // 3 h every day, only 30 min so far today: not "12% less".
        val r = WeekCompare.compare(days(10800, "2026-10-13" to 1800), today)
        assertEquals(0, r.deltaPercent)
        assertEquals(6 * 10800 + 1800, r.thisWeekSeconds)
        assertEquals(7 * 10800, r.lastWeekSeconds)
    }

    @Test fun todayAndItsPartnerAreExcluded() {
        val r = WeekCompare.compare(days(100, "2026-10-13" to 99999, "2026-10-06" to 1), today)
        assertEquals(0, r.deltaPercent)
        assertEquals(6 * 100 + 99999, r.thisWeekSeconds)
        assertEquals(6 * 100 + 1, r.lastWeekSeconds)
    }

    @Test fun positiveDelta() {
        // This week's completed days 150 each, last week's 100 each.
        val m = HashMap<String, Int>()
        for (i in 1..6) m[com.kreativekoala.surfsense.history.dayMinus(today, i)] = 150
        for (i in 8..13) m[com.kreativekoala.surfsense.history.dayMinus(today, i)] = 100
        val r = WeekCompare.compare(m, today)
        assertEquals(50, r.deltaPercent)
        assertEquals(600, r.lastWeekSeconds)
    }

    @Test fun negativeDelta() {
        val m = mapOf("2026-10-08" to 1000, "2026-09-30" to 1000, "2026-10-01" to 500, "2026-10-02" to 500)
        val r = WeekCompare.compare(m, today)
        assertEquals(-50, r.deltaPercent)
    }

    @Test fun deltaRounds() {
        // (100-300)*100/300 = -66.67 -> -67
        val m = mapOf("2026-10-12" to 100, "2026-10-01" to 100, "2026-10-02" to 100, "2026-10-03" to 100)
        val r = WeekCompare.compare(m, today)
        assertEquals(-67, r.deltaPercent)
    }

    @Test fun onlyTwoDataDaysLastWeekGivesNullDelta() {
        val r = WeekCompare.compare(mapOf("2026-10-12" to 3600, "2026-10-01" to 600, "2026-10-02" to 600), today)
        assertEquals(1200, r.lastWeekSeconds)
        assertNull(r.deltaPercent)
    }

    @Test fun lastWeekDataOnlyOnTodayPartnerGivesNullDelta() {
        // 10-06 is the same weekday as today; it is not a comparable completed day.
        val r = WeekCompare.compare(mapOf("2026-10-12" to 100, "2026-10-06" to 300), today)
        assertEquals(300, r.lastWeekSeconds)
        assertNull(r.deltaPercent)
    }

    @Test fun lastWeekPresentButZeroSecondsGivesNullDelta() {
        val m = mapOf("2026-10-12" to 100, "2026-10-01" to 0, "2026-10-02" to 0, "2026-10-03" to 0)
        val r = WeekCompare.compare(m, today)
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
