package com.kryptos.android.core

import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

enum class StegoLanguage {
    ENGLISH, RUSSIAN;

    val words: List<String>
        get() = when (this) {
            ENGLISH -> Wordlists.english
            RUSSIAN -> Wordlists.russian
        }

    internal val indexMap: Map<String, Int>
        get() = when (this) {
            ENGLISH -> Wordlists.englishIndex
            RUSSIAN -> Wordlists.russianIndex
        }

    companion object {
        fun forSystem(): StegoLanguage =
            if (Locale.getDefault().language.lowercase().startsWith("ru")) RUSSIAN else ENGLISH
    }
}

internal object Wordlists {
    val english: List<String> by lazy { load("english") }
    val russian: List<String> by lazy { load("russian") }
    val englishIndex: Map<String, Int> by lazy { index(english) }
    val russianIndex: Map<String, Int> by lazy { index(russian) }

    private fun load(name: String): List<String> {
        val stream = Wordlists::class.java.classLoader!!.getResourceAsStream("wordlists/$name.txt")
            ?: error("wordlist $name missing from resources")
        val words = stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        check(words.size == 4096) { "word list must hold exactly 4096 words, got ${words.size}" }
        return words
    }

    private fun index(words: List<String>): Map<String, Int> =
        words.withIndex().associate { (i, w) -> Normalizer.normalize(w, Normalizer.Form.NFC) to i }
}

object TextStego {
    private const val BITS_PER_WORD = 12
    private const val WORD_MASK = 0xFFF
    private const val MAGIC = 0xC7

    const val MAX_PAYLOAD_BYTES = 0x7FFF

    private val random = SecureRandom()

    fun encode(data: ByteArray, language: StegoLanguage = StegoLanguage.forSystem()): String =
        encode(data, language, random.nextInt(256))

    internal fun encode(data: ByteArray, language: StegoLanguage, seed: Int): String {
        val words = language.words
        require(data.size <= MAX_PAYLOAD_BYTES) { "payload too large for the stego frame" }

        val shortLen = data.size <= 0x7F
        val headerSize = if (shortLen) 2 else 3
        val inner = ByteArray(headerSize + data.size + 1)
        inner[0] = MAGIC.toByte()
        if (shortLen) {
            inner[1] = data.size.toByte()
        } else {
            inner[1] = (0x80 or (data.size shr 8)).toByte()
            inner[2] = (data.size and 0xFF).toByte()
        }
        data.copyInto(inner, headerSize)
        inner[inner.size - 1] = crc8(inner, 0, inner.size - 1).toByte()

        val framed = ByteArray(1 + inner.size)
        framed[0] = seed.toByte()
        var x = seed and 0xFF
        for (i in inner.indices) {
            x = (x * 197 + 91) and 0xFF
            framed[1 + i] = (inner[i].toInt() xor x).toByte()
        }

        val indices = ArrayList<Int>((framed.size * 8 + BITS_PER_WORD - 1) / BITS_PER_WORD)
        var acc = 0
        var bits = 0
        for (byte in framed) {
            acc = (acc shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= BITS_PER_WORD) {
                bits -= BITS_PER_WORD
                indices.add((acc shr bits) and WORD_MASK)
            }
        }
        if (bits > 0) indices.add((acc shl (BITS_PER_WORD - bits)) and WORD_MASK)

        return prettify(indices.map { words[it] }, seed)
    }

    fun decode(text: String): ByteArray? {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        for (language in StegoLanguage.entries) {
            decode(tokens, language)?.let { return it }
        }
        return null
    }

    fun looksLikeStego(text: String): Boolean = decode(text) != null

    fun mightBeStego(text: String, sample: Int = 6): Boolean {
        val tokens = tokenize(text.take(200))
        if (tokens.size < sample) return false
        val head = tokens.take(sample * 2)
        return StegoLanguage.entries.any { lang -> head.count { lang.indexMap.containsKey(it) } >= sample }
    }

    private fun decode(tokens: List<String>, language: StegoLanguage): ByteArray? {
        val index = language.indexMap
        var acc = 0L
        var bits = 0
        val bytes = ArrayList<Byte>(tokens.size * BITS_PER_WORD / 8 + 1)
        for (token in tokens) {
            val value = index[token] ?: continue
            acc = (acc shl BITS_PER_WORD) or value.toLong()
            bits += BITS_PER_WORD
            while (bits >= 8) {
                bits -= 8
                bytes.add(((acc shr bits) and 0xFF).toByte())
            }
        }
        if (bytes.size < 4) return null
        var x = bytes[0].toInt() and 0xFF
        val inner = ByteArray(bytes.size - 1)
        for (i in inner.indices) {
            x = (x * 197 + 91) and 0xFF
            inner[i] = (bytes[1 + i].toInt() xor x).toByte()
        }
        if ((inner[0].toInt() and 0xFF) != MAGIC) return null
        val b1 = inner[1].toInt() and 0xFF
        val n: Int
        val offset: Int
        if (b1 < 0x80) {
            n = b1
            offset = 2
        } else {
            if (inner.size < 3) return null
            n = ((b1 and 0x7F) shl 8) or (inner[2].toInt() and 0xFF)
            offset = 3
        }
        if (inner.size < offset + n + 1) return null
        if ((inner[offset + n].toInt() and 0xFF) != crc8(inner, 0, offset + n)) return null
        return inner.copyOfRange(offset, offset + n)
    }

    private fun crc8(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun prettify(words: List<String>, seed: Int): String {
        if (words.isEmpty()) return ""
        var x = (seed xor 0xA5) and 0xFF
        fun next(): Int {
            x = (x * 197 + 91) and 0xFF
            return x
        }
        val sentences = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val len = 4 + next() % 6
            val chunk = words.subList(i, minOf(i + len, words.size))
            val sb = StringBuilder()
            for ((k, word) in chunk.withIndex()) {
                if (k > 0) sb.append(' ')
                sb.append(word)
                if (k < chunk.size - 1 && next() % 6 == 0) sb.append(',')
            }
            val mark = when (next() % 10) {
                8 -> "?"
                9 -> "!"
                else -> "."
            }
            sentences.add(sb.toString().replaceFirstChar { it.uppercase() } + mark)
            i += len
        }
        return sentences.joinToString(" ")
    }

    private fun tokenize(text: String): List<String> = StegoTokenizer.split(text)
}
