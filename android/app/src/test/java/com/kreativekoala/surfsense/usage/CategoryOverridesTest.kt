package com.kreativekoala.surfsense.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryOverridesTest {
    private class MemStore(var data: Map<String, String> = emptyMap()) : CategoryOverrides.Store {
        override fun load() = data
        override fun save(map: Map<String, String>) { data = map }
    }

    @Test fun setAndAllPersistThroughStore() {
        val store = MemStore()
        CategoryOverrides(store).set("com.a", "News")
        assertEquals(mapOf("com.a" to "News"), CategoryOverrides(store).all())
    }

    @Test fun blankCategoryRemoves() {
        val o = CategoryOverrides(MemStore(mapOf("com.a" to "News")))
        o.set("com.a", "  ")
        assertEquals(emptyMap<String, String>(), o.all())
    }

    @Test fun removeIsHarmlessWhenAbsent() {
        val o = CategoryOverrides(MemStore(mapOf("com.a" to "News")))
        o.remove("com.zzz")
        o.remove("com.a")
        assertEquals(emptyMap<String, String>(), o.all())
    }

    @Test fun overridesWinOverMapping() {
        val o = CategoryOverrides(MemStore(mapOf("com.a" to "News")))
        val out = o.apply(mapOf("com.a" to "Social Media", "com.b" to "Food"), listOf("com.a", "com.b"))
        assertEquals(mapOf("com.a" to "News", "com.b" to "Food"), out)
    }

    @Test fun overrideAppliesToPackageMissingFromMapping() {
        val o = CategoryOverrides(MemStore(mapOf("com.a" to "News")))
        assertEquals(mapOf("com.a" to "News"), o.apply(emptyMap(), listOf("com.a")))
    }

    @Test fun unknownPackagesOmittedAndStaleOverridesKeptButIgnored() {
        val o = CategoryOverrides(MemStore(mapOf("com.gone" to "News")))
        val out = o.apply(mapOf("com.a" to "Food"), listOf("com.a", "com.c"))
        assertEquals(mapOf("com.a" to "Food"), out)
        assertEquals(mapOf("com.gone" to "News"), o.all())
    }
}
