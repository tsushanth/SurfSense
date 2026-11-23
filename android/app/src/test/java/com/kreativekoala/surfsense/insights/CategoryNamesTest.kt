package com.kreativekoala.surfsense.insights

import com.kreativekoala.surfsense.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryNamesTest {
    @Test fun everyCanonicalCategoryHasADisplayName() {
        assertEquals(TideModel.CANONICAL.toSet(), CategoryNames.keys)
        for (c in TideModel.CANONICAL) assertNotNull(c, CategoryNames.resFor(c))
    }

    @Test fun displayNamesAreDistinctResources() {
        val ids = TideModel.CANONICAL.map { CategoryNames.resFor(it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun serverAliasesAndCaseResolveToTheSameName() {
        assertEquals(R.string.category_social_media, CategoryNames.resFor("social"))
        assertEquals(R.string.category_social_media, CategoryNames.resFor(" SOCIAL MEDIA "))
        assertEquals(R.string.category_work_productivity, CategoryNames.resFor("work"))
        assertEquals(R.string.category_work_productivity, CategoryNames.resFor("Productivity"))
    }

    @Test fun unknownCategoryHasNoNameSoTheRawValueIsShown() {
        assertNull(CategoryNames.resFor("Mystery"))
        assertNull(CategoryNames.resFor(""))
    }

    @Test fun storageKeysAreUnchanged() {
        // These strings are stored, synced to the backend and shared with the browser extension.
        assertEquals(
            listOf(
                "Social Media", "Entertainment", "Work/Productivity", "News", "Education",
                "Finance", "Health", "Shopping", "Travel", "Food", "Other",
            ),
            TideModel.CANONICAL,
        )
        assertEquals("Social Media", TideModel.canonicalCategory("social"))
        assertEquals("Other", TideModel.canonicalCategory("Mystery"))
        assertTrue(TideModel.knownCategory("Mystery") == null)
    }
}
