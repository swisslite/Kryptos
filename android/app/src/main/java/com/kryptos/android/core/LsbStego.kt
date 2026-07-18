package com.kryptos.android.core

object LsbStego {
    private val MAGIC = byteArrayOf(0x4B, 0x58, 0x53, 0x31)
    private const val HEADER_BYTES = 8

    fun capacity(pixelCount: Int): Int = maxOf(0, (pixelCount * 3) / 8 - HEADER_BYTES)

    fun embed(payload: ByteArray, rgba: ByteArray): ByteArray {
        if (rgba.size % 4 != 0) throw CipherException(CipherException.Kind.INVALID_INPUT)
        val pixelCount = rgba.size / 4

        val stream = ByteArray(HEADER_BYTES + payload.size)
        MAGIC.copyInto(stream)
        val len = payload.size
        stream[4] = ((len shr 24) and 0xFF).toByte()
        stream[5] = ((len shr 16) and 0xFF).toByte()
        stream[6] = ((len shr 8) and 0xFF).toByte()
        stream[7] = (len and 0xFF).toByte()
        payload.copyInto(stream, HEADER_BYTES)

        val totalBits = stream.size * 8
        if (totalBits > pixelCount * 3) throw CipherException(CipherException.Kind.STEGO_CAPACITY_EXCEEDED)

        val out = rgba.copyOf()
        var bitIndex = 0
        outer@ for (p in 0 until pixelCount) {
            for (channel in 0 until 3) {
                if (bitIndex >= totalBits) break@outer
                val byte = stream[bitIndex / 8].toInt()
                val bit = (byte shr (7 - (bitIndex % 8))) and 1
                val idx = p * 4 + channel
                out[idx] = ((out[idx].toInt() and 0xFE) or bit).toByte()
                bitIndex++
            }
        }
        return out
    }

    fun extract(rgba: ByteArray): ByteArray {
        if (rgba.size % 4 != 0) throw CipherException(CipherException.Kind.INVALID_INPUT)
        val channelCount = (rgba.size / 4) * 3

        fun bit(i: Int): Int {
            val pixel = i / 3
            val channel = i % 3
            return rgba[pixel * 4 + channel].toInt() and 1
        }

        fun readBytes(fromBit: Int, count: Int): ByteArray {
            if (count < 0 || fromBit.toLong() + count.toLong() * 8 > channelCount) {
                throw CipherException(CipherException.Kind.NO_HIDDEN_DATA)
            }
            val result = ByteArray(count)
            for (j in 0 until count * 8) {
                val b = bit(fromBit + j)
                result[j / 8] = (result[j / 8].toInt() or (b shl (7 - (j % 8)))).toByte()
            }
            return result
        }

        val header = readBytes(0, HEADER_BYTES)
        if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw CipherException(CipherException.Kind.NO_HIDDEN_DATA)
        }
        val length = ((header[4].toInt() and 0xFF) shl 24) or ((header[5].toInt() and 0xFF) shl 16) or
            ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
        return readBytes(HEADER_BYTES * 8, length)
    }
}
