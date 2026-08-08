package com.kryptos.android

import com.kryptos.android.keyboard.SuggestionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LexiconTests {

    private fun build(dict: List<String>, vocab: String): SuggestionEngine.Lexicon {
        val sorted = dict.toTypedArray()
        sorted.sort()
        val ranks = dict.withIndex().associate { (i, w) -> w to i }
        return SuggestionEngine.Lexicon.build(
            sorted,
            { ranks[it] ?: SuggestionEngine.Lexicon.NO_RANK },
            vocab.toByteArray(Charsets.UTF_8),
        )
    }

    private fun words(lex: SuggestionEngine.Lexicon): List<String> =
        (0 until lex.size).map { lex.word(it) }

    @Test
    fun mergesDictAndVocabInSortedOrder() {
        val lex = build(listOf("cat", "apple"), "ant\nbee\ncat\nzebra\n")
        assertEquals(listOf("ant", "apple", "bee", "cat", "zebra"), words(lex))
    }

    @Test
    fun keepsDictRanksAndMarksVocabOnlyWords() {
        val lex = build(listOf("cat", "apple"), "ant\ncat\n")
        val byWord = (0 until lex.size).associate { lex.word(it) to lex.rank(it) }
        assertEquals(1, byWord["apple"])
        assertEquals(0, byWord["cat"])
        assertEquals(SuggestionEngine.Lexicon.NO_RANK, byWord["ant"])
    }

    @Test
    fun skipsBlankLinesAndSurvivesMissingTrailingNewline() {
        val lex = build(emptyList(), "\nalpha\n\nbeta")
        assertEquals(listOf("alpha", "beta"), words(lex))
    }

    @Test
    fun handlesNonAsciiWords() {
        val lex = build(listOf("дом"), "дела\nдом\nстраße\n")
        assertEquals(listOf("дела", "дом", "страße"), words(lex))
        assertTrue(lex.contains("дела"))
        assertTrue(lex.contains("страße"))
        assertFalse(lex.contains("дель"))
    }

    @Test
    fun containsFindsEveryEntryAndRejectsPrefixes() {
        val lex = build(listOf("band"), "ban\nbanana\nband\nbandit\n")
        for (w in listOf("ban", "banana", "band", "bandit")) assertTrue(w, lex.contains(w))
        assertFalse(lex.contains("bandits"))
        assertFalse(lex.contains("ba"))
    }

    @Test
    fun emptyVocabKeepsDictOnly() {
        val lex = build(listOf("b", "a"), "")
        assertEquals(listOf("a", "b"), words(lex))
    }

    @Test
    fun realAssetsBuildSortedAndSearchable() {
        val assets = File("src/main/assets/dict")
        for (code in listOf("en", "ru", "de")) {
            val raw = File(assets, "$code.txt").readLines().filter { it.isNotBlank() }
            val keys = raw.map { it.lowercase() }.distinct()
            val lex = build(keys, File(assets, "vocab-$code.txt").readText())
            assertTrue(code, lex.size >= keys.size)
            var previous = ""
            for (j in 0 until lex.size) {
                val w = lex.word(j)
                assertTrue("$code $previous -> $w", previous <= w)
                previous = w
            }
            for (w in keys.take(200)) assertTrue("$code $w", lex.contains(w))
        }
    }
}
