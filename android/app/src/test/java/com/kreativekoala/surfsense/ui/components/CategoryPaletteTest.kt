package com.kreativekoala.surfsense.ui.components

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CategoryPaletteTest {
    private fun argb(c: String) = CategoryPalette.colorFor(c).toArgb()

    @Test fun exactHexes() {
        assertEquals(0xFF3E7CB1.toInt(), argb("Social Media"))
        assertEquals(0xFF2F7F73.toInt(), argb("Work/Productivity"))
        assertEquals(0xFF8A979C.toInt(), argb("Other"))
        assertEquals(0xFFC8553D.toInt(), argb("Food"))
    }
    @Test fun unknownAndBlankFallBackToOther() {
        assertEquals(argb("Other"), argb("Quantum Basket Weaving"))
        assertEquals(argb("Other"), argb(""))
        assertEquals(argb("Other"), argb("   "))
    }
    @Test fun aliasesAndCaseResolve() {
        assertEquals(argb("Social Media"), argb("social"))
        assertEquals(argb("Social Media"), argb(" SOCIAL MEDIA "))
        assertEquals(argb("Work/Productivity"), argb("productivity"))
    }
    @Test fun elevenDistinctHues() {
        val names = listOf("Social Media", "Entertainment", "Work/Productivity", "News", "Education",
            "Finance", "Health", "Shopping", "Travel", "Food", "Other")
        assertEquals(11, names.map { argb(it) }.toSet().size)
        assertNotEquals(argb("Other"), argb("News"))
    }
}
