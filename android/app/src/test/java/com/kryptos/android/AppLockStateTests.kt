package com.kryptos.android

import com.kryptos.android.security.AppLock.LockState
import com.kryptos.android.security.AppLock.resolveLockState
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLockStateTests {

    @Test
    fun withoutASystemCredentialAnAppCodeBecomesTheMethod() {
        assertEquals(
            LockState(enabled = false, codeOnly = true),
            resolveLockState(LockState(enabled = false, codeOnly = false), canSystem = false, appCodeSet = true),
        )
    }

    @Test
    fun enablingWithoutASystemCredentialArmsTheCodeOnlyLock() {
        assertEquals(
            LockState(enabled = true, codeOnly = true),
            resolveLockState(LockState(enabled = true, codeOnly = false), canSystem = false, appCodeSet = true),
        )
    }

    @Test
    fun enablingWithNoUsableMethodCannotArmTheLock() {
        assertEquals(
            LockState(enabled = false, codeOnly = false),
            resolveLockState(LockState(enabled = true, codeOnly = false), canSystem = false, appCodeSet = false),
        )
        assertEquals(
            LockState(enabled = false, codeOnly = false),
            resolveLockState(LockState(enabled = true, codeOnly = true), canSystem = false, appCodeSet = false),
        )
    }

    @Test
    fun removingTheAppCodeTurnsOffACodeOnlyLockWhenNothingElseCanUnlock() {
        assertEquals(
            LockState(enabled = false, codeOnly = false),
            resolveLockState(LockState(enabled = true, codeOnly = true), canSystem = false, appCodeSet = false),
        )
    }

    @Test
    fun removingTheAppCodeFallsBackToTheSystemCredential() {
        assertEquals(
            LockState(enabled = true, codeOnly = false),
            resolveLockState(LockState(enabled = true, codeOnly = true), canSystem = true, appCodeSet = false),
        )
    }

    @Test
    fun anExplicitCodeOnlyChoiceSurvivesWhenBothMethodsExist() {
        assertEquals(
            LockState(enabled = true, codeOnly = true),
            resolveLockState(LockState(enabled = true, codeOnly = true), canSystem = true, appCodeSet = true),
        )
        assertEquals(
            LockState(enabled = true, codeOnly = false),
            resolveLockState(LockState(enabled = true, codeOnly = false), canSystem = true, appCodeSet = true),
        )
    }

    @Test
    fun disablingTheLockKeepsTheChosenMethod() {
        assertEquals(
            LockState(enabled = false, codeOnly = true),
            resolveLockState(LockState(enabled = false, codeOnly = true), canSystem = true, appCodeSet = true),
        )
    }

    @Test
    fun anArmedLockAlwaysHasAUsableMethod() {
        for (enabled in listOf(false, true)) {
            for (codeOnly in listOf(false, true)) {
                for (canSystem in listOf(false, true)) {
                    for (appCodeSet in listOf(false, true)) {
                        val out = resolveLockState(LockState(enabled, codeOnly), canSystem, appCodeSet)
                        val usable = if (out.codeOnly) appCodeSet else canSystem
                        assertEquals("$enabled/$codeOnly/$canSystem/$appCodeSet", true, usable || !out.enabled)
                    }
                }
            }
        }
    }

    @Test
    fun resolutionIsIdempotent() {
        for (enabled in listOf(false, true)) {
            for (codeOnly in listOf(false, true)) {
                for (canSystem in listOf(false, true)) {
                    for (appCodeSet in listOf(false, true)) {
                        val once = resolveLockState(LockState(enabled, codeOnly), canSystem, appCodeSet)
                        val twice = resolveLockState(once, canSystem, appCodeSet)
                        assertEquals("$enabled/$codeOnly/$canSystem/$appCodeSet", once, twice)
                    }
                }
            }
        }
    }

    @Test
    fun theLockIsNeverArmedByResolutionAlone() {
        for (codeOnly in listOf(false, true)) {
            for (canSystem in listOf(false, true)) {
                for (appCodeSet in listOf(false, true)) {
                    val out = resolveLockState(LockState(enabled = false, codeOnly = codeOnly), canSystem, appCodeSet)
                    assertEquals("$codeOnly/$canSystem/$appCodeSet", false, out.enabled)
                }
            }
        }
    }
}
