package com.kryptos.android

import com.kryptos.android.keyboard.SuggestionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class SuggestionEngineTests {

    companion object {
        @BeforeClass @JvmStatic
        fun load() {
            val assets = File("src/main/assets/dict")
            fun read(name: String) = File(assets, name).readLines().filter { it.isNotBlank() }
            SuggestionEngine.loadForTest(
                ruWords = read("ru.txt"),
                enWords = read("en.txt"),
                ruPairs = read("bigrams-ru.txt"),
                enPairs = read("bigrams-en.txt"),
                ruForms = File(assets, "vocab-ru.txt").readText(),
                enForms = File(assets, "vocab-en.txt").readText(),
            )
        }
    }

    @Test
    fun completesByFrequency() {
        val en = SuggestionEngine.suggest("th", previous = null, russianPlane = false)
        assertEquals("the", en.first())
        val ru = SuggestionEngine.suggest("прив", previous = null, russianPlane = true)
        assertTrue("got $ru", ru.contains("привет"))
    }

    @Test
    fun matchesTypedCase() {
        val ru = SuggestionEngine.suggest("Прив", previous = null, russianPlane = true)
        assertTrue("got $ru", ru.contains("Привет"))
    }

    @Test
    fun contextBeatsRawFrequency() {
        val ru = SuggestionEngine.suggest("д", previous = "как", russianPlane = true)
        assertEquals("got $ru", "дела", ru.first())
        val en = SuggestionEngine.suggest("y", previous = "thank", russianPlane = false)
        assertEquals("got $en", "you", en.first())
    }

    @Test
    fun predictsNextWordFromEmptyPrefix() {
        val ru = SuggestionEngine.suggest("", previous = "как", russianPlane = true)
        assertTrue("got $ru", ru.contains("дела"))
        val en = SuggestionEngine.suggest("", previous = "how", russianPlane = false)
        assertTrue("got $en", en.contains("are"))
    }

    @Test
    fun offersTypoRepairs() {
        val ru = SuggestionEngine.suggest("еак", previous = null, russianPlane = true)
        assertEquals("got $ru", "как", ru.first())
    }

    @Test
    fun fixesClassicTypos() {
        assertEquals("the", SuggestionEngine.autocorrect("teh", previous = null, russianPlane = false))
        assertEquals("The", SuggestionEngine.autocorrect("Teh", previous = null, russianPlane = false))
        assertEquals("привет", SuggestionEngine.autocorrect("превет", previous = null, russianPlane = true))
    }

    @Test
    fun fixesRareRealWordSlips() {
        assertEquals("дела", SuggestionEngine.autocorrect("бела", previous = "твои", russianPlane = true))
        assertNull(SuggestionEngine.autocorrect("бела", previous = null, russianPlane = true))
        assertNull(SuggestionEngine.autocorrect("дела", previous = null, russianPlane = true))
        assertNull(SuggestionEngine.autocorrect("твои", previous = null, russianPlane = true))
    }

    @Test
    fun protectsWordFormsFromTheBigVocabulary() {
        assertNull(SuggestionEngine.autocorrect("скачал", previous = null, russianPlane = true))
        assertNull(SuggestionEngine.autocorrect("скачал", previous = "я", russianPlane = true))
        assertNull(SuggestionEngine.autocorrect("слове", previous = "в", russianPlane = true))
        assertNull(SuggestionEngine.autocorrect("исправленное", previous = null, russianPlane = true))
    }

    @Test
    fun fixesTyposIntoBigVocabularyForms() {
        assertEquals("исправленное",
            SuggestionEngine.autocorrect("исправленое", previous = null, russianPlane = true))
    }

    @Test
    fun undoIsSessionOnlyAndTapIsPermanent() {
        assertEquals("world", SuggestionEngine.autocorrect("wrold", previous = null, russianPlane = false))
        SuggestionEngine.noteUndoneCorrection("wrold")
        assertNull(SuggestionEngine.autocorrect("wrold", previous = null, russianPlane = false))
    }

    @Test
    fun fixesDoubleTypos() {
        assertEquals("понимаю", SuggestionEngine.autocorrect("понтиаю", previous = null, russianPlane = true))
    }

    @Test
    fun completesAlmostFinishedWords() {
        assertEquals("привет", SuggestionEngine.autocorrect("приве", previous = null, russianPlane = true))
        assertEquals("исправлять", SuggestionEngine.autocorrect("исправля", previous = null, russianPlane = true))
        assertNull(SuggestionEngine.autocorrect("прив", previous = null, russianPlane = true))
    }

    @Test
    fun neverTouchesRealWords() {
        assertNull(SuggestionEngine.autocorrect("привет", previous = null, russianPlane = true))
        assertNull(SuggestionEngine.autocorrect("hello", previous = null, russianPlane = false))
        assertNull(SuggestionEngine.autocorrect("dont", previous = null, russianPlane = false))
        assertNull(SuggestionEngine.autocorrect("привetы", previous = null, russianPlane = true))
        assertNull(SuggestionEngine.autocorrect("im", previous = null, russianPlane = false))
    }

    @Test
    fun undoTeachesTheEngine() {
        val word = "квакозябр"
        SuggestionEngine.noteRejectedCorrection(word)
        assertNull(SuggestionEngine.autocorrect(word, previous = null, russianPlane = true))
    }

    @Test
    fun learnedWordsSurfaceInSuggestions() {
        SuggestionEngine.learn("зашифруй", "давай")
        SuggestionEngine.learn("зашифруй", "давай")
        SuggestionEngine.learn("зашифруй", "давай")
        val out = SuggestionEngine.suggest("зашифр", previous = null, russianPlane = true)
        assertTrue("got $out", out.contains("зашифруй"))
        val next = SuggestionEngine.suggest("", previous = "давай", russianPlane = true)
        assertTrue("got $next", next.contains("зашифруй"))
    }

    @Test
    fun splitsGluedWords() {
        assertEquals("как дела", SuggestionEngine.autocorrect("какдела", previous = null, russianPlane = true))
        assertEquals("Как дела", SuggestionEngine.autocorrect("Какдела", previous = null, russianPlane = true))
        assertEquals("a lot", SuggestionEngine.autocorrect("alot", previous = null, russianPlane = false))
    }

    @Test
    fun capitalizesLoneI() {
        assertEquals("I", SuggestionEngine.autocorrect("i", previous = null, russianPlane = false))
    }

    @Test
    fun suggestsThroughMultipleTypos() {
        val ru = SuggestionEngine.suggest("превт", previous = null, russianPlane = true)
        assertTrue("got $ru", ru.contains("привет"))
        val mid = SuggestionEngine.suggest("понтиа", previous = null, russianPlane = true)
        assertEquals("got $mid", "понимаю", mid.first())
        val en = SuggestionEngine.suggest("buisnes", previous = null, russianPlane = false)
        assertTrue("got $en", en.contains("business"))
    }

    @Test
    fun completesIntoBigVocabularyForms() {
        val ru = SuggestionEngine.suggest("исправленн", previous = null, russianPlane = true)
        assertTrue("got $ru", ru.isNotEmpty() && ru.all { it.startsWith("исправленн") })
    }

    @Test
    fun fixesMultiEditTypos() {
        assertEquals("здравствуйте", SuggestionEngine.autocorrect("здраствуйте", previous = null, russianPlane = true))
        assertEquals("воскресенье", SuggestionEngine.autocorrect("васкресенье", previous = null, russianPlane = true))
        assertEquals("понимаешь", SuggestionEngine.autocorrect("поеимаешь", previous = null, russianPlane = true))
        assertEquals("business", SuggestionEngine.autocorrect("bussines", previous = null, russianPlane = false))
        assertEquals("example", SuggestionEngine.autocorrect("exapmle", previous = null, russianPlane = false))
        assertEquals("definitely", SuggestionEngine.autocorrect("definately", previous = null, russianPlane = false))
        assertEquals("government", SuggestionEngine.autocorrect("goverment", previous = null, russianPlane = false))
        assertEquals("environment", SuggestionEngine.autocorrect("enviroment", previous = null, russianPlane = false))
    }

    @Test
    fun singleEditIntentBeatsFrequentTwoEditWord() {
        assertEquals("weird", SuggestionEngine.autocorrect("wierd", previous = null, russianPlane = false))
    }

    @Test
    fun neverMixesScriptsInCorrections() {
        assertNull(SuggestionEngine.autocorrect("чiтать", previous = null, russianPlane = true))
    }
}
