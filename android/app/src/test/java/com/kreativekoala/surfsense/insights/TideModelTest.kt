package com.kreativekoala.surfsense.insights

import org.junit.Assert.assertEquals
import org.junit.Test

class TideModelTest {
    @Test fun rowSortsSegmentsDescAndComputesFractions() {
        val r = TideModel.row("Phone", mapOf("News" to 100, "Finance" to 300), 400)
        assertEquals(400, r.totalSeconds)
        assertEquals(listOf("Finance", "News"), r.segments.map { it.category })
        assertEquals(0.75f, r.segments[0].fraction, 1e-6f)
        assertEquals(0.25f, r.segments[1].fraction, 1e-6f)
    }

    @Test fun zeroScaleGivesZeroFractions() {
        val r = TideModel.row("Phone", mapOf("News" to 100), 0)
        assertEquals(100, r.totalSeconds)
        assertEquals(0f, r.segments.single().fraction, 0f)
    }

    @Test fun emptyRowHasNoSegmentsAndNoNaN() {
        val r = TideModel.row("Phone", emptyMap(), 0)
        assertEquals(0, r.totalSeconds)
        assertEquals(emptyList<TideSegment>(), r.segments)
    }

    @Test fun fractionClampedToOne() {
        val r = TideModel.row("x", mapOf("News" to 500), 100)
        assertEquals(1f, r.segments.single().fraction, 0f)
    }

    @Test fun zeroSecondSegmentsDropped() {
        val r = TideModel.row("x", mapOf("News" to 0, "Food" to 10), 10)
        assertEquals(listOf("Food"), r.segments.map { it.category })
    }

    @Test fun rowsShareScaleAndPrependAll() {
        val rows = TideModel.rows(
            listOf(
                "Phone" to mapOf("News" to 100, "Food" to 100),
                "Tablet" to mapOf("News" to 50),
            ),
        )
        assertEquals(listOf("All", "Phone", "Tablet"), rows.map { it.label })
        val all = rows[0]
        assertEquals(250, all.totalSeconds)
        assertEquals(1f, all.segments.sumOf { it.fraction.toDouble() }.toFloat(), 1e-5f)
        assertEquals(1f, rows[1].segments.sumOf { it.fraction.toDouble() }.toFloat(), 1e-5f)
        assertEquals(0.25f, rows[2].segments.single().fraction, 1e-6f)
    }

    @Test fun rowsCombinedMergesCategories() {
        val rows = TideModel.rows(listOf("A" to mapOf("News" to 10), "B" to mapOf("News" to 20)))
        assertEquals(30, rows[0].segments.single().seconds)
    }

    @Test fun rowsWithAllZeroDevices() {
        val rows = TideModel.rows(listOf("A" to emptyMap(), "B" to mapOf("News" to 0)))
        assertEquals(3, rows.size)
        assertEquals(0, rows[0].totalSeconds)
        assertEquals(emptyList<TideSegment>(), rows[0].segments)
    }

    @Test fun rowsWithNoDevices() {
        val rows = TideModel.rows(emptyList())
        assertEquals(listOf("All"), rows.map { it.label })
        assertEquals(0, rows[0].totalSeconds)
    }

    @Test fun canonicalCategory() {
        assertEquals("Social Media", TideModel.canonicalCategory("social"))
        assertEquals("Social Media", TideModel.canonicalCategory(" SOCIAL MEDIA "))
        assertEquals("Work/Productivity", TideModel.canonicalCategory("Productivity"))
        assertEquals("Work/Productivity", TideModel.canonicalCategory("work"))
        assertEquals("News", TideModel.canonicalCategory("news"))
        assertEquals("Other", TideModel.canonicalCategory("Gardening"))
        assertEquals("Other", TideModel.canonicalCategory(""))
        assertEquals("Other", TideModel.canonicalCategory("   "))
    }
}
