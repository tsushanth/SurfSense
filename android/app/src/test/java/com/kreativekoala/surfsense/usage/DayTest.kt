package com.kreativekoala.surfsense.usage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class DayTest {
    private val la = TimeZone.getTimeZone("America/Los_Angeles")
    private fun ms(z: String, y: Int, m: Int, d: Int, h: Int, min: Int) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneId.of(z)).toInstant().toEpochMilli()

    @Test fun startOfDayIsLocalMidnight() {
        val now = ms("America/Los_Angeles", 2026, 10, 12, 23, 30)
        assertEquals(ms("America/Los_Angeles", 2026, 10, 12, 0, 0), Day.startOfDayMs(now, la))
    }

    @Test fun dayStringUsesTheLocalDateNotUtc() {
        // 23:30 in Los Angeles on Oct 12 is already Oct 13 in UTC
        val now = ms("America/Los_Angeles", 2026, 10, 12, 23, 30)
        assertEquals("2026-10-12", Day.dayString(now, la))
        assertEquals("2026-10-13", Day.dayString(now, TimeZone.getTimeZone("UTC")))
    }

    @Test fun midnightBelongsToTheNewDay() {
        val now = ms("America/Los_Angeles", 2026, 10, 13, 0, 0)
        assertEquals("2026-10-13", Day.dayString(now, la))
        assertEquals(now, Day.startOfDayMs(now, la))
    }

    @Test fun daylightSavingDayStartsAtLocalMidnight() {
        // US fall-back day is 2026-11-01 (25-hour day)
        val now = ms("America/Los_Angeles", 2026, 11, 1, 12, 0)
        assertEquals(ms("America/Los_Angeles", 2026, 11, 1, 0, 0), Day.startOfDayMs(now, la))
        assertEquals("2026-11-01", Day.dayString(now, la))
    }
}
