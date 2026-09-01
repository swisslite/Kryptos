package com.kryptos.android

import com.kryptos.android.core.CipherException
import com.kryptos.android.core.ImageBridge
import com.kryptos.android.core.KeyText
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.Padding
import com.kryptos.android.core.StegoLanguage
import com.kryptos.android.core.WireFormat
import com.kryptos.android.security.AppLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardeningTests {

    @Test
    fun paddingRejectsNegativeLength() {
        val error = runCatching { Padding.target(-1) }.exceptionOrNull()
        assertTrue(error is CipherException)
        assertEquals(CipherException.Kind.INVALID_INPUT, (error as CipherException).kind)
    }

    @Test
    fun paddingRejectsLengthsThatWouldOverflow() {
        val error = runCatching { Padding.target(Int.MAX_VALUE - 16) }.exceptionOrNull()
        assertTrue(error is CipherException)
    }

    @Test
    fun paddingKeepsItsExistingShape() {
        assertEquals(64, Padding.target(0))
        assertEquals(64, Padding.target(64))
        assertEquals(128, Padding.target(65))
        assertEquals(1 shl 20, Padding.target(1 shl 20))
        assertEquals(2 shl 20, Padding.target((1 shl 20) + 1))
    }

    @Test
    fun pixelBudgetShrinksWithTheHeap() {
        val small = ImageBridge.pixelBudgetFor(64L * 1024 * 1024)
        val large = ImageBridge.pixelBudgetFor(512L * 1024 * 1024)
        assertEquals(ImageBridge.MIN_PIXEL_BUDGET, small)
        assertTrue(large > small)
        assertTrue(large <= ImageBridge.MAX_PIXELS)
    }

    @Test
    fun pixelBudgetNeverExceedsTheHardCeiling() {
        assertEquals(ImageBridge.MAX_PIXELS, ImageBridge.pixelBudgetFor(Long.MAX_VALUE / 4))
    }

    @Test
    fun revealBudgetRejectsAFiftyMegapixelPhotoOnAModestHeap() {
        val budget = ImageBridge.pixelBudgetFor(192L * 1024 * 1024)
        assertFalse(ImageBridge.withinBudget(8160, 6120, 1, budget))
        assertTrue(ImageBridge.withinBudget(3264, 2448, 1, budget))
    }

    @Test
    fun throttleGrowsAfterFourFailuresAndCaps() {
        assertEquals(0L, AppLock.throttleFor(0))
        assertEquals(0L, AppLock.throttleFor(4))
        assertEquals(1_000L, AppLock.throttleFor(5))
        assertEquals(2_000L, AppLock.throttleFor(6))
        assertEquals(30_000L, AppLock.throttleFor(50))
    }

    @Test
    fun throttleIsChargedInFullUntilItHasBeenWaitedOut() {
        assertEquals(0L, AppLock.remainingThrottle(0L, 0L, 5_000L))
        assertEquals(4_000L, AppLock.remainingThrottle(4_000L, 0L, 9_000L))
        assertEquals(4_000L, AppLock.remainingThrottle(4_000L, 1_000L, 1_000L))
        assertEquals(1_000L, AppLock.remainingThrottle(4_000L, 1_000L, 4_000L))
        assertEquals(0L, AppLock.remainingThrottle(4_000L, 1_000L, 5_000L))
        assertEquals(0L, AppLock.remainingThrottle(4_000L, 1_000L, 900_000L))
        assertEquals(4_000L, AppLock.remainingThrottle(4_000L, 9_000L, 1_000L))
    }

    @Test
    fun keyTextStopsScanningAfterTheBlobCap() {
        val blob = ByteArray(64) { (it * 7 and 0xFF).toByte() }
        val encoded = java.util.Base64.getEncoder().encodeToString(blob)
        val padding = "A".repeat(KeyText.MAX_BLOB_CHARS)
        assertTrue(KeyText.blobs(KeyText.PREFIX + encoded).any { it.contentEquals(blob) })
        assertTrue(KeyText.blobs(KeyText.PREFIX + padding + encoded).none { it.contentEquals(blob) })
    }

    @Test
    fun aLettersCoverCanLookExactlyLikeAToken() {
        val payload = ByteArray(80) { (it * 31 and 0xFF).toByte() }
        val covers = (0 until 40).mapNotNull {
            runCatching { LetterStego.encode(payload, StegoLanguage.ENGLISH, it) }.getOrNull()
        }
        assertTrue(covers.isNotEmpty())
        assertTrue(covers.any { WireFormat.isToken(it) })
    }
}
