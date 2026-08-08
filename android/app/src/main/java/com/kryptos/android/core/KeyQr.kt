package com.kryptos.android.core

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

    fun matrix(payload: ByteArray): BitMatrix = QRCodeWriter().encode(
        String(payload, Charsets.ISO_8859_1),
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.CHARACTER_SET to CHARSET,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to QUIET_ZONE,
        ),
    )
}
