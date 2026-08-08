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
            SuggestionEngine.loadLanguageForTest(
                "de",
                read("de.txt"),
                read("bigrams-de.txt"),
                File(assets, "vocab-de.txt").readText(),
            )
        }
    }

    @Test
    fun completesByFrequency() {
        val en = SuggestionEngine.suggest("th", previous = null, language = "en")
        assertEquals("the", en.first())
        val ru = SuggestionEngine.suggest("прив", previous = null, language = "ru")
        assertTrue("got $ru", ru.contains("привет"))
    }

    @Test
    fun matchesTypedCase() {
        val ru = SuggestionEngine.suggest("Прив", previous = null, language = "ru")
        assertTrue("got $ru", ru.contains("Привет"))
    }

    @Test
    fun contextBeatsRawFrequency() {
        val ru = SuggestionEngine.suggest("д", previous = "как", language = "ru")
        assertEquals("got $ru", "дела", ru.first())
        val en = SuggestionEngine.suggest("y", previous = "thank", language = "en")
        assertEquals("got $en", "you", en.first())
    }

    @Test
    fun predictsNextWordFromEmptyPrefix() {
        val ru = SuggestionEngine.suggest("", previous = "как", language = "ru")
        assertTrue("got $ru", ru.contains("дела"))
        val en = SuggestionEngine.suggest("", previous = "how", language = "en")
        assertTrue("got $en", en.contains("are"))
    }

    @Test
    fun offersTypoRepairs() {
        val ru = SuggestionEngine.suggest("еак", previous = null, language = "ru")
        assertEquals("got $ru", "как", ru.first())
    }

    @Test
    fun fixesClassicTypos() {
        assertEquals("the", SuggestionEngine.autocorrect("teh", previous = null, language = "en"))
        assertEquals("The", SuggestionEngine.autocorrect("Teh", previous = null, language = "en"))
        assertEquals("привет", SuggestionEngine.autocorrect("превет", previous = null, language = "ru"))
    }

    @Test
    fun fixesRareRealWordSlips() {
        assertEquals("дела", SuggestionEngine.autocorrect("бела", previous = "твои", language = "ru"))
        assertNull(SuggestionEngine.autocorrect("бела", previous = null, language = "ru"))
        assertNull(SuggestionEngine.autocorrect("дела", previous = null, language = "ru"))
        assertNull(SuggestionEngine.autocorrect("твои", previous = null, language = "ru"))
    }

    @Test
    fun protectsWordFormsFromTheBigVocabulary() {
        assertNull(SuggestionEngine.autocorrect("скачал", previous = null, language = "ru"))
        assertNull(SuggestionEngine.autocorrect("скачал", previous = "я", language = "ru"))
        assertNull(SuggestionEngine.autocorrect("слове", previous = "в", language = "ru"))
        assertNull(SuggestionEngine.autocorrect("исправленное", previous = null, language = "ru"))
    }

    @Test
    fun fixesTyposIntoBigVocabularyForms() {
        assertEquals("исправленное",
            SuggestionEngine.autocorrect("исправленое", previous = null, language = "ru"))
    }

    @Test
    fun undoIsSessionOnlyAndTapIsPermanent() {
        assertEquals("world", SuggestionEngine.autocorrect("wrold", previous = null, language = "en"))
        SuggestionEngine.noteUndoneCorrection("wrold")
        assertNull(SuggestionEngine.autocorrect("wrold", previous = null, language = "en"))
    }

    @Test
    fun fixesDoubleTypos() {
        assertEquals("понимаю", SuggestionEngine.autocorrect("понтиаю", previous = null, language = "ru"))
    }

    @Test
    fun completesAlmostFinishedWords() {
        assertEquals("привет", SuggestionEngine.autocorrect("приве", previous = null, language = "ru"))
        assertEquals("исправлять", SuggestionEngine.autocorrect("исправля", previous = null, language = "ru"))
        assertNull(SuggestionEngine.autocorrect("прив", previous = null, language = "ru"))
    }

    @Test
    fun neverTouchesRealWords() {
        assertNull(SuggestionEngine.autocorrect("привет", previous = null, language = "ru"))
        assertNull(SuggestionEngine.autocorrect("hello", previous = null, language = "en"))
        assertNull(SuggestionEngine.autocorrect("dont", previous = null, language = "en"))
        assertNull(SuggestionEngine.autocorrect("привetы", previous = null, language = "ru"))
        assertNull(SuggestionEngine.autocorrect("im", previous = null, language = "en"))
    }

    @Test
    fun undoTeachesTheEngine() {
        val word = "квакозябр"
        SuggestionEngine.noteRejectedCorrection(word)
        assertNull(SuggestionEngine.autocorrect(word, previous = null, language = "ru"))
    }

    @Test
    fun learnedWordsSurfaceInSuggestions() {
        SuggestionEngine.learn("зашифруй", "давай")
        SuggestionEngine.learn("зашифруй", "давай")
        SuggestionEngine.learn("зашифруй", "давай")
        val out = SuggestionEngine.suggest("зашифр", previous = null, language = "ru")
        assertTrue("got $out", out.contains("зашифруй"))
        val next = SuggestionEngine.suggest("", previous = "давай", language = "ru")
        assertTrue("got $next", next.contains("зашифруй"))
    }

    @Test
    fun splitsGluedWords() {
        assertEquals("как дела", SuggestionEngine.autocorrect("какдела", previous = null, language = "ru"))
        assertEquals("Как дела", SuggestionEngine.autocorrect("Какдела", previous = null, language = "ru"))
        assertEquals("a lot", SuggestionEngine.autocorrect("alot", previous = null, language = "en"))
    }

    @Test
    fun capitalizesLoneI() {
        assertEquals("I", SuggestionEngine.autocorrect("i", previous = null, language = "en"))
    }

    @Test
    fun suggestsThroughMultipleTypos() {
        val ru = SuggestionEngine.suggest("превт", previous = null, language = "ru")
        assertTrue("got $ru", ru.contains("привет"))
        val mid = SuggestionEngine.suggest("понтиа", previous = null, language = "ru")
        assertEquals("got $mid", "понимаю", mid.first())
        val en = SuggestionEngine.suggest("buisnes", previous = null, language = "en")
        assertTrue("got $en", en.contains("business"))
    }

    @Test
    fun completesIntoBigVocabularyForms() {
        val ru = SuggestionEngine.suggest("исправленн", previous = null, language = "ru")
        assertTrue("got $ru", ru.isNotEmpty() && ru.all { it.startsWith("исправленн") })
    }

    @Test
    fun fixesMultiEditTypos() {
        assertEquals("здравствуйте", SuggestionEngine.autocorrect("здраствуйте", previous = null, language = "ru"))
        assertEquals("воскресенье", SuggestionEngine.autocorrect("васкресенье", previous = null, language = "ru"))
        assertEquals("понимаешь", SuggestionEngine.autocorrect("поеимаешь", previous = null, language = "ru"))
        assertEquals("business", SuggestionEngine.autocorrect("bussines", previous = null, language = "en"))
        assertEquals("example", SuggestionEngine.autocorrect("exapmle", previous = null, language = "en"))
        assertEquals("definitely", SuggestionEngine.autocorrect("definately", previous = null, language = "en"))
        assertEquals("government", SuggestionEngine.autocorrect("goverment", previous = null, language = "en"))
        assertEquals("environment", SuggestionEngine.autocorrect("enviroment", previous = null, language = "en"))
    }

    @Test
    fun singleEditIntentBeatsFrequentTwoEditWord() {
        assertEquals("weird", SuggestionEngine.autocorrect("wierd", previous = null, language = "en"))
    }

    @Test
    fun neverMixesScriptsInCorrections() {
        assertNull(SuggestionEngine.autocorrect("чiтать", previous = null, language = "ru"))
    }

    @Test
    fun germanCompletesByFrequency() {
        val out = SuggestionEngine.suggest("wahrsch", previous = null, language = "de")
        assertTrue("got $out", out.contains("wahrscheinlich"))
    }

    @Test
    fun germanCapitalizesNouns() {
        val out = SuggestionEngine.suggest("entschuldig", previous = null, language = "de")
        assertTrue("got $out", out.any { it == "Entschuldigung" })
        val haus = SuggestionEngine.suggest("hau", previous = null, language = "de")
        assertTrue("got $haus", haus.any { it == "Haus" })
    }

    @Test
    fun germanKeepsVerbsLowercase() {
        val out = SuggestionEngine.suggest("mach", previous = null, language = "de")
        assertTrue("got $out", out.none { it.first().isUpperCase() })
    }

    @Test
    fun germanFixesAdjacentKeySlip() {
        assertEquals("nicht", SuggestionEngine.autocorrect("nixht", previous = null, language = "de"))
        assertEquals("haben", SuggestionEngine.autocorrect("habrn", previous = null, language = "de"))
    }

    @Test
    fun germanFixesUmlautConfusion() {
        assertEquals("für", SuggestionEngine.autocorrect("fur", previous = null, language = "de"))
    }

    @Test
    fun germanNeverTouchesRealWords() {
        assertNull(SuggestionEngine.autocorrect("Haus", previous = null, language = "de"))
        assertNull(SuggestionEngine.autocorrect("wetter", previous = null, language = "de"))
        assertNull(SuggestionEngine.autocorrect("Straße", previous = null, language = "de"))
    }

    @Test
    fun germanPredictsFromBigrams() {
        val out = SuggestionEngine.suggest("", previous = "vielen", language = "de")
        assertTrue("got $out", out.isNotEmpty())
    }

    @Test
    fun germanCompletesUmlautWords() {
        val out = SuggestionEngine.suggest("mögl", previous = null, language = "de")
        assertTrue("got $out", out.any { it.startsWith("mögl") })
        val gr = SuggestionEngine.suggest("gro", previous = null, language = "de")
        assertTrue("got $gr", gr.isNotEmpty())
    }

    @Test
    fun germanRoutesByActiveLayoutNotJustScript() {
        val de = SuggestionEngine.suggest("wass", previous = null, language = "de")
        val en = SuggestionEngine.suggest("wass", previous = null, language = "en")
        assertTrue("de=$de en=$en", de != en)
    }

    @Test
    fun germanUmlautStartRoutesToGermanEvenOnEnglishLayout() {
        val out = SuggestionEngine.suggest("über", previous = null, language = "en")
        assertTrue("got $out", out.isEmpty() || out.any { it.lowercase().startsWith("über") })
    }
}
