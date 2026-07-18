package com.kryptos.android.core

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

object Deflate {
    const val MAX_OUTPUT = 8 * 1024 * 1024

    fun compress(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        val result = out.toByteArray()
        return if (result.size < data.size) result else null
    }

    fun decompress(data: ByteArray, limit: Int = MAX_OUTPUT): ByteArray? {
        if (data.isEmpty()) return null
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2)
        val buf = ByteArray(4096)
        return try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.finished()) break
                    return null
                }
                out.write(buf, 0, n)
                if (out.size() > limit) return null
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}
