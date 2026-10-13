package com.kreativekoala.surfsense.insights

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExportTest {
    @Test fun emptyIsHeaderOnly() {
        assertEquals("date,device,category,seconds\n", CsvExport.build(emptyList()))
    }

    @Test fun sortsByDateDeviceCategory() {
        val csv = CsvExport.build(
            listOf(
                ExportRow("2026-10-13", "B", "News", 1),
                ExportRow("2026-10-12", "B", "News", 2),
                ExportRow("2026-10-12", "A", "News", 3),
                ExportRow("2026-10-12", "A", "Food", 4),
            ),
        )
        assertEquals(
            "date,device,category,seconds\n" +
                "2026-10-12,A,Food,4\n2026-10-12,A,News,3\n2026-10-12,B,News,2\n2026-10-13,B,News,1\n",
            csv,
        )
    }

    @Test fun quotesCommasQuotesAndNewlines() {
        val csv = CsvExport.build(
            listOf(
                ExportRow("2026-10-12", "a,b", "he said \"x\"", 1),
                ExportRow("2026-10-12", "line1\nline2", "Plain", 2),
            ),
        )
        assertEquals(
            "date,device,category,seconds\n" +
                "2026-10-12,\"a,b\",\"he said \"\"x\"\"\",1\n" +
                "2026-10-12,\"line1\nline2\",Plain,2\n",
            csv,
        )
    }
}
