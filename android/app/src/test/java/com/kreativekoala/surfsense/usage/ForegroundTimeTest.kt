package com.kreativekoala.surfsense.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTimeTest {
    private val start = 1_000_000L
    private val end = start + 10 * 60_000L
    private fun ev(pkg: String, type: Int, atMin: Double) = UsageEvent(pkg, type, start + (atMin * 60_000).toLong())

    @Test fun pairsResumeAndPause() {
        val r = ForegroundTime.secondsByPackage(listOf(ev("a", 1, 1.0), ev("a", 2, 3.0)), start, end)
        assertEquals(mapOf("a" to 120), r)
    }

    @Test fun sumsSeveralSessionsAndSeparatesPackages() {
        val r = ForegroundTime.secondsByPackage(
            listOf(ev("a", 1, 0.0), ev("a", 2, 1.0), ev("b", 1, 1.0), ev("b", 2, 2.0), ev("a", 1, 5.0), ev("a", 2, 6.0)),
            start, end
        )
        assertEquals(mapOf("a" to 120, "b" to 60), r)
    }

    @Test fun anAppStillOpenAtTheEndIsClampedToNow() {
        assertEquals(mapOf("a" to 120), ForegroundTime.secondsByPackage(listOf(ev("a", 1, 8.0)), start, end))
    }

    @Test fun aPauseWithoutAResumeInTheWindowIsIgnored() {
        assertEquals(emptyMap<String, Int>(), ForegroundTime.secondsByPackage(listOf(ev("a", 2, 3.0)), start, end))
    }

    @Test fun screenOffClosesEveryOpenApp() {
        val r = ForegroundTime.secondsByPackage(listOf(ev("a", 1, 1.0), ev("b", 1, 2.0), ev("x", 16, 3.0)), start, end)
        assertEquals(mapOf("a" to 120, "b" to 60), r)
    }

    @Test fun deviceShutdownClosesEveryOpenApp() {
        assertEquals(mapOf("a" to 60), ForegroundTime.secondsByPackage(listOf(ev("a", 1, 1.0), ev("x", 26, 2.0)), start, end))
    }

    @Test fun duplicateResumeDoesNotRestartTheClockAndStoppedAfterPausedAddsNothing() {
        val r = ForegroundTime.secondsByPackage(
            listOf(ev("a", 1, 1.0), ev("a", 1, 2.0), ev("a", 2, 3.0), ev("a", 23, 3.1)), start, end
        )
        assertEquals(mapOf("a" to 120), r)
    }

    @Test fun unsortedInputIsHandledAndSubSecondSessionsDropped() {
        val r = ForegroundTime.secondsByPackage(
            listOf(ev("a", 2, 3.0), ev("a", 1, 1.0), UsageEvent("b", 1, start), UsageEvent("b", 2, start + 400)), start, end
        )
        assertEquals(mapOf("a" to 120), r)
    }

    @Test fun eventsOutsideTheWindowAreClamped() {
        val r = ForegroundTime.secondsByPackage(listOf(UsageEvent("a", 1, end - 30_000), UsageEvent("a", 2, end + 60_000)), start, end)
        assertEquals(mapOf("a" to 30), r)
    }

    private fun act(cls: String, type: Int, atMs: Long) = UsageEvent("p", type, start + atMs, cls)

    @Test fun activityToActivityPlatformOrderKeepsTheWholeSession() {
        val r = ForegroundTime.secondsByPackage(
            listOf(
                act("A1", 1, 0), act("A1", 2, 60_000), act("A2", 1, 60_100),
                act("A1", 23, 60_300), act("A2", 2, 180_000)
            ), start, end
        )
        assertEquals(mapOf("p" to 179), r)
    }

    @Test fun reversedActivityOrderCountsContinuously() {
        val r = ForegroundTime.secondsByPackage(
            listOf(act("A1", 1, 0), act("A2", 1, 60_000), act("A1", 2, 60_100), act("A2", 2, 180_000)), start, end
        )
        assertEquals(mapOf("p" to 180), r)
    }

    @Test fun twoActivitiesOpenAtOnceAreNotDoubleCounted() {
        val r = ForegroundTime.secondsByPackage(
            listOf(act("A1", 1, 0), act("A2", 1, 30_000), act("A1", 2, 60_000), act("A2", 2, 120_000)), start, end
        )
        assertEquals(mapOf("p" to 120), r)
    }

    @Test fun aSessionStartedBeforeTheWindowCountsOnlyTheInWindowPart() {
        val ws = 10 * 60_000L
        val r = ForegroundTime.secondsByPackage(
            listOf(UsageEvent("a", 1, ws - 2 * 60_000), UsageEvent("a", 2, ws + 3 * 60_000)), ws, ws + 60 * 60_000
        )
        assertEquals(mapOf("a" to 180), r)
    }
}
