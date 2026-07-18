package com.kryptos.android.core

object Padding {
    private const val FLOOR = 64
    private const val CAP = 1 shl 20

    fun target(n: Int): Int {
        if (n <= FLOOR) return FLOOR
        if (n > CAP) return ((n + CAP - 1) / CAP) * CAP
        var p = FLOOR
        while (p < n) p = p shl 1
        return p
    }

    fun frame(content: ByteArray): ByteArray {
        val total = target(4 + content.size)
        val padLen = total - 4 - content.size
        val out = ByteArray(total)
        val n = content.size
        out[0] = ((n ushr 24) and 0xFF).toByte()
        out[1] = ((n ushr 16) and 0xFF).toByte()
        out[2] = ((n ushr 8) and 0xFF).toByte()
        out[3] = (n and 0xFF).toByte()
        content.copyInto(out, 4)
        if (padLen > 0) randomBytes(padLen).copyInto(out, 4 + content.size)
        return out
    }

    fun unframe(framed: ByteArray): ByteArray? {
        if (framed.size < 4) return null
        val n = ((framed[0].toInt() and 0xFF) shl 24) or ((framed[1].toInt() and 0xFF) shl 16) or
            ((framed[2].toInt() and 0xFF) shl 8) or (framed[3].toInt() and 0xFF)
        if (n < 0 || n > framed.size - 4) return null
        return framed.copyOfRange(4, 4 + n)
    }
}
