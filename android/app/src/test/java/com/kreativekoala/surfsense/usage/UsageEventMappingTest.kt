package com.kreativekoala.surfsense.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageEventMappingTest {
    @Test fun nullPackageYieldsNull() = assertNull(toUsageEvent(null, 1, 10L, "C"))

    @Test fun blankPackageYieldsNull() {
        assertNull(toUsageEvent("", 1, 10L, "C"))
        assertNull(toUsageEvent("   ", 1, 10L, "C"))
    }

    @Test fun nullClassNameBecomesEmpty() =
        assertEquals(UsageEvent("a.b", 1, 10L, ""), toUsageEvent("a.b", 1, 10L, null))

    @Test fun normalEventPreservesFields() =
        assertEquals(UsageEvent("a.b", 2, 99L, "a.b.Main"), toUsageEvent("a.b", 2, 99L, "a.b.Main"))
}
