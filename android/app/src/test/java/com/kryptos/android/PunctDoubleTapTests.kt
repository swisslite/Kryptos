package com.kryptos.android

import com.kryptos.android.keyboard.PunctDoubleTap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctDoubleTapTests {

    @Test
    fun replacesPeriodOnSecondTapInsideTheWindow() {
        assertTrue(PunctDoubleTap.replacesPeriod(true, false, 120, ".", "."))
        assertTrue(PunctDoubleTap.replacesPeriod(true, false, PunctDoubleTap.WINDOW_MS - 1, ".", "."))
    }

    @Test
    fun keepsPeriodWhenTheSecondTapIsTooLate() {
        assertFalse(PunctDoubleTap.replacesPeriod(true, false, PunctDoubleTap.WINDOW_MS, ".", "."))
        assertFalse(PunctDoubleTap.replacesPeriod(true, false, 5_000, ".", "."))
    }

    @Test
    fun keepsPeriodWhenTheSettingIsOff() {
        assertFalse(PunctDoubleTap.replacesPeriod(false, false, 120, ".", "."))
    }

    @Test
    fun keepsPeriodInPasswordFields() {
        assertFalse(PunctDoubleTap.replacesPeriod(true, true, 120, ".", "."))
    }

    @Test
    fun neverDeletesACharacterTheKeyDidNotType() {
        assertFalse(PunctDoubleTap.replacesPeriod(true, false, 120, "", "."))
        assertFalse(PunctDoubleTap.replacesPeriod(true, false, 120, "a", "."))
        assertFalse(PunctDoubleTap.replacesPeriod(true, false, 120, "?", "."))
        assertFalse(PunctDoubleTap.replacesPeriod(true, false, 120, ",", "."))
        assertFalse(PunctDoubleTap.replacesPeriod(true, false, 120, ".", "。"))
    }

    @Test
    fun worksForTheChinesePeriod() {
        assertTrue(PunctDoubleTap.replacesPeriod(true, false, 120, "。", "。"))
    }

    @Test
    fun ignoresAClockThatMovedBackwards() {
        assertFalse(PunctDoubleTap.replacesPeriod(true, false, -50, ".", "."))
    }
}
