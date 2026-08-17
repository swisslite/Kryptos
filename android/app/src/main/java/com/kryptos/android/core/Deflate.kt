package com.kryptos.android.core

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

object Deflate {
    const val MAX_OUTPUT = 8 * 1024 * 1024

    fun compress(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        val result = try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
        return if (result.size < data.size) result else null
    }

    fun decompress(data: ByteArray, limit: Int = MAX_OUTPUT): ByteArray? {
        if (data.isEmpty()) return null
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream(
            (data.size.toLong() * 2).coerceIn(64L, limit.toLong().coerceAtLeast(64L)).toInt()
        )
        val buf = ByteArray(4096)
        return try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.finished()) break
                    return null
                }
                if (out.size().toLong() + n > limit.toLong()) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}
