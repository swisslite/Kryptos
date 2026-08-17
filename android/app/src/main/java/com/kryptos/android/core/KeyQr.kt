package com.kryptos.android.core

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object KeyQr {
    const val CHARSET = "ISO-8859-1"
    const val QUIET_ZONE = 4

    fun scaleFor(modules: Int, availablePx: Int): Int =
        if (modules <= 0) 1 else (availablePx / modules).coerceAtLeast(1)

    fun matrix(payload: ByteArray, correction: ErrorCorrectionLevel = ErrorCorrectionLevel.L): BitMatrix =
        QRCodeWriter().encode(
            String(payload, Charsets.ISO_8859_1),
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.CHARACTER_SET to CHARSET,
                EncodeHintType.ERROR_CORRECTION to correction,
                EncodeHintType.MARGIN to QUIET_ZONE,
            ),
        )

    fun bitmap(
        payload: ByteArray,
        availablePx: Int,
        correction: ErrorCorrectionLevel = ErrorCorrectionLevel.L,
    ): Bitmap? = runCatching {
        val matrix = matrix(payload, correction)
        val modules = matrix.width
        val scale = scaleFor(modules, availablePx)
        val size = modules * scale
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val my = y / scale
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix[x / scale, my]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }.getOrNull()
}
