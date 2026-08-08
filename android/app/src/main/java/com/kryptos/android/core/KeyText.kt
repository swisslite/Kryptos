package com.kryptos.android.core

import java.util.Base64

object KeyText {
    const val PREFIX = "KRYPTOS-KEY:"

    private fun isBase64Char(c: Char): Boolean =
        c.code < 128 && (c.isLetterOrDigit() || c == '+' || c == '/' || c == '=')

    fun blobs(raw: String): List<ByteArray> {
        val trimmed = raw.trim()
        val idx = trimmed.indexOf(PREFIX)
        if (idx < 0) return emptyList()
        val rest = trimmed.substring(idx + PREFIX.length)
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
