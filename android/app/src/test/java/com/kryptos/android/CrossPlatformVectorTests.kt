package com.kryptos.android

import com.kryptos.android.core.Deflate
import com.kryptos.android.core.KryptosCore
import com.kryptos.android.core.LsbStego
import com.kryptos.android.core.Padding
import com.kryptos.android.core.PasswordCipher
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.StegoLanguage
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import com.kryptos.android.screen.ScreenDecryptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val token = "hlNbZJ-F55jSK4Pm9yLSDIha3R5rC9k3dtyWXkH7TXoESjZH-TW2dwNrwGXNXhmmgE41wbyf0prZRAgP"
        assertEquals("привет, Android! 🔐", KryptosCore.decrypt(token, "correct horse"))
    }

    @Test
    fun decryptsPaddedPasswordTokenFromIos() {
        val token = "WMte5Zloe0ZEaKp8GeM88rliC5hn2FzkTb9fogC_gWDZdhLbPBdpmrB8wUKnPW7il1W3Mu75wmRvo0fIS1g1DaC7ckFzxWl3fKZ-m3cP2cqSthDfy38xtcxt_r-D5g_-CA"
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
    fun extractsLsbPayloadFromIosPixels() {
        val hex = "000100ff020506ff070b0eff090e15ff0d141cff0e1823ff121f2aff142331ff182839ff1b2c3eff1e3346ff" +
            "20364cff243c54ff26405aff2a4662ff2c4a68ff305070ff325476ff365a7eff385e84ff3d648dff3f6893ff426e9bff" +
            "4473a1ff4879a8ff4b7daeff4e82b6ff5087bcff558dc4ff5790cbff5a96d3ff5d9ad8ff60a1e0ff63a4e6ff67abeeff" +
            "69aef4ff6cb4fdff6eb802ff73be0bff75c211ff78c918ff7bcc1fff7ed226ff81d62cff84dd34ff87e03bff8ae742ff" +
            "8ceb49ff90f150ff93f457ff96fa5eff99ff65ff9c046cff9f0973ffa20e7affa51381ffa81888ffab1d8fffae2296ff" +
            "b1279dffb42ca4ffb731abffba36b2ffbd3bb9ff"
        val pixels = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        assertEquals("KX-LSB-TEST", String(LsbStego.extract(pixels), Charsets.UTF_8))
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
            val words = TextStego.encode(payload, lang)
            val screen = "Алексей: $words изменено 2:14 PM ✓✓"
            assertArrayEquals(payload, TextStego.decode(screen))
            assertTrue(TextStego.mightBeStego(screen))
            assertTrue(ScreenDecryptor.quickCheck(screen))

            val smart = SmartTextStego.encode(payload, lang)
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
            val words = TextStego.encode(payload, lang)
            assertArrayEquals(payload, TextStego.decode(words))
        }
        assertNull(TextStego.decode("just some ordinary words that mean nothing"))
    }

    @Test
    fun matchesIosStegoVectors() {
        val expected = ByteArray(0x21) { it.toByte() }
        val en = "Gang max lyla jen, tons vet? Light rap tum jinx con ira atm. " +
            "Kev, dina rap bead bong feet zoo. Loco, dev code, kev fdr?"
        val ru = "Книги велел трупы тигр ним. Рейс линг ген, света шанса, сцену багаж, уход эрика. " +
            "Лок, тан сдай игре полы несу танк пулю скоро? Эйр крут!"
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
    fun lsbRoundTrip() {
        val pixels = ByteArray(400 * 4) { (it * 13).toByte() }
        val payload = "hidden payload".toByteArray()
        val stego = LsbStego.embed(payload, pixels)
        assertArrayEquals(payload, LsbStego.extract(stego))
        for (i in 3 until stego.size step 4) assertEquals(pixels[i], stego[i])
    }

    @Test
    fun rejectsForgedHugeLsbLength() {
        val header = byteArrayOf(0x4B, 0x58, 0x53, 0x31, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val rgba = ByteArray(32 * 4)
        var bit = 0
        for (b in header) for (i in 7 downTo 0) {
            val idx = (bit / 3) * 4 + bit % 3
            rgba[idx] = ((rgba[idx].toInt() and 0xFE) or ((b.toInt() shr i) and 1)).toByte()
            bit++
        }
        try {
            LsbStego.extract(rgba)
            throw AssertionError("should have thrown")
        } catch (e: com.kryptos.android.core.CipherException) {
            assertEquals(com.kryptos.android.core.CipherException.Kind.NO_HIDDEN_DATA, e.kind)
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
        assertNotNull(TextStego.decode(TextStego.encode(byteArrayOf(1, 2, 3), StegoLanguage.RUSSIAN)))
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
}
