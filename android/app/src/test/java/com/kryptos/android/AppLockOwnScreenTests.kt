package com.kryptos.android

import com.kryptos.android.security.AppLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockOwnScreenTests {

    @Test
    fun goingToTheBackgroundWithoutOurOwnScreenIsAnOrdinaryDeparture() {
        assertFalse(AppLock.leftForOwnScreen(launchedAt = 0L, leftAt = 10_000L))
    }

    @Test
    fun aScreenWeJustStartedCountsAsOurOwn() {
        assertTrue(AppLock.leftForOwnScreen(launchedAt = 10_000L, leftAt = 10_000L))
        assertTrue(AppLock.leftForOwnScreen(launchedAt = 10_000L, leftAt = 10_500L))
        assertTrue(AppLock.leftForOwnScreen(launchedAt = 10_000L, leftAt = 19_999L))
    }

    @Test
    fun aStaleLaunchMarkDoesNotCountAsOurOwnScreen() {
        assertFalse(AppLock.leftForOwnScreen(launchedAt = 10_000L, leftAt = 20_000L))
        assertFalse(AppLock.leftForOwnScreen(launchedAt = 10_000L, leftAt = 9_000L))
    }

    @Test
    fun returningFromOurOwnScreenSkipsTheLock() {
        assertTrue(AppLock.returningFromOwnScreen(leftForOwnScreen = true, leftAt = 1_000L, now = 1_000L))
        assertTrue(AppLock.returningFromOwnScreen(leftForOwnScreen = true, leftAt = 1_000L, now = 60_000L))
    }

    @Test
    fun anOrdinaryDepartureAlwaysLocks() {
        assertFalse(AppLock.returningFromOwnScreen(leftForOwnScreen = false, leftAt = 1_000L, now = 1_100L))
    }

    @Test
    fun beingAwayTooLongLocksEvenAfterOurOwnScreen() {
        val leftAt = 1_000L
        assertTrue(AppLock.returningFromOwnScreen(true, leftAt, leftAt + 5 * 60_000L - 1))
        assertFalse(AppLock.returningFromOwnScreen(true, leftAt, leftAt + 5 * 60_000L))
        assertFalse(AppLock.returningFromOwnScreen(true, leftAt, leftAt + 60 * 60_000L))
    }

    @Test
    fun clockGoingBackwardsLocks() {
        assertFalse(AppLock.returningFromOwnScreen(true, leftAt = 10_000L, now = 5_000L))
    }
}
