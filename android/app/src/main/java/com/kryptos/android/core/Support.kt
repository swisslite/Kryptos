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

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

fun hexOf(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xFF
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
    }
    return String(out)
}

fun sha256Hex(data: ByteArray): String =
    hexOf(java.security.MessageDigest.getInstance("SHA-256").digest(data))

internal object StegoTokenizer {
    fun isHan(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF

    fun split(text: String): List<String> {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).lowercase()
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            if (isHan(cp)) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.setLength(0)
                }
                tokens.add(String(Character.toChars(cp)))
            } else if (Character.isLetter(cp)) {
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

    fun runs(text: String): List<String> {
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
    private val decryptedHooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun register(hook: () -> Unit) { hooks += hook }
    fun registerDecrypted(hook: () -> Unit) { decryptedHooks += hook }
    fun purgeAll() { hooks.forEach { it() } }
    fun purgeDecrypted() { decryptedHooks.forEach { it() } }
}

internal class WipingBuffer(initial: Int = 8192) : java.io.OutputStream() {
    private var buf = ByteArray(initial.coerceAtLeast(64))
    private var size = 0

    private fun ensure(extra: Int) {
        if (extra < 0) throw CipherException(CipherException.Kind.INVALID_INPUT)
        val needed = size.toLong() + extra
        if (needed <= buf.size) return
        if (needed > MAX_CAPACITY) throw CipherException(CipherException.Kind.INVALID_INPUT)
        var capacity = buf.size.toLong()
        while (capacity < needed) capacity = minOf(capacity * 2, MAX_CAPACITY)
        val grown = buf.copyOf(capacity.toInt())
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

    companion object {
        const val MAX_CAPACITY = Int.MAX_VALUE - 8L
    }
}

internal inline fun wipingBytes(write: (java.io.OutputStream) -> Unit): ByteArray {
    val buffer = WipingBuffer()
    return try {
        write(buffer)
        buffer.drain()
    } catch (e: Throwable) {
        buffer.drain().fill(0)
        throw e
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

class TaskQueue(name: String) {
    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    fun execute(block: () -> Unit) {
        if (pool.isShutdown) return
        runCatching { pool.execute { runCatching(block) } }
    }

    fun shutdown() {
        runCatching { pool.shutdown() }
    }

    fun shutdownNow() {
        runCatching { pool.shutdownNow() }
    }
}
