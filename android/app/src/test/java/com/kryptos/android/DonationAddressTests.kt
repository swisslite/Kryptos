package com.kryptos.android

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.kryptos.android.core.KeyQr
import com.kryptos.android.ui.Donations
import org.bouncycastle.crypto.digests.KeccakDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Base64

class DonationAddressTests {

    private fun address(id: String): String = Donations.coins.first { it.id == id }.address

    @Test
    fun moneroAddressIsAValidMainnetSubaddress() {
        val addr = address("xmr")
        assertEquals(95, addr.length)
        val raw = base58Decode(addr)
        assertEquals(69, raw.size)
        assertEquals(42, raw[0].toInt() and 0xFF)
        val body = raw.copyOfRange(0, raw.size - 4)
        val checksum = raw.copyOfRange(raw.size - 4, raw.size)
        assertArrayEquals(keccak256(body).copyOfRange(0, 4), checksum)
    }

    @Test
    fun tonAddressIsAValidMainnetBasechainAddress() {
        val addr = address("ton")
        assertEquals(48, addr.length)
        val raw = Base64.getUrlDecoder().decode(addr)
        assertEquals(36, raw.size)
        assertEquals(0x51, raw[0].toInt() and 0xFF)
        assertEquals(0, raw[1].toInt() and 0xFF)
        val want = ((raw[34].toInt() and 0xFF) shl 8) or (raw[35].toInt() and 0xFF)
        assertEquals(want, crc16Xmodem(raw.copyOfRange(0, 34)))
    }

    @Test
    fun bitcoinAddressIsAValidMainnetSegwitV0Address() {
        val addr = address("btc")
        assertEquals(addr, addr.lowercase())
        val split = addr.lastIndexOf('1')
        val hrp = addr.substring(0, split)
        assertEquals("bc", hrp)
        val data = addr.substring(split + 1).map {
            val v = BECH32_CHARSET.indexOf(it)
            assertTrue("bad bech32 character", v >= 0)
            v
        }
        assertEquals(0, data[0])
        assertEquals(1, bech32Polymod(hrpExpand(hrp) + data))
        assertEquals(20, convertBits(data.subList(1, data.size - 6)).size)
    }

    @Test
    fun everyQrCodeDecodesBackToTheExactAddress() {
        for (coin in Donations.coins) {
            val matrix = KeyQr.matrix(coin.address.toByteArray(Charsets.ISO_8859_1), ErrorCorrectionLevel.M)
            val size = matrix.width * 4
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val v = if (matrix[x / 4, y / 4]) 0 else 255
                    pixels[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
            val hints = mapOf(DecodeHintType.CHARACTER_SET to KeyQr.CHARSET)
            val decoded = MultiFormatReader().decode(bitmap, hints).text
            assertEquals(coin.address, decoded)
        }
    }

    private fun keccak256(data: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    private fun base58Decode(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val chunk = text.substring(i, minOf(i + 11, text.length))
            val size = BLOCK_SIZES[chunk.length] ?: error("bad base58 block length ${chunk.length}")
            var value = BigInteger.ZERO
            for (c in chunk) {
                val digit = BASE58.indexOf(c)
                assertTrue("bad base58 character", digit >= 0)
                value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
            }
            assertTrue("base58 block overflow", value.bitLength() <= size * 8)
            val block = ByteArray(size)
            val raw = value.toByteArray()
            val src = if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size) else raw
            src.copyInto(block, size - src.size)
            out.write(block)
            i += chunk.length
        }
        return out.toByteArray()
    }

    private fun crc16Xmodem(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun bech32Polymod(values: List<Int>): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top shr i) and 1 != 0) chk = chk xor gen[i]
        }
        return chk
    }

    private fun convertBits(data: List<Int>): ByteArray {
        var acc = 0
        var bits = 0
        val out = ByteArrayOutputStream()
        for (value in data) {
            acc = (acc shl 5) or value
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        assertTrue("bad bech32 padding", bits < 5 && ((acc shl (8 - bits)) and 0xFF) == 0)
        return out.toByteArray()
    }

    private companion object {
        const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val BLOCK_SIZES = mapOf(0 to 0, 2 to 1, 3 to 2, 5 to 3, 6 to 4, 7 to 5, 9 to 6, 10 to 7, 11 to 8)
    }
}
