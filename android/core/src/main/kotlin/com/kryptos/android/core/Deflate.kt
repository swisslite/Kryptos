package com.kryptos.android.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.Deflater
import java.util.zip.Inflater

object Deflate {
    const val MAX_OUTPUT = 8 * 1024 * 1024

    private const val INITIAL_CAPACITY_CAP = 64L * 1024

    private val SYNC_FLUSH = byteArrayOf(0, 0, 0, 0xFF.toByte(), 0xFF.toByte())

    class Body(val bytes: ByteArray, val deflated: Boolean)

    fun body(text: String): Body {
        val plain = text.toByteArray(Charsets.UTF_8)
        if (plain.isEmpty()) return Body(plain, false)
        val packed = pack(plain)
        val packedReadsAsText = strictUtf8(packed) != null
        if (packed.size < plain.size && !packedReadsAsText) return Body(packed, true)
        if (decompress(plain)?.let(::strictUtf8) == null) return Body(plain, false)
        if (!packedReadsAsText) return Body(packed, true)
        return Body(SYNC_FLUSH + packed, true)
    }

    fun compress(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val result = pack(data)
        return if (result.size < data.size) result else null
    }

    private fun pack(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
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
    }

    fun decompress(data: ByteArray, limit: Int = MAX_OUTPUT): ByteArray? {
        if (data.isEmpty()) return null
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream(
            (data.size.toLong() * 2)
                .coerceIn(64L, limit.toLong().coerceAtLeast(64L))
                .coerceAtMost(INITIAL_CAPACITY_CAP)
                .toInt()
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

    fun text(data: ByteArray, deflated: Boolean, limit: Int = MAX_OUTPUT): String? {
        val stored = data.takeIf { it.size <= limit }
        val flagged = if (deflated) decompress(data, limit) else stored
        flagged?.let(::strictUtf8)?.let { return it }
        val other = if (deflated) stored else decompress(data, limit)
        return other?.let(::strictUtf8)
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
