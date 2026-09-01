package com.kryptos.android

import com.kryptos.android.keyboard.VoiceInput
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputTests {

    private val original: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun fallsBackToAFixedTagWhenTheSystemLocaleIsAnotherLanguage() {
        Locale.setDefault(Locale.forLanguageTag("fr-FR"))
        assertEquals("en-US", VoiceInput.languageTag("en"))
        assertEquals("ru-RU", VoiceInput.languageTag("ru"))
        assertEquals("de-DE", VoiceInput.languageTag("de"))
        assertEquals("zh-CN", VoiceInput.languageTag("zh"))
    }

    @Test
    fun keepsTheSystemRegionWhenTheLanguageMatches() {
        Locale.setDefault(Locale.forLanguageTag("en-GB"))
        assertEquals("en-GB", VoiceInput.languageTag("en"))
        assertEquals("ru-RU", VoiceInput.languageTag("ru"))
    }

    @Test
    fun ignoresASystemLocaleWithoutARegion() {
        Locale.setDefault(Locale.forLanguageTag("de"))
        assertEquals("de-DE", VoiceInput.languageTag("de"))
    }

    @Test
    fun separatesDictatedTextFromWhatIsAlreadyInTheField() {
        assertTrue(VoiceInput.needsLeadingSpace("Привет", chinese = false))
        assertTrue(VoiceInput.needsLeadingSpace("done.", chinese = false))
        assertFalse(VoiceInput.needsLeadingSpace("Привет ", chinese = false))
        assertFalse(VoiceInput.needsLeadingSpace("line\n", chinese = false))
        assertFalse(VoiceInput.needsLeadingSpace("", chinese = false))
        assertFalse(VoiceInput.needsLeadingSpace("(", chinese = false))
        assertFalse(VoiceInput.needsLeadingSpace("«", chinese = false))
    }

    @Test
    fun neverSeparatesChineseText() {
        assertFalse(VoiceInput.needsLeadingSpace("你好", chinese = true))
        assertFalse(VoiceInput.needsLeadingSpace("你好。", chinese = true))
    }
}
