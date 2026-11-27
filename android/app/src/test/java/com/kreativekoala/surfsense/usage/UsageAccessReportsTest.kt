package com.kreativekoala.surfsense.usage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class UsageAccessReportsTest {
    private class FakeStore : UsageAccessReports.Store {
        var p = emptySet<String>()
        var s = emptySet<String>()
        override fun pending() = p
        override fun sent() = s
        override fun save(pending: Set<String>, sent: Set<String>) { p = pending; s = sent }
    }

    private val store = FakeStore()
    private val delivered = ArrayList<String>()
    private var online = true

    private val reports = UsageAccessReports(store) { reason ->
        if (!online) throw IOException("offline")
        delivered.add(reason)
        true
    }

    @Test fun eachReasonIsSentOnce() = runBlocking {
        reports.record(AccessRefusal.NOT_NOW)
        reports.record(AccessRefusal.NOT_NOW)
        reports.flush()
        reports.record(AccessRefusal.NOT_NOW)
        reports.record(AccessRefusal.RETURNED_WITHOUT_ACCESS)
        reports.flush()
        reports.flush()
        assertEquals(listOf("not_now", "returned_without_access"), delivered)
    }

    @Test fun aReportThatCannotBeSentIsKeptForLater() = runBlocking {
        online = false
        reports.record(AccessRefusal.SETTINGS_UNAVAILABLE)
        reports.flush()
        assertEquals(setOf("settings_unavailable"), store.p)
        online = true
        reports.flush()
        assertEquals(listOf("settings_unavailable"), delivered)
        assertEquals(emptySet<String>(), store.p)
    }

    @Test fun aServerThatDoesNotAcceptItDoesNotCountAsSent() = runBlocking {
        val refusing = UsageAccessReports(store) { false }
        refusing.record(AccessRefusal.NOT_NOW)
        refusing.flush()
        assertEquals(setOf("not_now"), store.p)
        assertEquals(emptySet<String>(), store.s)
    }
}
