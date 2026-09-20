package com.kryptos.android.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object WireFormat {
    const val SALT_LENGTH = 8
    private const val MARKER = 0x03
    private val INFO = "kryptos/wire/v3".toByteArray(Charsets.UTF_8)
    private val PAIR_INFO = "kryptos/wire/pair/v1".toByteArray(Charsets.UTF_8)
    private const val MIN_TOKEN_BYTES = 24
    private const val MIN_TOKEN_CHARS = 32
    private const val MAX_TOKEN_CHARS = 2_000_000
    private const val KNOWN_HEADER_BITS = 0x0F or 0x10 or 0x20

    sealed class Opened {
        class Message(val type: Int, val deflate: Boolean, val body: ByteArray) : Opened()
        object Unsupported : Opened()
        object Absent : Opened()
    }

    fun pairSecret(agreement: ByteArray, a: String, b: String): ByteArray =
        hkdfSha256(agreement, (if (a <= b) a + b else b + a).toByteArray(Charsets.UTF_8), PAIR_INFO, 32)

    fun sealedSize(ciphertext: Int, padded: Boolean): Int =
        SALT_LENGTH + 2 + (if (padded) Padding.target(4 + ciphertext) else ciphertext)

    fun fitsStego(ciphertext: Int, padded: Boolean): Boolean =
        sealedSize(ciphertext, padded) <= TextStego.MAX_PAYLOAD_BYTES

    fun seal(body: ByteArray, type: Int, deflate: Boolean, padded: Boolean, pairKey: ByteArray): ByteArray =
        seal(body, type, deflate, padded, pairKey, randomBytes(SALT_LENGTH))

    fun seal(body: ByteArray, type: Int, deflate: Boolean, padded: Boolean, pairKey: ByteArray, salt: ByteArray): ByteArray {
        val inner = if (padded) Padding.frame(body) else body
        val plain = ByteArray(2 + inner.size)
        plain[0] = MARKER.toByte()
        plain[1] = ((type and 0x0F) or (if (deflate) 0x10 else 0x00) or (if (padded) 0x20 else 0x00)).toByte()
        inner.copyInto(plain, 2)
        val (key, iv) = derive(pairKey, salt)
        return salt + ctr(key, iv, plain)
    }

    fun open(raw: ByteArray, pairKey: ByteArray): Opened {
        if (raw.size <= SALT_LENGTH + 1) return Opened.Absent
        val salt = raw.copyOfRange(0, SALT_LENGTH)
        val masked = raw.copyOfRange(SALT_LENGTH, raw.size)
        val (key, iv) = derive(pairKey, salt)
        val plain = ctr(key, iv, masked)
        if (plain.size < 2) return Opened.Absent
        if ((plain[0].toInt() and 0xFF) != MARKER) return Opened.Absent
        val header = plain[1].toInt() and 0xFF
        if (header and KNOWN_HEADER_BITS.inv() != 0) return Opened.Unsupported
        val type = header and 0x0F
        if (type != 2 && type != 3) return Opened.Unsupported
        val inner = plain.copyOfRange(2, plain.size)
        val body = if (header and 0x20 != 0) (Padding.unframe(inner) ?: return Opened.Absent) else inner
        return Opened.Message(type, header and 0x10 != 0, body)
    }

    fun wrap(body: ByteArray, type: Int, deflate: Boolean, padded: Boolean, pairKey: ByteArray): String =
        wrap(body, type, deflate, padded, pairKey, randomBytes(SALT_LENGTH))

    fun wrap(body: ByteArray, type: Int, deflate: Boolean, padded: Boolean, pairKey: ByteArray, salt: ByteArray): String =
        base64UrlEncode(seal(body, type, deflate, padded, pairKey, salt))

    fun unwrap(text: String, pairKey: ByteArray): Opened {
        val raw = rawBytes(text) ?: return Opened.Absent
        return open(raw, pairKey)
    }

    fun token(raw: ByteArray): String = base64UrlEncode(raw)

    fun tokenBytes(text: String): ByteArray? = rawBytes(text)

    private fun rawBytes(text: String): ByteArray? {
        val run = longestRun(text) ?: return null
        return base64UrlDecode(run)
    }

    fun isToken(text: String): Boolean {
        val t = text.trim()
        if (t.length < MIN_TOKEN_CHARS || t.length > MAX_TOKEN_CHARS || !t.all { isBase64UrlChar(it) }) return false
        val d = base64UrlDecode(t) ?: return false
        return d.size >= MIN_TOKEN_BYTES
    }

    fun extractToken(text: String): String? {
        val bounds = longestRunBounds(text) ?: return null
        val len = bounds.last - bounds.first + 1
        if (len < MIN_TOKEN_CHARS || len > MAX_TOKEN_CHARS) return null
        val run = text.substring(bounds.first, bounds.last + 1)
        val d = base64UrlDecode(run) ?: return null
        return if (d.size >= MIN_TOKEN_BYTES) run else null
    }

    private fun longestRun(text: String): String? {
        val bounds = longestRunBounds(text) ?: return null
        return text.substring(bounds.first, bounds.last + 1)
    }

    private fun longestRunBounds(text: String): IntRange? {
        var bestStart = -1
        var bestLen = 0
        var start = -1
        for (i in text.indices) {
            if (isBase64UrlChar(text[i])) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start > bestLen) { bestLen = i - start; bestStart = start }
                start = -1
            }
        }
        if (start >= 0 && text.length - start > bestLen) { bestLen = text.length - start; bestStart = start }
        return if (bestLen > 0) bestStart until bestStart + bestLen else null
    }

    private fun derive(pairKey: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = hkdfSha256(pairKey, salt, INFO, 48)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 48)
    }

    private fun ctr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(input)
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun isBase64UrlChar(c: Char): Boolean =
        c.code < 128 && (c.isLetterOrDigit() || c == '-' || c == '_')

    private fun base64UrlEncode(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun base64UrlDecode(s: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()
}
