package com.kreativekoala.surfsense.insights

import com.kreativekoala.surfsense.history.DeviceDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRowsTest {
    @Test fun emptyHistoryGivesNoRows() {
        assertTrue(ExportRows.from(emptyList()).isEmpty())
    }

    @Test fun oneRowPerDeviceDayCategory() {
        val rows = ExportRows.from(
            listOf(
                DeviceDay("p1", "Pixel", "2026-10-12", mapOf("Social Media" to 60, "News" to 30)),
                DeviceDay("l1", "Laptop", "2026-10-13", mapOf("Work/Productivity" to 120)),
            ),
        )
        assertEquals(
            setOf(
                ExportRow("2026-10-12", "Pixel", "Social Media", 60),
                ExportRow("2026-10-12", "Pixel", "News", 30),
                ExportRow("2026-10-13", "Laptop", "Work/Productivity", 120),
            ),
            rows.toSet(),
        )
        assertEquals(3, rows.size)
    }

    @Test fun zeroAndNegativeSecondsAreSkipped() {
        val rows = ExportRows.from(listOf(DeviceDay("p1", "Pixel", "2026-10-12", mapOf("News" to 0, "Food" to -5, "Other" to 1))))
        assertEquals(listOf(ExportRow("2026-10-12", "Pixel", "Other", 1)), rows)
    }

    @Test fun sameNameDifferentDevicesAreKeptApart() {
        val rows = ExportRows.from(
            listOf(
                DeviceDay("aaaaaa111", "Phone", "2026-10-12", mapOf("News" to 10)),
                DeviceDay("bbbbbb222", "Phone", "2026-10-12", mapOf("News" to 20)),
                DeviceDay("cccccc333", "Laptop", "2026-10-12", mapOf("News" to 30)),
            ),
        )
        assertEquals(
            setOf("Phone (aaaaaa)", "Phone (bbbbbb)", "Laptop"),
            rows.map { it.device }.toSet(),
        )
    }

    @Test fun sameDeviceOnSeveralDaysKeepsItsPlainName() {
        val rows = ExportRows.from(
            listOf(
                DeviceDay("p1", "Phone", "2026-10-12", mapOf("News" to 10)),
                DeviceDay("p1", "Phone", "2026-10-13", mapOf("News" to 20)),
            ),
        )
        assertEquals(setOf("Phone"), rows.map { it.device }.toSet())
    }

    @Test fun commaInNameSurvivesCsv() {
        val csv = CsvExport.build(ExportRows.from(listOf(DeviceDay("p1", "Sam's phone, old", "2026-10-12", mapOf("News" to 5)))))
        assertEquals("date,device,category,seconds\n2026-10-12,\"Sam's phone, old\",News,5\n", csv)
    }

    @Test fun fileNameUsesDate() {
        assertEquals("surfsense-2026-10-13.csv", ExportRows.fileName("2026-10-13"))
    }
}
