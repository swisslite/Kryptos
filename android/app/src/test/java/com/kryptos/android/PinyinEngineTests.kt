package com.kryptos.android

import com.kryptos.android.keyboard.PinyinEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class PinyinEngineTests {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val assets = File("src/main/assets/dict")
            PinyinEngine.loadForTest(
                File(assets, "pinyin-zh.txt").inputStream(),
                File(assets, "pinyin-syllables.txt").inputStream(),
            )
        }
    }

    private fun top(input: String, count: Int = 8): List<String> =
        PinyinEngine.candidates(input, count).map { it.text }

    private fun first(input: String): String = top(input, 1).firstOrNull() ?: ""

    @Test
    fun loadsDictionary() {
        assertTrue(PinyinEngine.isLoaded)
    }

    @Test
    fun fullPinyinPicksTheCommonWord() {
        assertEquals("你好", first("nihao"))
        assertEquals("我们", first("women"))
        assertEquals("中国", first("zhongguo"))
        assertEquals("谢谢", first("xiexie"))
        assertEquals("什么", first("shenme"))
        assertEquals("明天", first("mingtian"))
    }

    @Test
    fun composesSentencesAcrossWords() {
        assertEquals("你好吗", first("nihaoma"))
        assertEquals("我爱", first("woai"))
        assertEquals("今天天气真好", first("jintiantianqizhenhao"))
        assertEquals("中华人民共和国", first("zhonghuarenmingongheguo"))
    }

    @Test
    fun initialsOnlyInputResolves() {
        assertEquals("你好", first("nh"))
        assertEquals("我们", first("wm"))
        assertEquals("谢谢", first("xx"))
    }

    @Test
    fun apostropheForcesSyllableBoundary() {
        assertEquals("西安", first("xi'an"))
        assertEquals("先", first("xian"))
    }

    @Test
    fun secondaryReadingsDoNotOutrankPrimaryOnes() {
        val xian = top("xian")
        assertTrue("见 is a jian reading and must not lead xian", !xian.take(4).contains("见"))
        assertTrue(xian.contains("先"))
    }

    @Test
    fun candidatesReportConsumedInput() {
        val full = PinyinEngine.candidates("nihao", 1).first()
        assertEquals("你好", full.text)
        assertEquals(5, full.consumed)

        val partial = PinyinEngine.candidates("mingtian", 8).first { it.text == "明" }
        assertEquals(4, partial.consumed)

        val withApostrophe = PinyinEngine.candidates("xi'an", 1).first()
        assertEquals(5, withApostrophe.consumed)
    }

    @Test
    fun unknownInputIsHandledWithoutCrashing() {
        assertTrue(PinyinEngine.candidates("", 8).isEmpty())
        assertTrue(PinyinEngine.candidates("'''", 8).isEmpty())
        assertTrue(PinyinEngine.candidates("qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq", 8).size <= 8)
        assertTrue(PinyinEngine.candidates("zzz", 8).isNotEmpty())
    }

    @Test
    fun candidatesAreDistinctAndBounded() {
        for (probe in listOf("ni", "q", "xx", "shi", "zhong", "a")) {
            val list = PinyinEngine.candidates(probe, 9)
            assertTrue(probe, list.size <= 9)
            assertEquals(probe, list.map { it.text }.distinct().size, list.size)
            assertTrue(probe, list.all { it.consumed in 1..probe.length })
        }
    }

    @Test
    fun trailingApostropheChangesNothingButIsConsumed() {
        assertEquals(top("xi"), top("xi'"))
        assertEquals(3, PinyinEngine.candidates("xi'", 1).first().consumed)
        assertEquals(top("nihao"), top("nihao'"))
    }
}
