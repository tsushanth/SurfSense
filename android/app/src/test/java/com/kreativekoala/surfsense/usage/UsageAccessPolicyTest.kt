package com.kreativekoala.surfsense.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAccessPolicyTest {
    private class FakeStore(var shown: Int = 0, var last: Boolean? = null) : UsageAccessPolicy.Store {
        override fun dialogShown() = shown
        override fun setDialogShown(count: Int) { shown = count }
        override fun lastGranted() = last
        override fun setLastGranted(granted: Boolean) { last = granted }
    }

    @Test fun dialogIsShownTwiceInTotalThenNever() {
        val policy = UsageAccessPolicy(FakeStore())
        assertTrue(policy.shouldShowDialog(granted = false))
        policy.recordDialogShown()
        assertTrue(policy.shouldShowDialog(granted = false))
        policy.recordDialogShown()
        assertFalse(policy.shouldShowDialog(granted = false))
    }

    @Test fun dialogCountSurvivesANewPolicyOnTheSameStore() {
        val store = FakeStore()
        UsageAccessPolicy(store).apply { recordDialogShown(); recordDialogShown() }
        assertFalse(UsageAccessPolicy(store).shouldShowDialog(granted = false))
        assertEquals(UsageAccessPolicy.MAX_DIALOG_SHOWINGS, store.shown)
    }

    @Test fun dialogIsNeverShownWhenAccessIsGranted() {
        assertFalse(UsageAccessPolicy(FakeStore()).shouldShowDialog(granted = true))
    }

    @Test fun missingThenGrantedIsAFlip() {
        val policy = UsageAccessPolicy(FakeStore())
        assertFalse(policy.onChecked(false))
        assertTrue(policy.onChecked(true))
        assertFalse("already granted, no second flip", policy.onChecked(true))
    }

    @Test fun firstCheckThatIsGrantedIsNotAFlip() {
        assertFalse(UsageAccessPolicy(FakeStore()).onChecked(true))
    }

    @Test fun revokedThenGrantedFlipsAgain() {
        val policy = UsageAccessPolicy(FakeStore(last = true))
        assertFalse(policy.onChecked(false))
        assertTrue(policy.onChecked(true))
    }

    @Test fun flipIsRememberedAcrossAProcessRestart() {
        val store = FakeStore()
        UsageAccessPolicy(store).onChecked(false)
        assertTrue(UsageAccessPolicy(store).onChecked(true))
    }

    @Test fun stillMissingIsNotAFlip() {
        val policy = UsageAccessPolicy(FakeStore())
        assertFalse(policy.onChecked(false))
        assertFalse(policy.onChecked(false))
    }
}
