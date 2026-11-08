package com.kryptos.android.core

import java.util.Base64

object KeyText {
    const val PREFIX = "KRYPTOS-KEY:"
    const val MAX_BLOB_CHARS = 16 * 1024

    private fun isBase64Char(c: Char): Boolean =
        c.code < 128 && (c.isLetterOrDigit() || c == '+' || c == '/' || c == '=')

    fun blobs(raw: String): List<ByteArray> {
        val trimmed = raw.trim()
        val idx = trimmed.indexOf(PREFIX)
        if (idx < 0) return emptyList()
        val from = idx + PREFIX.length
        val rest = trimmed.substring(from, minOf(trimmed.length, from + MAX_BLOB_CHARS))
        val direct = rest.takeWhile { !it.isWhitespace() }
        val joined = rest.takeWhile { it.isWhitespace() || isBase64Char(it) }.filterNot { it.isWhitespace() }
        val out = ArrayList<ByteArray>(2)
        decode(direct)?.let { out.add(it) }
        if (joined.length > direct.length) decode(joined)?.let { out.add(it) }
        return out
    }

    private fun decode(s: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(s) }.getOrNull()
}
