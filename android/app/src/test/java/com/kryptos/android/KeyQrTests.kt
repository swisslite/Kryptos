package com.kryptos.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.kryptos.android.core.KeyQr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Random

class KeyQrTests {

    private fun keyPayload(seed: Long): ByteArray {
        val out = ByteArray(1841)
        Random(seed).nextBytes(out)
        out[0] = 1
        return out
    }

    private fun render(matrix: BitMatrix, pxPerModule: Int, blur: Boolean): BinaryBitmap {
        val size = matrix.width * pxPerModule
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = if (matrix[x / pxPerModule, y / pxPerModule]) 0 else 255
                pixels[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val source = if (blur) boxBlur(pixels, size) else pixels
        return BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, source)))
    }

    private fun boxBlur(pixels: IntArray, size: Int): IntArray {
        val out = IntArray(pixels.size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                var sum = 0
                var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue
                        sum += pixels[ny * size + nx] and 0xFF
                        count++
                    }
                }
                val v = sum / count
                out[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return out
    }

    private fun gray(pixels: IntArray, size: Int): BinaryBitmap =
        BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))

    private fun pack(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun nearestUpscale(matrix: BitMatrix, targetPx: Int): IntArray {
        val out = IntArray(targetPx * targetPx)
        for (y in 0 until targetPx) {
            val my = y * matrix.height / targetPx
            for (x in 0 until targetPx) {
                val mx = x * matrix.width / targetPx
                out[y * targetPx + x] = pack(if (matrix[mx, my]) 0 else 255)
            }
        }
        return out
    }

    private fun decode(bitmap: BinaryBitmap): ByteArray {
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to KeyQr.CHARSET,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        return MultiFormatReader().decode(bitmap, hints).text.toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun keyQrStaysWithinScannableVersion() {
        val modules = KeyQr.matrix(keyPayload(1)).width
        assertTrue("modules=$modules", modules <= 145 + KeyQr.QUIET_ZONE * 2)
    }

    @Test
    fun binaryPayloadIsDenserThanBase64Text() {
        val payload = keyPayload(2)
        val binary = KeyQr.matrix(payload).width
        val text = "KRYPTOS-KEY:" + Base64.getEncoder().encodeToString(payload)
        val legacy = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0).width
        assertTrue("binary=$binary legacy=$legacy", binary < legacy)
    }

    @Test
    fun keyQrRoundTripsThroughIsoLatin1() {
        val payload = keyPayload(3)
        assertArrayEquals(payload, decode(render(KeyQr.matrix(payload), 8, blur = false)))
    }

    @Test
    fun keyQrDecodesAtThreePixelsPerModule() {
        val payload = keyPayload(4)
        assertArrayEquals(payload, decode(render(KeyQr.matrix(payload), 3, blur = false)))
    }

    @Test
    fun keyQrDecodesWhenOpticallySoftened() {
        val payload = keyPayload(5)
        assertArrayEquals(payload, decode(render(KeyQr.matrix(payload), 4, blur = true)))
    }

    @Test
    fun everyIntegerScaleDecodes() {
        val payload = keyPayload(6)
        val matrix = KeyQr.matrix(payload)
        for (scale in 3..8) {
            assertArrayEquals("scale=$scale", payload, decode(render(matrix, scale, blur = false)))
        }
    }

    @Test
    fun scaleForAlwaysFitsAndStaysWholeModules() {
        val modules = KeyQr.matrix(keyPayload(7)).width
        for (available in intArrayOf(320, 459, 500, 600, 640, 800, 1080, 1440)) {
            val scale = KeyQr.scaleFor(modules, available)
            val side = modules * scale
            assertTrue("available=$available side=$side", scale >= 1)
            if (available >= modules) {
                assertTrue("available=$available side=$side", side <= available)
                assertTrue("available=$available side=$side", side + modules > available)
            }
        }
    }

    @Test
    fun nonIntegerScalingIsUnreliableAndMustNotBeUsed() {
        val payload = keyPayload(8)
        val matrix = KeyQr.matrix(payload)
        val broken = intArrayOf(500, 600, 800).count { target ->
            runCatching { decode(gray(nearestUpscale(matrix, target), target)) }
                .getOrNull()?.contentEquals(payload) != true
        }
        assertTrue("нецелочисленное масштабирование неожиданно оказалось надёжным", broken > 0)
    }
}
