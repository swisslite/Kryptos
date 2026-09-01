package com.kryptos.android

import com.kryptos.android.core.Argon2id
import com.kryptos.android.core.Deflate
import com.kryptos.android.core.KryptosCore
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.Padding
import com.kryptos.android.core.PasswordCipher
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.StegoLanguage
import com.kryptos.android.core.StegoTokenizer
import com.kryptos.android.core.StegoSafety
import com.kryptos.android.core.Wordlists
import com.kryptos.android.core.SmartStegoData
import com.kryptos.android.core.StegoWire
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import com.kryptos.android.screen.ScreenDecryptor
import com.kryptos.android.signal.DecryptCacheKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPlatformVectorTests {

    @Test
    fun matchesIosWireVector() {
        val body = ByteArray(80) { it.toByte() }
        val pairKey = "alicebob".toByteArray(Charsets.UTF_8)
        val salt = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        val token = WireFormat.wrap(body, 3, false, false, pairKey, salt)
        assertEquals(
            "ESIzRFVmd4j9V93M7t_5BfiySUrpS5b-bJc5XQxr8yhQ-TLQRriqac8X9u92dvjUFGVCBG8zNr0TC3zv-i-436Ueg1dvXYdP8nSb8hMQaVbr4ChluV4c-aI",
            token,
        )
        val u = WireFormat.unwrap(token, pairKey)!!
        assertEquals(3, u.type)
        assertFalse(u.deflate)
        assertArrayEquals(body, u.body)
    }

    @Test
    fun wirePaddedGivesExactBucketLength() {
        val pairKey = "pair".toByteArray()
        val t40 = WireFormat.wrap(ByteArray(40), 2, false, true, pairKey)
        val t55 = WireFormat.wrap(ByteArray(55), 2, false, true, pairKey)
        val t200 = WireFormat.wrap(ByteArray(200), 2, false, true, pairKey)
        assertEquals(t40.length, t55.length)
        assertTrue(t200.length > t40.length)
        val u40 = WireFormat.wrap(ByteArray(40), 2, false, false, pairKey)
        val u55 = WireFormat.wrap(ByteArray(55), 2, false, false, pairKey)
        assertTrue(u40.length != u55.length)
        assertArrayEquals(ByteArray(40), WireFormat.unwrap(t40, pairKey)!!.body)
    }

    @Test
    fun paddingMatchesIosBuckets() {
        assertEquals(64, Padding.target(0))
        assertEquals(64, Padding.target(60))
        assertEquals(64, Padding.target(64))
        assertEquals(128, Padding.target(65))
        assertEquals(128, Padding.target(128))
        assertEquals(256, Padding.target(129))
        assertEquals(1024, Padding.target(1000))
        assertEquals(1 shl 20, Padding.target(1 shl 20))
        assertEquals(2 shl 20, Padding.target((1 shl 20) + 1))
    }

    @Test
    fun paddingFrameUnframeRoundTrip() {
        for (n in intArrayOf(0, 1, 60, 61, 200, 5000)) {
            val content = ByteArray(n) { (it * 13 and 0xFF).toByte() }
            val framed = Padding.frame(content)
            assertEquals(Padding.target(4 + n), framed.size)
            assertArrayEquals(content, Padding.unframe(framed))
        }
        assertNull(Padding.unframe(byteArrayOf(0, 0)))
        assertNull(Padding.unframe(byteArrayOf(-1, -1, -1, -1, 1)))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun stegoPayload(ciphertext: Int, padded: Boolean): ByteArray =
        StegoWire.frame(ByteArray(ciphertext) { (it * 31 and 0xFF).toByte() }, 2, false, padded)

    private fun unwrapStegoPayload(payload: ByteArray): ByteArray? = StegoWire.unframe(payload)?.body

    @Test
    fun paddedStegoPayloadRoundTrips() {
        for (ciphertext in intArrayOf(1, 40, 55, 60, 300)) {
            val body = ByteArray(ciphertext) { (it * 17 and 0xFF).toByte() }
            val payload = StegoWire.frame(body, 3, true, true)
            assertEquals(StegoWire.payloadSize(ciphertext, true), payload.size)
            val back = StegoWire.unframe(payload)!!
            assertArrayEquals(body, back.body)
            assertEquals(3, back.type)
            assertTrue(back.deflate)
        }
        assertNull(StegoWire.unframe(ByteArray(0)))
        assertNull(StegoWire.unframe(byteArrayOf(0x03)))
        assertNull(StegoWire.unframe(byteArrayOf(0x04, 0x22, 0, 0, 0, 0)))
        assertNull(StegoWire.unframe(byteArrayOf(0x03, 0x22, -1, -1, -1, -1)))
    }

    @Test
    fun paddedStegoHidesCiphertextLength() {
        val sameBucket = intArrayOf(1, 20, 40, 55)
        for (language in StegoLanguage.entries) {
            val wordCounts = HashSet<Int>()
            val letterLengths = HashSet<Int>()
            val smartWordCounts = HashSet<Int>()
            for (ciphertext in sameBucket) {
                val payload = stegoPayload(ciphertext, padded = true)
                assertEquals(2 + Padding.target(4 + ciphertext), payload.size)
                val words = requireNotNull(TextStego.encode(payload, language))
                wordCounts.add(StegoTokenizer.split(words).size)
                letterLengths.add(requireNotNull(LetterStego.encode(payload, language)).length)
                val smart = requireNotNull(SmartTextStego.encode(payload, language))
                smartWordCounts.add(StegoTokenizer.split(smart).size)
                assertArrayEquals(unwrapStegoPayload(payload), unwrapStegoPayload(TextStego.decode(words)!!))
            }
            assertEquals("$language word count leaks the ciphertext size", 1, wordCounts.size)
            assertEquals("$language letter run leaks the ciphertext size", 1, letterLengths.size)
            assertTrue("$language smart sentence count varies too much", smartWordCounts.size <= 4)
        }
    }

    @Test
    fun opensIosPaddedStegoPayload() {
        val expected = hex("001f3e5d7c9bbad9f81736557493b2d1f00f2e4d6c8baac9e80726456483a2c1e0ff1e3d5c7b9ab9")
        val words = "Чжи, бьёт коул лежи мост? Курю сол пойму, вэл бар узнаю вудс. " +
            "Сан брюс людях, видим езжу комы одной, жди айк. Долю, этажа сорок, замке шли. " +
            "Сыра, леви рой, трёх муки заказ дениз. Хочу хах чушь, глен бон пил тима алек кусок. " +
            "Туфли, тоже крем хор араб."
        val letters = "вжъывфкчмсзтнмишщтввбьдмцуьгргафъгадшсгшубцкйрересъпсюемъуижбыгдпбмашсеаобжгйю" +
            "авяьфвибгнджвръньалунцчсауаэрцгтси"
        for (cover in listOf(TextStego.decode(words), LetterStego.decode(letters))) {
            val payload = assertNotNull(cover).let { cover!! }
            assertEquals(0x03, payload[0].toInt() and 0xFF)
            assertEquals(0x20, payload[1].toInt() and 0x20)
            assertArrayEquals(expected, unwrapStegoPayload(payload))
        }
    }

    @Test
    fun stegoPaddingIsDroppedBeforeStegoIsAbandoned() {
        for (ciphertext in intArrayOf(16382, 20000, 32000)) {
            assertTrue("$ciphertext must still fit unpadded", StegoWire.fits(ciphertext, false))
            assertFalse("$ciphertext must not fit padded", StegoWire.fits(ciphertext, true))
        }
        for (ciphertext in intArrayOf(70, 200, 1900, 16000)) {
            assertTrue("$ciphertext is a realistic size and must stay padded", StegoWire.fits(ciphertext, true))
        }
        assertEquals(2 + 64, StegoWire.payloadSize(40, true))
        assertEquals(2 + 40, StegoWire.payloadSize(40, false))
    }

    @Test
    fun unpaddedStegoStillLeaksLength() {
        val short = stegoPayload(5, padded = false)
        val long = stegoPayload(55, padded = false)
        assertNotEquals(
            requireNotNull(LetterStego.encode(short, StegoLanguage.RUSSIAN)).length,
            requireNotNull(LetterStego.encode(long, StegoLanguage.RUSSIAN)).length,
        )
    }

    @Test
    fun stegoPaddingSeparatesBuckets() {
        assertEquals(64, Padding.target(4 + 55))
        assertEquals(128, Padding.target(4 + 70))
        assertTrue(
            requireNotNull(LetterStego.encode(stegoPayload(55, padded = true), StegoLanguage.RUSSIAN)).length <
                requireNotNull(LetterStego.encode(stegoPayload(70, padded = true), StegoLanguage.RUSSIAN)).length,
        )
    }

    @Test
    fun passwordPaddedHidesLength() {
        val a = KryptosCore.encrypt("да", "pw", pad = true)
        val b = KryptosCore.encrypt("нет, совсем другой текст!", "pw", pad = true)
        assertEquals(a.length, b.length)
        assertEquals("да", KryptosCore.decrypt(a, "pw"))
        assertEquals("нет, совсем другой текст!", KryptosCore.decrypt(b, "pw"))
    }

    @Test
    fun decryptsNewPasswordTokenFromIos() {
        val token = "WD2pBKVtaz_z8uflmZgVrQENDICw24TwRbMHOeFuS__0PEMbvXIVSkGW6hfESSV29pVEO4-JP1O9FFNMuA"
        assertEquals("привет, Android! 🔐", KryptosCore.decrypt(token, "correct horse"))
    }

    @Test
    fun decryptsPaddedPasswordTokenFromIos() {
        val token = "GhcFSX4rx33SWkSqOZKJPQFVgHpSSzmriVklKjYKt_im_mdrvOZdBurM_hJUvMrK-k1bH4LzUq4uA_a5J6GLxTuOndoctV6ZbMeGwxaQgDVNb8OOgRt3SARhrIKaDDQr3XY"
        assertEquals("секрет", KryptosCore.decrypt(token, "pw"))
    }

    @Test
    fun wireRoundTripAcrossSizesWithCompression() {
        val pairKey = "shared-pair".toByteArray(Charsets.UTF_8)
        for (n in intArrayOf(1, 33, 200, 2048)) {
            val body = ByteArray(n) { (it * 7 and 0xFF).toByte() }
            val token = WireFormat.wrap(body, 2, true, false, pairKey)
            val u = WireFormat.unwrap(token, pairKey)!!
            assertEquals(2, u.type)
            assertTrue(u.deflate)
            assertArrayEquals(body, u.body)
        }
    }

    @Test
    fun wireUnwrapWrongPairKeyNeverRecoversBody() {
        val body = ByteArray(80) { (it * 3 and 0xFF).toByte() }
        val token = WireFormat.wrap(body, 2, false, false, "one".toByteArray())
        for (i in 0 until 64) {
            WireFormat.unwrap(token, "k$i".toByteArray())?.let {
                assertFalse(it.body.contentEquals(body))
            }
        }
        assertArrayEquals(body, WireFormat.unwrap(token, "one".toByteArray())!!.body)
    }

    @Test
    fun decompressesIosDeflate() {
        val hex = "f32eaa2c28c92f56284e4d2e2d4a55c84d2d2e4e4c4f55f01e151e7ac200"
        val deflated = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val expected = "Kryptos secure message ".repeat(20)
        assertEquals(expected, String(Deflate.decompress(deflated)!!, Charsets.UTF_8))
    }

    @Test
    fun deflateRoundTrip() {
        val data = "ab".repeat(500).toByteArray(Charsets.UTF_8)
        val comp = Deflate.compress(data)!!
        assertTrue(comp.size < data.size)
        assertArrayEquals(data, Deflate.decompress(comp))
        assertNull(Deflate.compress("x".toByteArray()))
    }

    @Test
    fun deflateExactLimitRoundTrip() {
        val data = ByteArray(64 * 1024)
        val comp = Deflate.compress(data)!!
        assertEquals(64 * 1024, Deflate.decompress(comp, 64 * 1024)!!.size)
        assertNull(Deflate.decompress(comp, 64 * 1024 - 1))
    }

    @Test
    fun deflateHandlesGarbageWithoutHang() {
        val rng = java.util.Random(1)
        repeat(50) {
            val garbage = ByteArray(200).also { rng.nextBytes(it) }
            Deflate.decompress(garbage)?.let { assertTrue(it.size <= Deflate.MAX_OUTPUT) }
        }
        assertNull(Deflate.decompress(byteArrayOf(-1, -1, -1, -1)))
        assertNull(Deflate.decompress(ByteArray(0)))
    }

    @Test
    fun newPasswordTokenIsPrefixFree() {
        val token = KryptosCore.encrypt("тайное сообщение", "pass phrase")
        assertFalse(token.contains("BEGIN"))
        assertFalse(token.contains("KX1:"))
        assertTrue(KryptosCore.containsMessage(token))
        assertEquals("тайное сообщение", KryptosCore.decrypt(token, "pass phrase"))
    }

    @Test
    fun rejectsCorruptedStego() {
        val payload = ByteArray(64) { (it * 3 and 0xFF).toByte() }
        val text = TextStego.encode(payload, StegoLanguage.ENGLISH, seed = 7)
        val tokens = text.split(" ")
        assertNull(TextStego.decode(tokens.dropLast(2).joinToString(" ")))
        fun clean(s: String) = s.lowercase().filter { it.isLetter() }
        val k = (2 until tokens.size - 2).first { clean(tokens[it]) != clean(tokens[it + 1]) }
        val swapped = tokens.toMutableList().also { val t = it[k]; it[k] = it[k + 1]; it[k + 1] = t }
        assertNull(TextStego.decode(swapped.joinToString(" ")))
    }


    @Test
    fun passwordRoundTrip() {
        val armored = KryptosCore.encrypt("secret message", "pass phrase")
        assertTrue(KryptosCore.containsMessage(armored))
        assertEquals("secret message", KryptosCore.decrypt(armored, "pass phrase"))
    }

    @Test(expected = Exception::class)
    fun passwordWrongPasswordFails() {
        KryptosCore.decrypt(KryptosCore.encrypt("x", "right"), "wrong")
    }

    @Test
    fun tokenSurvivesSurroundingProse() {
        val token = WireFormat.token(PasswordCipher.encrypt("hi".toByteArray(), "p"))
        assertEquals("hi", KryptosCore.decrypt("смотри: $token привет", "p"))
    }

    @Test
    fun stegoSurvivesScreenChrome() {
        val payload = ByteArray(64) { (it * 3).toByte() }
        for (lang in StegoLanguage.entries) {
            val words = requireNotNull(TextStego.encode(payload, lang))
            val screen = "Алексей: $words изменено 2:14 PM ✓✓"
            assertArrayEquals(payload, TextStego.decode(screen))
            assertTrue(TextStego.mightBeStego(screen))
            assertTrue(ScreenDecryptor.quickCheck(screen))

            val smart = requireNotNull(SmartTextStego.encode(payload, lang))
            val smartScreen = "Michael: $smart edited изменено 14:52"
            assertArrayEquals(payload, SmartTextStego.decode(smartScreen))
            assertTrue(SmartTextStego.mightBeStego(smartScreen))
            assertTrue(ScreenDecryptor.quickCheck(smartScreen))
        }
    }

    @Test
    fun screenQuickCheckFindsTokenInsideNodeText() {
        val token = WireFormat.wrap(ByteArray(80) { it.toByte() }, 2, false, false, "alicebob".toByteArray())
        assertTrue(ScreenDecryptor.quickCheck(token))
        assertTrue(ScreenDecryptor.quickCheck("Иван: $token 14:52 ✓✓"))
        assertTrue(ScreenDecryptor.quickCheck("$token\n14:52"))
        assertEquals(token, WireFormat.extractToken("Иван: $token\n14:52"))
        assertFalse(ScreenDecryptor.quickCheck("обычный текст сообщения без всякого шифра внутри, 14:52"))
    }

    @Test
    fun textStegoRoundTripBothLanguages() {
        val payload = ByteArray(200) { (it * 7).toByte() }
        for (lang in StegoLanguage.entries) {
            val words = requireNotNull(TextStego.encode(payload, lang))
            assertArrayEquals(payload, TextStego.decode(words))
        }
        assertNull(TextStego.decode("just some ordinary words that mean nothing"))
    }

    @Test
    fun matchesIosStegoVectors() {
        val expected = ByteArray(0x21) { it.toByte() }
        val en = "Pete rid paso inn, palm hoo? Piece hon sie clam ash mam ere. " +
            "Abi, manu hon dara otis kiss ram. Jace, bea crew, abi jus?"
        val ru = "Штука дилан нашло крыс ним. Гнев мини арт, кости самим, видим велик, змей мешок. " +
            "Мёд, ямы очень кафе окне буря момо тема денег? Маи марс!"
        assertEquals(en, TextStego.encode(expected, StegoLanguage.ENGLISH, seed = 0x5C))
        assertEquals(ru, TextStego.encode(expected, StegoLanguage.RUSSIAN, seed = 0xB3))
        assertArrayEquals(expected, TextStego.decode(en))
        assertArrayEquals(expected, TextStego.decode(ru))
    }

    @Test
    fun stegoMaskVariesFirstWordAcrossSeeds() {
        val payload = ByteArray(24) { 0x42 }
        val firstWords = HashSet<String>()
        for (seed in 0 until 64) {
            firstWords.add(TextStego.encode(payload, StegoLanguage.ENGLISH, seed).substringBefore(' '))
        }
        assertTrue("got ${firstWords.size}", firstWords.size > 32)
    }

    @Test
    fun stegoRoundTripAcrossSizesAndSeeds() {
        for (lang in StegoLanguage.entries) {
            for (n in intArrayOf(0, 1, 2, 3, 16, 33, 127, 128, 200, 1500)) {
                val payload = ByteArray(n) { ((it * 31 + n) and 0xFF).toByte() }
                for (seed in intArrayOf(0, 1, 0x7F, 0xC7, 0xFF)) {
                    assertArrayEquals(
                        "n=$n seed=$seed lang=$lang",
                        payload,
                        TextStego.decode(TextStego.encode(payload, lang, seed)),
                    )
                }
            }
        }
    }



    @Test
    fun rejectsPlainTextAsMessage() {
        assertFalse(KryptosCore.containsMessage("просто обычный текст без токена"))
        try {
            KryptosCore.decrypt("просто обычный текст без токена", "pw")
            throw AssertionError("should have thrown")
        } catch (e: com.kryptos.android.core.CipherException) {
            assertEquals(com.kryptos.android.core.CipherException.Kind.NOT_A_KRYPTOS_MESSAGE, e.kind)
        }
    }

    @Test
    fun wordlistsAreExactly4096Unique() {
        for (lang in StegoLanguage.entries) {
            assertEquals(4096, lang.words.size)
            assertEquals(4096, lang.words.toSet().size)
        }
        assertNotNull(TextStego.decode(requireNotNull(TextStego.encode(byteArrayOf(1, 2, 3), StegoLanguage.RUSSIAN))))
    }

    @Test
    fun smartStegoMatchesIosVectors() {
        val probe = byteArrayOf(
            0x03, 0x02, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x10,
            0x22, 0x77, 0x91.toByte(), 0x04, 0x5C, 0xBE.toByte(),
        )
        val en = SmartTextStego.encode(probe, StegoLanguage.ENGLISH, seed = 0x5C)
        val ru = SmartTextStego.encode(probe, StegoLanguage.RUSSIAN, seed = 0xB3)
        assertEquals(
            "Luckily, his agent clenched their thimble, so this teacher conveyed some platter. " +
                "Seemingly, one customer poked his strap gamely. " +
                "Naturally, one writer found the box.",
            en,
        )
        assertEquals(
            "Часто всякий архитектор распилил некий лоскут, а иной портной почистил твой зубец. " +
                "Честно, мой учитель подпилил тот противень, и любой слесарь заложил один ключ.",
            ru,
        )
        assertArrayEquals(probe, SmartTextStego.decode(en))
        assertArrayEquals(probe, SmartTextStego.decode(ru))
    }

    private val letterProbe = byteArrayOf(
        0x03, 0x02, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x10,
        0x22, 0x77, 0x91.toByte(), 0x04, 0x5C, 0xBE.toByte(),
    )

    @Test
    fun letterStegoMatchesIosVectors() {
        assertEquals(
            "hdfhmfeavxoptfqowalblltnbmbi",
            LetterStego.encode(letterProbe, StegoLanguage.ENGLISH, seed = 0x5C),
        )
        assertEquals(
            "цппцпыяхшчжмяъэавквыцвсчеп",
            LetterStego.encode(letterProbe, StegoLanguage.RUSSIAN, seed = 0xB3),
        )
        assertArrayEquals(letterProbe, LetterStego.decode("hdfhmfeavxoptfqowalblltnbmbi"))
        assertArrayEquals(letterProbe, LetterStego.decode("цппцпыяхшчжмяъэавквыцвсчеп"))
    }

    @Test
    fun letterStegoRoundTripsAcrossSizes() {
        val rng = java.security.SecureRandom()
        for (language in StegoLanguage.entries) {
            for (n in intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 31, 64, 120, 400, 1900)) {
                val payload = ByteArray(n).also { rng.nextBytes(it) }
                val text = requireNotNull(LetterStego.encode(payload, language))
                assertArrayEquals("$language n=$n", payload, LetterStego.decode(text))
            }
        }
    }

    @Test
    fun letterStegoIsShorterThanTheOtherModes() {
        val rng = java.security.SecureRandom()
        val body = ByteArray(120).also { rng.nextBytes(it) }
        body[0] = 0x03
        body[1] = 0x02
        for (language in StegoLanguage.entries) {
            val letters = requireNotNull(LetterStego.encode(body, language)).length
            assertTrue(letters < requireNotNull(SmartTextStego.encode(body, language)).length)
            if (language.isHan) continue
            assertTrue(letters < requireNotNull(TextStego.encode(body, language)).length)
        }
    }

    @Test
    fun letterStegoIsDisjointFromTheOtherModes() {
        for (language in StegoLanguage.entries) {
            for (seed in intArrayOf(0x00, 0x5C, 0xB3, 0xFF)) {
                val letters = LetterStego.encode(letterProbe, language, seed)
                assertNull(TextStego.decode(letters))
                assertNull(SmartTextStego.decode(letters))
                assertNull(LetterStego.decode(TextStego.encode(letterProbe, language, seed)))
                assertNull(LetterStego.decode(SmartTextStego.encode(letterProbe, language, seed)))
            }
        }
    }

    @Test
    fun letterStegoSurvivesScreenChrome() {
        val payload = ByteArray(120) { (it * 7 and 0xFF).toByte() }
        for (language in StegoLanguage.entries) {
            val text = requireNotNull(LetterStego.encode(payload, language))
            assertArrayEquals(payload, LetterStego.decode("Иван  14:52  $text  изменено"))
            assertArrayEquals(payload, LetterStego.decode("$text\nDelivered"))
            assertArrayEquals(payload, LetterStego.decode(text.uppercase()))
            assertTrue(ScreenDecryptor.quickCheck(text))
            assertTrue(ScreenDecryptor.quickCheck("Иван  14:52  $text  изменено"))
        }
    }

    @Test
    fun letterStegoRejectsProse() {
        for (text in listOf(
            "привет как дела сегодня вечером",
            "hello how are you doing today my friend",
            "Съешь ещё этих мягких французских булок да выпей чаю",
            "The quick brown fox jumps over the lazy dog",
        )) {
            assertNull(LetterStego.decode(text))
            assertFalse(LetterStego.mightBeStego(text))
        }
    }

    @Test
    fun letterStegoPrefilterFindsTheRunAfterChrome() {
        val payload = ByteArray(120) { (it * 5 and 0xFF).toByte() }
        for (language in StegoLanguage.entries) {
            val text = requireNotNull(LetterStego.encode(payload, language))
            assertTrue(LetterStego.mightBeStego(text))
            assertTrue(LetterStego.mightBeStego("Иван  14:52  $text"))
            assertTrue(LetterStego.mightBeStego(text.uppercase()))
        }
        assertFalse(LetterStego.mightBeStego("Иван 14:52 короткое слово"))
        assertFalse(LetterStego.mightBeStego(""))
        assertFalse(LetterStego.mightBeStego("абв где ёжз ийк лмн опр сту фхц чшщ ъыь эюя"))
    }

    @Test
    fun letterStegoNeverTrapsOnGarbage() {
        val rng = java.util.Random(20260802)
        val alphabets = listOf("абвгдежзийклмнопрстуфхцчшщъыьэюя", "abcdefghijklmnopqrstuvwxyz")
        repeat(4000) {
            val source = alphabets[rng.nextInt(alphabets.size)]
            val n = 1 + rng.nextInt(120)
            val junk = buildString { repeat(n) { append(source[rng.nextInt(source.length)]) } }
            LetterStego.decode(junk)
            LetterStego.mightBeStego(junk)
        }
    }

    @Test
    fun letterCacheKeyUsesTheStegoPayload() {
        val payload = ByteArray(120) { (it * 11 and 0xFF).toByte() }
        val other = ByteArray(120) { (it * 13 and 0xFF).toByte() }
        for (language in StegoLanguage.entries) {
            val covers = HashSet<String>()
            repeat(24) { covers.add(requireNotNull(LetterStego.encode(payload, language))) }
            assertTrue("$language", covers.size > 1)
            val keys = covers.mapTo(HashSet()) { DecryptCacheKey.of(it) }
            assertEquals("$language", 1, keys.size)
            val otherKey = DecryptCacheKey.of(requireNotNull(LetterStego.encode(other, language)))
            assertTrue("$language", otherKey !in keys)
        }
    }

    @Test
    fun smartStegoRoundTripsAndIsDisjointFromStandard() {
        for (lang in StegoLanguage.entries) {
            for (n in intArrayOf(1, 2, 3, 16, 33, 64, 100, 127, 128, 200, 300, 512, 1000)) {
                val payload = ByteArray(n) { ((it * 37 + 11) and 0xFF).toByte() }
                val text = SmartTextStego.encode(payload, lang, seed = (n * 7) and 0xFF)
                assertArrayEquals("n=$n lang=$lang", payload, SmartTextStego.decode(text))
                assertTrue(SmartTextStego.looksLikeStego(text))
                assertNull(TextStego.decode(text))
            }
        }
        assertNull(SmartTextStego.decode("just some ordinary words that mean nothing at all"))
    }

    @Test
    fun smartStegoVariesFirstWordAcrossSeeds() {
        val payload = ByteArray(24) { 0x42 }
        val firstWords = HashSet<String>()
        for (seed in 0 until 256) {
            firstWords.add(SmartTextStego.encode(payload, StegoLanguage.ENGLISH, seed).substringBefore(' '))
        }
        assertTrue("got ${firstWords.size}", firstWords.size >= 40)
    }

    @Test
    fun cacheKeyMatchesStegoPayloadAndTokenBytes() {
        val payload = ByteArray(64) { ((it * 13 + 5) and 0xFF).toByte() }
        val stego = TextStego.encode(payload, StegoLanguage.ENGLISH, seed = 0x21)
        assertEquals(sha256Hex(payload), DecryptCacheKey.of(stego))
        assertEquals(sha256Hex(payload), DecryptCacheKey.of("14:52 $stego edited"))

        val token = WireFormat.wrap(payload, 3, false, false, "pair".toByteArray(Charsets.UTF_8))
        val raw = WireFormat.tokenBytes(token)!!
        assertEquals(sha256Hex(raw), DecryptCacheKey.of(token))
        assertEquals(sha256Hex(raw), DecryptCacheKey.of("Alice 14:52 $token"))
    }

    @Test
    fun cacheKeyNeverCollapsesToAShortWordRun() {
        val n = 24_000
        val first = TextStego.encode(ByteArray(n) { (it and 0xFF).toByte() }, StegoLanguage.ENGLISH, seed = 0x10)
        val second = TextStego.encode(ByteArray(n) { ((it * 7) and 0xFF).toByte() }, StegoLanguage.ENGLISH, seed = 0x10)
        assertTrue("${first.length}", first.length > 64_000)
        assertTrue("${second.length}", second.length > 64_000)
        assertEquals(sha256Hex(first.toByteArray(Charsets.UTF_8)), DecryptCacheKey.of(first))
        assertTrue(DecryptCacheKey.of(first) != DecryptCacheKey.of(second))
    }

    @Test
    fun cacheKeyIgnoresProseThatCarriesNoToken() {
        val prose = "just some ordinary words that mean nothing at all"
        assertEquals(sha256Hex(prose.toByteArray(Charsets.UTF_8)), DecryptCacheKey.of(prose))
    }

    private fun sha256Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun germanStegoMatchesIosVectors() {
        val payload = ByteArray(0x21) { it.toByte() }
        assertEquals(
            "Meilen zelt abflug flucht umsatz schade junge. Enden mumm lohnt angeln selig. " +
                "Sinnen lauf umwelt bewege freud japp trupp zahl harte. Wozu stelle fliegt, zeile.",
            TextStego.encode(payload, StegoLanguage.GERMAN, seed = 0x41),
        )
        val probe = byteArrayOf(
            0x03, 0x02, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x10,
            0x22, 0x77, 0x91.toByte(), 0x04, 0x5C, 0xBE.toByte(),
        )
        assertEquals(
            "Ehrlich malten meine Priester unsere Geigen. Seine Boten sendeten alle Brunnen. " +
                "Offenbar sperrten diese Enkel meine Boote gerade. " +
                "Bekanntlich benutzten unsere Boten meine Tische zusammen! " +
                "Alle Lehrer stapelten die Tische oft!",
            SmartTextStego.encode(probe, StegoLanguage.GERMAN, seed = 0x41),
        )
    }

    @Test
    fun germanStegoRoundTrips() {
        val rnd = java.util.Random(9)
        for (n in intArrayOf(1, 7, 32, 120)) {
            val data = ByteArray(n).also { rnd.nextBytes(it) }
            assertArrayEquals(data, TextStego.decode(requireNotNull(TextStego.encode(data, StegoLanguage.GERMAN))))
            assertArrayEquals(
                data,
                SmartTextStego.decode(requireNotNull(SmartTextStego.encode(data, StegoLanguage.GERMAN))),
            )
            assertArrayEquals(data, LetterStego.decode(requireNotNull(LetterStego.encode(data, StegoLanguage.GERMAN))))
        }
    }

    @Test
    fun germanStegoSurvivesGermanScreenChrome() {
        val payload = ByteArray(64) { (it * 5).toByte() }
        val chrome = listOf(
            "Anna: %s Bearbeitet 14:52", "%s\nZugestellt", "Weber: %s 09:31 ✓✓",
            "Richter: %s Gelesen", "Ritter: %s 08:04", "Koch: %s Heute", "Bauer: %s Wolf 12:00",
        )
        for (frame in chrome) {
            val words = frame.format(requireNotNull(TextStego.encode(payload, StegoLanguage.GERMAN)))
            assertArrayEquals("words in <$frame>", payload, TextStego.decode(words))
            assertTrue(ScreenDecryptor.quickCheck(words))

            val smart = frame.format(requireNotNull(SmartTextStego.encode(payload, StegoLanguage.GERMAN)))
            assertArrayEquals("smart in <$frame>", payload, SmartTextStego.decode(smart))
            assertTrue(ScreenDecryptor.quickCheck(smart))

            val letters = frame.format(requireNotNull(LetterStego.encode(payload, StegoLanguage.GERMAN)))
            assertArrayEquals("letters in <$frame>", payload, LetterStego.decode(letters))
            assertTrue(ScreenDecryptor.quickCheck(letters))
        }
    }

    @Test
    fun chineseStegoMatchesIosVectors() {
        val payload = ByteArray(0x21) { it.toByte() }
        assertEquals(
            "掷行睛难，浮班萦吻朱袁特唉骂毛晨吻。疲墩暮戴锉洞瓜，毛腰。",
            TextStego.encode(payload, StegoLanguage.CHINESE, seed = 0x5C),
        )
        assertEquals(
            "我的兄弟准备多数手表，然后全部演员更换那些雨伞。据说，多数老人展示他的椅子。" +
                "他的邻居已经核对不少地图。前天，大量村民购买这些书本，而且这些学生购买这些书本。",
            SmartTextStego.encode(letterProbe, StegoLanguage.CHINESE, seed = 0x5C),
        )
        assertEquals(
            "它讲带玩新把睡问无糟另水呀却带我",
            LetterStego.encode(letterProbe, StegoLanguage.CHINESE, seed = 0x5C),
        )
        assertArrayEquals(payload, TextStego.decode("掷行睛难，浮班萦吻朱袁特唉骂毛晨吻。疲墩暮戴锉洞瓜，毛腰。"))
        assertArrayEquals(letterProbe, LetterStego.decode("它讲带玩新把睡问无糟另水呀却带我"))
    }

    @Test
    fun persianStegoMatchesIosVectors() {
        val payload = ByteArray(0x21) { it.toByte() }
        assertEquals(
            "صاحب کنید لین نباید، نکنیم ازدواج؟ دکستر میتونه نزن دادین چطوری عشق یارو. گذشته، سوزی میتونه محاصره یو اسمت هیچوقت. چجور، عاشق میفهمی، گذشته نسبت؟",
            TextStego.encode(payload, StegoLanguage.PERSIAN, seed = 0x5C),
        )
        assertEquals(
            "وانگهی، کشاورز آن سوزن را شمرد، اما نانوا یک جعبه را دوشید. امسال عکاس یک روبان را سپرد. دیشب خریدار آن میخ را دوشید، اما ماهیگیر شش قوری را برداشت.",
            SmartTextStego.encode(letterProbe, StegoLanguage.PERSIAN, seed = 0x5C),
        )
        assertEquals(
            "رظضچدزعطصپییغجچژچچعتکرمثتل",
            LetterStego.encode(letterProbe, StegoLanguage.PERSIAN, seed = 0x5C),
        )
        assertArrayEquals(payload, TextStego.decode("صاحب کنید لین نباید، نکنیم ازدواج؟ دکستر میتونه نزن دادین چطوری عشق یارو. گذشته، سوزی میتونه محاصره یو اسمت هیچوقت. چجور، عاشق میفهمی، گذشته نسبت؟"))
        assertArrayEquals(letterProbe, SmartTextStego.decode("وانگهی، کشاورز آن سوزن را شمرد، اما نانوا یک جعبه را دوشید. امسال عکاس یک روبان را سپرد. دیشب خریدار آن میخ را دوشید، اما ماهیگیر شش قوری را برداشت."))
        assertArrayEquals(letterProbe, LetterStego.decode("رظضچدزعطصپییغجچژچچعتکرمثتل"))
    }

    @Test
    fun persianStegoRoundTrips() {
        val rnd = java.util.Random(29)
        for (n in intArrayOf(1, 3, 16, 64, 127, 200, 512, 1500)) {
            val data = ByteArray(n).also { rnd.nextBytes(it) }
            val words = requireNotNull(TextStego.encode(data, StegoLanguage.PERSIAN))
            assertArrayEquals("words n=$n", data, TextStego.decode(words))
            val smart = requireNotNull(SmartTextStego.encode(data, StegoLanguage.PERSIAN))
            assertArrayEquals("smart n=$n", data, SmartTextStego.decode(smart))
            val letters = requireNotNull(LetterStego.encode(data, StegoLanguage.PERSIAN))
            assertArrayEquals("letters n=$n", data, LetterStego.decode(letters))
        }
    }

    @Test
    fun persianWordsSurvivesLeadingChatter() {
        val data = ByteArray(48) { (it * 5 + 1).toByte() }
        val cover = requireNotNull(TextStego.encode(data, StegoLanguage.PERSIAN))
        val chatter = listOf("سلام", "امروز", "خیلی", "کتاب", "دوست", "خانه", "این")
        for (junk in 1..7) {
            val text = chatter.take(junk).joinToString(" ") + " " + cover
            assertArrayEquals("junk=$junk", data, TextStego.decode(text))
        }
    }

    @Test
    fun chineseStegoRoundTrips() {
        val rnd = java.util.Random(11)
        for (n in intArrayOf(1, 3, 16, 64, 127, 200, 512, 1500)) {
            val data = ByteArray(n).also { rnd.nextBytes(it) }
            val words = requireNotNull(TextStego.encode(data, StegoLanguage.CHINESE))
            assertArrayEquals("words n=$n", data, TextStego.decode(words))
            val smart = requireNotNull(SmartTextStego.encode(data, StegoLanguage.CHINESE))
            assertArrayEquals("smart n=$n", data, SmartTextStego.decode(smart))
            val letters = requireNotNull(LetterStego.encode(data, StegoLanguage.CHINESE))
            assertEquals("letters n=$n", n + 4, letters.length)
            assertArrayEquals("letters n=$n", data, LetterStego.decode(letters))
        }
    }

    @Test
    fun chineseStegoSurvivesChineseScreenChrome() {
        val payload = ByteArray(96) { (it * 5).toByte() }
        val chrome = listOf("李明: %s 14:52", "Anna: %s edited", "%s\n已读", "王芳  昨天  %s  ✓✓")
        for (frame in chrome) {
            val words = frame.format(requireNotNull(TextStego.encode(payload, StegoLanguage.CHINESE)))
            assertArrayEquals("words in <$frame>", payload, TextStego.decode(words))
            assertTrue(ScreenDecryptor.quickCheck(words))

            val smart = frame.format(requireNotNull(SmartTextStego.encode(payload, StegoLanguage.CHINESE)))
            assertArrayEquals("smart in <$frame>", payload, SmartTextStego.decode(smart))
            assertTrue(ScreenDecryptor.quickCheck(smart))

            val letters = frame.format(requireNotNull(LetterStego.encode(payload, StegoLanguage.CHINESE)))
            assertArrayEquals("letters in <$frame>", payload, LetterStego.decode(letters))
            assertTrue(ScreenDecryptor.quickCheck(letters))
        }
    }

    @Test
    fun chineseSmartRecoversFromOddLeadingJunk() {
        val payload = ByteArray(64) { (it * 3).toByte() }
        val smart = requireNotNull(SmartTextStego.encode(payload, StegoLanguage.CHINESE))
        for (junk in listOf("我", "我的兄", "他", "学生老")) {
            assertArrayEquals("junk=$junk", payload, SmartTextStego.decode(junk + smart))
        }
    }

    @Test
    fun chineseCoversCarryOnlyHanAndFullWidthPunctuation() {
        val payload = ByteArray(240) { (it * 7).toByte() }
        val words = requireNotNull(TextStego.encode(payload, StegoLanguage.CHINESE))
        for (c in words) assertTrue("words: $c", StegoTokenizer.isHan(c.code) || c in "，。？！")
        val smart = requireNotNull(SmartTextStego.encode(payload, StegoLanguage.CHINESE))
        for (c in smart) assertTrue("smart: $c", StegoTokenizer.isHan(c.code) || c in "，。！\n")
        val letters = requireNotNull(LetterStego.encode(payload, StegoLanguage.CHINESE))
        for (c in letters) assertTrue("letters: $c", StegoTokenizer.isHan(c.code))
    }

    @Test
    fun chineseDataSetsCarryNothingBlocked() {
        for (word in Wordlists.chinese) {
            assertEquals(1, word.length)
            assertFalse(word, StegoSafety.blocks(word))
        }
        val tokens = SmartStegoData.chinese.openers + SmartStegoData.chinese.slots.flatten()
        for (token in tokens) {
            assertEquals(2, token.length)
            assertFalse(token, StegoSafety.containsBlocked(token))
        }
        val alphabet = LetterStego.alphabetCharacters(StegoLanguage.CHINESE).toSet()
        assertEquals(256, alphabet.size)
        for (stem in StegoSafety.hanStems) {
            if (stem.length < 2) continue
            assertFalse("alphabet can spell $stem", stem.all { it in alphabet })
        }
    }

    @Test
    fun hanTokenizerSplitsCharactersButKeepsLatinRuns() {
        assertEquals(listOf("你", "好", "world"), StegoTokenizer.split("你好world"))
        assertEquals(listOf("привет", "你", "好"), StegoTokenizer.split("привет 你 好"))
        assertEquals(listOf("hello", "world"), StegoTokenizer.split("hello world"))
        assertEquals(listOf("你好world"), StegoTokenizer.runs("你好world"))
    }

    @Test
    fun chineseBlocklistCoversProfanityAndDangerousTerms() {
        for (character in listOf(
            "屎", "尿", "屁", "贱", "操", "屌", "杀", "枪", "炸", "毒", "尸", "奸", "妓",
        )) {
            assertTrue("single not blocked: $character", StegoSafety.blocks(character))
            assertFalse("still in wordlist: $character", character in Wordlists.chinese)
        }
        for (term in listOf(
            "鸡巴", "龟头", "精液", "拉屎", "撒尿", "狗屎", "你妈", "尼玛", "草泥马", "卧槽", "干你", "混账", "杂种", "野种", "贱货",
            "荡妇", "窑子", "援交", "包养", "二奶", "小三", "三级片", "毛片", "性感", "猥琐", "同志", "蕾丝", "人妖", "男同", "女同",
            "娘炮", "杀死", "砍死", "打死", "弄死", "害死", "灭口", "割喉", "断头", "斩首", "肢解", "去死", "找死", "该死", "自尽", "寻死",
            "跳河", "卧轨", "自残", "割脉", "烧炭", "安眠药", "遗言", "绝笔", "枪支", "弹匣", "炸药", "火药", "雷管", "引爆", "爆破",
            "射杀", "白粉", "溜冰", "吸食", "摇头丸", "黑社会", "混混", "打手", "保护费", "绑票", "撕票", "抢夺", "行窃", "扒手", "小偷",
            "传销", "黑钱", "赃款", "上访", "维权", "联署", "串联", "声援", "围堵", "封路", "打倒", "变天", "暴徒", "乱党", "极权", "专政",
            "万岁", "白纸", "台独", "藏独", "疆独", "港独", "小日本", "鬼佬", "白皮", "洋垃圾", "法轮", "邪教", "洗脑",
        )) {
            assertTrue("term not blocked: $term", StegoSafety.containsBlocked(term))
        }
    }

    @Test
    fun innocentChineseWordsAreNotBlocked() {
        for (word in listOf(
            "鸡蛋", "龟壳", "干净", "死机", "上课", "万一", "白天", "小学", "专心", "同学", "变化", "装修", "男孩", "女孩", "打开", "打算",
            "火车", "火柴", "联系", "声音", "围巾", "封面", "保护", "抢先", "行动", "传统", "洗手", "极限",
        )) {
            assertFalse("false positive: $word", StegoSafety.blocks(word))
            assertFalse("false positive: $word", StegoSafety.containsBlocked(word))
        }
    }

    @Test
    fun noBlockedTermCanBeSpelledByTheChineseDataSets() {
        val pool = Wordlists.chinese.toSet()
        val grammar = (SmartStegoData.chinese.openers + SmartStegoData.chinese.slots.flatten()).toSet()
        val alphabet = LetterStego.alphabetCharacters(StegoLanguage.CHINESE).toSet()
        for (stem in StegoSafety.hanStems) {
            if (stem.length == 1) {
                assertFalse("wordlist can emit $stem", stem in pool)
                assertFalse("alphabet can emit $stem", stem[0] in alphabet)
                continue
            }
            assertFalse("alphabet can spell $stem", stem.all { it in alphabet })
            for (word in grammar) assertFalse("grammar word $word contains $stem", stem in word)
        }
    }
}
