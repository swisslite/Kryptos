package com.kryptos.android.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

object ImageBridge {
    data class Pixels(val rgba: ByteArray, val width: Int, val height: Int)

    private const val MAX_PIXELS = 100_000_000L

    const val COVER_TARGET_PIXELS = 20_000_000L

    fun sampleSizeFor(width: Int, height: Int, target: Long): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width.toLong() / sample * (height.toLong() / sample) > target) sample *= 2
        return sample
    }

    fun rgba(stream: InputStream): Pixels? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeStream(stream, null, options) ?: return null
        return try {
            rgba(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun rgba(bitmap: Bitmap): Pixels {
        val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
        if (bitmap.width <= 0 || bitmap.height <= 0 || pixelCount > MAX_PIXELS) {
            throw CipherException(CipherException.Kind.STEGO_CAPACITY_EXCEEDED)
        }
        val src = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw CipherException(CipherException.Kind.INVALID_INPUT)
        } else bitmap
        return try {
            val buffer = ByteBuffer.allocate((pixelCount * 4).toInt())
            src.copyPixelsToBuffer(buffer)
            Pixels(buffer.array(), src.width, src.height)
        } finally {
            if (src !== bitmap) src.recycle()
        }
    }

    fun pngData(rgba: ByteArray, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val opaque = rgba.copyOf()
            for (i in 3 until opaque.size step 4) opaque[i] = 0xFF.toByte()
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(opaque))
            val out = ByteArrayOutputStream(rgba.size / 3 + 1024)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
