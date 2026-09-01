package com.kryptos.android

import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartStegoData
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.StegoLanguage
import com.kryptos.android.core.StegoSafety
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.Wordlists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class StegoSafetyTests {

    private fun wordlist(language: StegoLanguage): List<String> = when (language) {
        StegoLanguage.ENGLISH -> Wordlists.english
        StegoLanguage.RUSSIAN -> Wordlists.russian
        StegoLanguage.GERMAN -> Wordlists.german
        StegoLanguage.CHINESE -> Wordlists.chinese
        StegoLanguage.PERSIAN -> Wordlists.persian
    }

    private fun grammar(language: StegoLanguage): SmartStegoData.Grammar = when (language) {
        StegoLanguage.ENGLISH -> SmartStegoData.english
        StegoLanguage.RUSSIAN -> SmartStegoData.russian
        StegoLanguage.GERMAN -> SmartStegoData.german
        StegoLanguage.CHINESE -> SmartStegoData.chinese
        StegoLanguage.PERSIAN -> SmartStegoData.persian
    }

    @Test
    fun matcherSemantics() {
        for (blocked in listOf("пиздец", "Бомбардировка", "gays", "Ficken", "war", "ass")) {
            assertTrue(blocked, StegoSafety.blocks(blocked))
        }
        for (innocent in listOf(
            "тебе", "себя", "хлеб", "потребность", "требую", "небо", "требовать",
            "class", "assist", "glass", "method", "couple", "arsenal", "skill",
            "total", "gunner", "warm", "ward", "hasel", "kante",
        )) {
            assertFalse(innocent, StegoSafety.blocks(innocent))
        }
    }

    @Test
    fun wordListsCarryNothingBlocked() {
        for (language in StegoLanguage.entries) {
            val words = wordlist(language)
            assertEquals(4096, words.size)
            assertEquals(4096, words.toSet().size)
            for (word in words) {
                assertFalse("$language: $word", StegoSafety.blocks(word))
                assertFalse("$language: $word", StegoSafety.containsBlocked(word))
            }
        }
    }

    @Test
    fun grammarCarriesNothingBlocked() {
        for (language in StegoLanguage.entries) {
            val g = grammar(language)
            val tokens = ArrayList<String>(g.openers)
            g.slots.forEach { tokens.addAll(it) }
            g.structures.forEach { row -> tokens.addAll(row.filterNot { it.startsWith("#") }) }
            for (token in tokens) {
                assertFalse("$language: $token", StegoSafety.blocks(token))
                assertFalse("$language: $token", StegoSafety.containsBlocked(token))
            }
        }
    }

    @Test
    fun generatedCoverTextIsCleanInEveryMode() {
        val rnd = Random(20260811)
        for (language in StegoLanguage.entries) {
            repeat(60) {
                val payload = ByteArray(12 + rnd.nextInt(389)).also { rnd.nextBytes(it) }
                val letters = requireNotNull(LetterStego.encode(payload, language))
                for (text in listOf(
                    requireNotNull(TextStego.encode(payload, language)),
                    requireNotNull(SmartTextStego.encode(payload, language)),
                    letters,
                )) {
                    assertFalse("$language: ${text.take(120)}", StegoSafety.containsBlocked(text))
                }
            }
        }
    }

    @Test
    fun handshakeSizedCoverTextIsClean() {
        val rnd = Random(7)
        for (language in StegoLanguage.entries) {
            val payload = ByteArray(1900).also { rnd.nextBytes(it) }
            val letters = requireNotNull(LetterStego.encode(payload, language))
            for (text in listOf(
                requireNotNull(TextStego.encode(payload, language)),
                requireNotNull(SmartTextStego.encode(payload, language)),
                letters,
            )) {
                assertFalse(language.toString(), StegoSafety.containsBlocked(text))
            }
        }
    }

    @Test
    fun everyPayloadFindsACleanSeed() {
        val rnd = Random(3)
        for (language in StegoLanguage.entries) {
            for (n in listOf(12, 60, 200, 800, 1900)) {
                val payload = ByteArray(n).also { rnd.nextBytes(it) }
                val text = LetterStego.encode(payload, language)
                assertTrue("$language n=$n found no clean seed", text != null)
                assertFalse(StegoSafety.containsBlocked(text!!))
            }
        }
    }

    @Test
    fun seedSearchIsBounded() {
        assertEquals(StegoSafety.SEED_ATTEMPTS, StegoSafety.seedAllowance(12))
        assertEquals(StegoSafety.SEED_ATTEMPTS, StegoSafety.seedAllowance(1900))
        assertTrue(StegoSafety.seedAllowance(32767) < 32)
        assertTrue(StegoSafety.seedAllowance(1 shl 20) >= StegoSafety.MIN_SEED_ATTEMPTS)
    }

    @Test
    fun scannerMatchesAcrossEncodings() {
        assertTrue(StegoSafety.containsBlocked("ааабомбаааа"))
        assertTrue(StegoSafety.containsBlocked("xxxTERRORxxx"))
        assertFalse(StegoSafety.containsBlocked("тебе себя хлеб потребность требую"))
        assertFalse(StegoSafety.containsBlocked("class assist glass method couple"))
        assertFalse(StegoSafety.containsBlocked(""))
        assertFalse(StegoSafety.containsBlocked("аб"))
    }

    @Test
    fun matchesIosMatcherOnTheSharedProbe() {
        assertTrue(StegoSafety.containsBlocked("xxпиздецxx"))
        assertTrue(StegoSafety.containsBlocked("aaabombbbb"))
        assertFalse(StegoSafety.containsBlocked("гей"))
        assertEquals(4, StegoSafety.RUNTIME_MIN_LENGTH)
    }

    @Test
    fun blocklistCoversPersianDangerousTerms() {
        for (word in listOf("کیر", "کس", "کون", "کونی", "جنده", "جاکش", "کسکش", "سکس", "سکسی", "پورن", "لخت", "تجاوز", "فاحشه", "شهوت", "زنا", "همجنسگرا", "همجنسباز", "لزبین", "ترنس", "کشتن", "کشتار", "قتل", "قاتل", "اعدام", "جنایت", "ترور", "تروریست", "بمب", "انفجار", "تفنگ", "اسلحه", "گلوله", "موشک", "نارنجک", "شکنجه", "گروگان", "جنگ", "حمله", "خشونت", "شلیک", "مسلح", "چاقو", "خنجر", "جسد", "جنازه", "مخدر", "هروئین", "کوکائین", "تریاک", "حشیش", "معتاد", "اعتیاد", "دزد", "دزدی", "سرقت", "قاچاق", "اختلاس", "رشوه", "کلاهبرداری", "خودکشی", "زندان", "زندانی", "تظاهرات", "اعتراض", "اعتصاب", "شورش", "انقلاب", "براندازی", "آشوب", "دیکتاتور", "کودتا", "سرباز", "ارتش", "حرومزاده", "بیشرف", "پدرسگ", "عوضی", "کثافت", "احمق", "ابله", "کتک", "خفه", "مرگ", "بکش", "میکشمت", "رید", "شاش")) {
            assertTrue(word, StegoSafety.blocks(word))
        }
    }

    @Test
    fun innocentPersianWordsAreNotBlocked() {
        for (word in listOf("سلام", "خوبی", "ممنون", "کتاب", "مدرسه", "خانه", "پنجره", "درخت", "باران", "آفتاب", "کلاس", "معلم", "برادر", "خواهر", "مادر", "پدر", "دوست", "شهر", "خیابان", "ماشین", "قطار", "هواپیما", "بیمارستان", "دکتر", "پرستار", "نان", "چای", "قهوه", "میوه", "سیب", "گربه", "دریا", "کوه", "رودخانه", "ستاره", "ماه", "خورشید", "کارگر", "مهندس", "نویسنده", "نقاش", "خواننده", "ورزش", "فوتبال", "توپ", "بازی", "خنده", "عشق", "زندگی", "امید", "صلح", "دوستی", "مهربان", "زیبا", "بزرگ", "کوچک", "تازه", "روشن", "گرم", "سرد", "شیرین", "کشور", "عکس", "کسی", "تکون", "زنان", "فرزندان", "مقابله", "کوسه", "گوزن", "حمل", "انتقام", "سلامت", "تظاهر", "مسلما", "شهادت", "کشیدن", "بگیر", "دیگه", "یعنی", "معنی", "خونه", "بزن", "پلیس", "درد", "زخم")) {
            assertFalse(word, StegoSafety.blocks(word))
            assertFalse(word, StegoSafety.containsBlocked(word))
        }
    }
}
