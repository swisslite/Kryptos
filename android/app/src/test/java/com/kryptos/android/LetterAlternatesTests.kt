package com.kryptos.android

import com.kryptos.android.keyboard.LetterAlternates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterAlternatesTests {

    @Test
    fun offersTheLettersThatHaveNoKeyOfTheirOwn() {
        assertEquals(listOf("ь", "ъ"), LetterAlternates.forLabel("ь"))
        assertEquals(listOf("е", "ё"), LetterAlternates.forLabel("е"))
    }

    @Test
    fun followsTheCaseOfThePressedKey() {
        assertEquals(listOf("Ь", "Ъ"), LetterAlternates.forLabel("Ь"))
        assertEquals(listOf("Е", "Ё"), LetterAlternates.forLabel("Е"))
    }

    @Test
    fun offersNothingForAKeyThatIsNotInTheTable() {
        listOf("а", "z", "1", ".", "", "ъ", "ё").forEach {
            assertTrue(it, LetterAlternates.forLabel(it).isEmpty())
        }
    }

    @Test
    fun everyEntryStartsWithThePressedLetterSoAHoldAloneChangesNothing() {
        LetterAlternates.table.forEach { (key, options) ->
            assertEquals(key, options.first())
            assertTrue(key, options.size > 1)
            assertEquals(key, options.size, options.distinct().size)
        }
    }
}
