package com.kryptos.android.core

import java.security.SecureRandom

class CipherException(val kind: Kind) : Exception(kind.name) {
    enum class Kind {
        MALFORMED,
        NOT_A_KRYPTOS_MESSAGE,
        DECRYPTION_FAILED,
        STEGO_CAPACITY_EXCEEDED,
        INVALID_INPUT,
        UNSUPPORTED_FORMAT,
    }
}

private val rng = SecureRandom()

fun randomBytes(count: Int): ByteArray = ByteArray(count).also { rng.nextBytes(it) }

internal object StegoTokenizer {
    fun split(text: String): List<String> {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).lowercase()
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            if (Character.isLetter(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                tokens.add(current.toString())
                current.setLength(0)
            }
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }
}

object CachePurge {
    private val hooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun register(hook: () -> Unit) { hooks += hook }
    fun purgeAll() { hooks.forEach { it() } }
}

internal class WipingBuffer(initial: Int = 8192) : java.io.OutputStream() {
    private var buf = ByteArray(initial.coerceAtLeast(64))
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var capacity = buf.size
        while (capacity < size + extra) capacity *= 2
        val grown = buf.copyOf(capacity)
        buf.fill(0)
        buf = grown
    }

    override fun write(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensure(len)
        b.copyInto(buf, size, off, off + len)
        size += len
    }

    fun drain(): ByteArray {
        val out = buf.copyOf(size)
        buf.fill(0)
        size = 0
        return out
    }
}

class BinaryWriter {
    private val out = java.io.ByteArrayOutputStream()
    val data: ByteArray get() = out.toByteArray()

    fun writeByte(b: Int) = out.write(b and 0xFF)

    fun writeUInt32(v: Long) {
        out.write(((v shr 24) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }

    fun writeVar(d: ByteArray) {
        writeUInt32(d.size.toLong())
        out.write(d)
    }
}

class BinaryReader(private val bytes: ByteArray) {
    private var index = 0

    fun readByte(): Int {
        if (index >= bytes.size) throw CipherException(CipherException.Kind.MALFORMED)
        return bytes[index++].toInt() and 0xFF
    }

    fun readUInt32(): Long {
        val b = readRaw(4)
        return ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
    }

    fun readRaw(n: Int): ByteArray {
        if (n < 0 || n > bytes.size - index) throw CipherException(CipherException.Kind.MALFORMED)
        val r = bytes.copyOfRange(index, index + n)
        index += n
        return r
    }

    fun readVar(): ByteArray = readRaw(readUInt32().toInt())
}
