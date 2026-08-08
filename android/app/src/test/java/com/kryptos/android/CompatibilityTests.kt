package com.kryptos.android

import com.kryptos.android.core.KeyText
import com.kryptos.android.core.StegoWire
import com.kryptos.android.core.WireFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CompatibilityTests {

    private val ciphertext = ByteArray(80) { it.toByte() }

    @Test
    fun stegoWireRoundTripsEveryKnownFlagCombination() {
        for (type in listOf(2, 3)) {
            for (deflate in listOf(false, true)) {
                for (padded in listOf(false, true)) {
                    val payload = StegoWire.frame(ciphertext, type, deflate, padded)
                    assertFalse(StegoWire.carriesUnknownFlags(payload))
                    val framed = StegoWire.unframe(payload)
                    assertNotNull("unframe failed for $type/$deflate/$padded", framed)
                    assertEquals(type, framed!!.type)
                    assertEquals(deflate, framed.deflate)
                    assertArrayEquals(ciphertext, framed.body)
                }
            }
        }
    }

    @Test
    fun stegoWireRejectsUnknownFlagsInsteadOfMisparsing() {
        val payload = StegoWire.frame(ciphertext, 3, deflate = true, padded = false)
        payload[1] = (payload[1].toInt() or 0x40).toByte()
        assertTrue(StegoWire.carriesUnknownFlags(payload))
        assertNull(StegoWire.unframe(payload))
    }

    @Test
    fun stegoWireStillAcceptsPayloadsFromOlderBuilds() {
        val legacy = byteArrayOf(StegoWire.PREFIX.toByte(), 3) + ciphertext
        assertFalse(StegoWire.carriesUnknownFlags(legacy))
        val framed = StegoWire.unframe(legacy)
        assertNotNull(framed)
        assertEquals(3, framed!!.type)
        assertFalse(framed.deflate)
        assertArrayEquals(ciphertext, framed.body)
    }

    @Test
    fun wireUnwrapRejectsUnknownHeaderBits() {
        val pairKey = "alicebob".toByteArray(Charsets.UTF_8)
        val salt = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        for (padded in listOf(false, true)) {
            val token = WireFormat.wrap(ciphertext, 3, false, padded, pairKey, salt)
            val unwrapped = WireFormat.unwrap(token, pairKey)
            assertNotNull(unwrapped)
            assertArrayEquals(ciphertext, unwrapped!!.body)
        }
        val forged = WireFormat.wrap(ciphertext, 3, false, false, pairKey, salt)
        val raw = Base64.getUrlDecoder().decode(forged)
        raw[WireFormat.SALT_LENGTH] = (raw[WireFormat.SALT_LENGTH].toInt() xor 0x40).toByte()
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertNull(WireFormat.unwrap(token, pairKey))
    }

    private fun keyText(body: String) = KeyText.PREFIX + body

    @Test
    fun keyTextReadsAPlainKey() {
        val blob = byteArrayOf(0x01, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val blobs = KeyText.blobs(keyText(Base64.getEncoder().encodeToString(blob)))
        assertArrayEquals(blob, blobs.first())
    }

    @Test
    fun keyTextRecoversAKeyBrokenAcrossLines() {
        val blob = ByteArray(600) { ((it * 37 + 11) and 0xFF).toByte() }
        val b64 = Base64.getEncoder().encodeToString(blob)
        val wrapped = b64.chunked(76).joinToString("\n")
        assertTrue(wrapped.contains("\n"))
        assertTrue(KeyText.blobs(keyText(wrapped)).any { it.contentEquals(blob) })
    }

    @Test
    fun keyTextKeepsIgnoringTrailingProse() {
        val blob = ByteArray(120) { ((it * 13 + 5) and 0xFF).toByte() }
        val text = keyText(Base64.getEncoder().encodeToString(blob)) + " — это мой ключ"
        assertArrayEquals(blob, KeyText.blobs(text).first())
    }

    @Test
    fun keyTextRejectsTextWithoutAKey() {
        assertTrue(KeyText.blobs("just an ordinary sentence").isEmpty())
        assertTrue(KeyText.blobs(keyText("!!!!")).isEmpty())
    }

    @Test
    fun keyTextMatchesIosBehaviourOnTheSameInputs() {
        val blob = ByteArray(64) { (it * 7).toByte() }
        val b64 = Base64.getEncoder().encodeToString(blob)
        assertArrayEquals(blob, KeyText.blobs(keyText(b64)).first())
        assertArrayEquals(blob, KeyText.blobs("prefix text " + keyText(b64) + "\nend").first())
        assertTrue(
            KeyText.blobs(keyText(b64.chunked(20).joinToString("\r\n")))
                .any { it.contentEquals(blob) }
        )
    }

    @Test
    fun aTruncatedFirstLineNeverHidesTheRealKey() {
        val blob = ByteArray(600) { (it * 3).toByte() }
        val b64 = Base64.getEncoder().encodeToString(blob)
        val candidates = KeyText.blobs(keyText(b64.chunked(20).joinToString("\n")))
        assertTrue(candidates.size >= 2)
        assertFalse(candidates.first().contentEquals(blob))
        assertArrayEquals(blob, candidates.last())
    }
}
