package com.kryptos.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Round-trip and tamper tests for the pure-JVM crypto primitives. */
class CoreRoundTripTest {

    private fun hex(data: ByteArray): String =
        data.joinToString("") { "%02x".format(it) }

    // --- padding ---

    @Test
    fun paddingRoundTripAndPowerOfTwoSizing() {
        for (size in listOf(0, 1, 17, 59, 60, 61, 200, 1000)) {
            val content = ByteArray(size) { (it * 31).toByte() }
            val framed = Padding.frame(content)
            assertEquals(Padding.target(4 + size), framed.size)
            assertTrue(content.contentEquals(Padding.unframe(framed)!!))
        }
        assertEquals(64, Padding.target(4 + 60))
        assertEquals(128, Padding.target(4 + 61))
    }

    @Test
    fun paddingRejectsCorruptLength() {
        val framed = Padding.frame("secret".toByteArray())
        val tampered = framed.copyOf()
        tampered[3] = 0x7F
        assertNull(Padding.unframe(tampered))
        assertNull(Padding.unframe(framed.copyOfRange(0, 3)))
    }

    // --- deflate ---

    @Test
    fun deflateBodyRoundTripsForCompressibleAndShortText() {
        val longText = "Kryptos encrypts before the channel sees it. ".repeat(200)
        val shortText = "hi"
        for (text in listOf(longText, shortText, "")) {
            val body = Deflate.body(text)
            val restored = Deflate.text(body.bytes, body.deflated)
            assertEquals(text, restored)
        }
    }

    @Test
    fun deflateTextFallsBackToTheOtherReading() {
        // Deflate.text deliberately tries both readings; a wrong but harmless
        // flag must not lose the plaintext.
        val plain = "plain words that were not compressed".toByteArray(Charsets.UTF_8)
        assertEquals("plain words that were not compressed", Deflate.text(plain, true))
    }

    @Test
    fun deflateRejectsNonUtf8Payload() {
        assertNull(Deflate.text(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00), false))
    }

    // --- shared-password mode (Argon2id + AES-256-GCM) ---

    @Test
    fun passwordCipherRoundTrip() {
        val plaintext = "shared password mode / 共享密码".repeat(20).toByteArray(Charsets.UTF_8)
        for (pad in listOf(false, true)) {
            val sealed = PasswordCipher.encrypt(plaintext, "correct horse battery staple", pad)
            assertTrue(plaintext.contentEquals(PasswordCipher.decrypt(sealed, "correct horse battery staple")))
        }
    }

    @Test
    fun passwordCipherRejectsWrongPasswordAndTampering() {
        val sealed = PasswordCipher.encrypt("sensitive".toByteArray(), "right password")
        assertFailsWith<CipherException> { PasswordCipher.decrypt(sealed, "wrong password") }
        val tampered = sealed.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()
        assertFailsWith<CipherException> { PasswordCipher.decrypt(tampered, "right password") }
    }

    @Test
    fun passwordCipherFlagByteIsInsideTheGcmTag() {
        // sealBody puts the deflate/pad flags in the AEAD plaintext, not in
        // the clear: changing the first ciphertext byte must fail the tag.
        val key = ByteArray(32) { 5 }
        val nonce = ByteArray(12) { 7 }
        val sealed = PasswordCipher.sealBody("x".repeat(500).toByteArray(), key, nonce, 1, true)
        val tampered = sealed.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFailsWith<CipherException> { PasswordCipher.openBody(tampered, key, nonce, 1) }
        val versionTampered = sealed.copyOf()
        assertFailsWith<CipherException> { PasswordCipher.openBody(versionTampered, key, nonce, 2) }
    }

    @Test
    fun argon2idMatchesTheCrossLanguageVector() {
        val salt = ByteArray(16) { (it * 17 + 1).toByte() }
        val derived = Argon2id.derive("cross-language-vector", salt, 44)
        assertEquals(44, derived.size)
        assertEquals(CROSS_LANGUAGE_ARGON2_HEX, hex(derived))
    }

    // --- steganography ---

    @Test
    fun textStegoRoundTrip() {
        val payload = ByteArray(64) { (it * 29).toByte() }
        val cover = TextStego.encode(payload, StegoLanguage.ENGLISH)
        assertNotNull(cover)
        assertTrue(payload.contentEquals(TextStego.decode(cover)!!))
    }

    @Test
    fun letterStegoRoundTrip() {
        val payload = ByteArray(48) { (it * 11).toByte() }
        val cover = LetterStego.encode(payload, StegoLanguage.ENGLISH)
        assertNotNull(cover)
        assertTrue(payload.contentEquals(LetterStego.decode(cover)!!))
    }

    @Test
    fun smartTextStegoRoundTrip() {
        val payload = ByteArray(48) { (it * 13).toByte() }
        val cover = SmartTextStego.encode(payload, StegoLanguage.ENGLISH)
        assertNotNull(cover)
        assertTrue(payload.contentEquals(SmartTextStego.decode(cover)!!))
    }

    @Test
    fun stegoWordlistsArePackagedWithTheCoreModule() {
        // Regression: splitting :core out of :app must not lose wordlists.
        for (language in StegoLanguage.entries) {
            assertTrue(language.words.size >= 4096, "${language.name} wordlist missing")
        }
    }

    companion object {
        // "cross-language-vector", salt = (i*17+1), 44 bytes, Argon2id
        // 64 MiB / t=3 / p=1. Asserted verbatim in the Swift test target.
        const val CROSS_LANGUAGE_ARGON2_HEX: String =
            "474ec627d0e164299eaa2828f74bf405866735fa8fb586e83abdeb48088851b17364075eac5ad2c55df150ef"
    }
}
