package com.kryptos.android.core

import java.security.SecureRandom
import java.text.Normalizer

object SmartTextStego {
    private const val MAGIC = 0xC6
    const val MAX_PAYLOAD_BYTES = 0x7FFF

    private val random = SecureRandom()
    private val commaBefore = setOf("but", "so", "yet", "then", "while", "because", "though", "и", "но", "а", "затем", "потом", "пока", "когда", "поэтому")

    fun encode(data: ByteArray, language: StegoLanguage = StegoLanguage.forSystem()): String =
        encode(data, language, random.nextInt(256))

    internal fun encode(data: ByteArray, language: StegoLanguage, seed: Int): String {
        require(data.size <= MAX_PAYLOAD_BYTES) { "payload too large for the smart stego frame" }
        val g = grammar(language)
        val framed = frameEncode(data, seed)
        val reader = BitReader(framed)
        val bodies = ArrayList<String>()
        while (reader.position < reader.total) {
            val openerIdx = reader.read(g.openerBits)
            val structure = g.structures[g.structOf[openerIdx]]
            val parts = ArrayList<String>(structure.size)
            for (element in structure) {
                when (element) {
                    is Element.Literal -> parts.add(element.word)
                    is Element.Slot -> parts.add(g.slots[element.type][reader.read(g.slotBits[element.type])])
                }
            }
            bodies.add(render(g.openers[openerIdx], g.openerKind[openerIdx], parts))
        }
        return assemble(bodies, seed)
    }

    fun decode(text: String): ByteArray? {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        for (g in grammars) decode(tokens, g)?.let { return it }
        return null
    }

    fun looksLikeStego(text: String): Boolean = decode(text) != null

    fun mightBeStego(text: String, sample: Int = 6): Boolean {
        val tokens = tokenize(text.take(240))
        if (tokens.size < sample) return false
        val head = tokens.take(8)
        return grammars.any { g -> head.any { g.openerIndex.containsKey(it) } }
    }

    private fun decode(rawTokens: List<String>, g: Grammar): ByteArray? {
        val tokens = rawTokens.filter { it in g.vocab }
        if (tokens.isEmpty()) return null
        var pos = 0
        val writer = BitWriter()
        while (pos < tokens.size) {
            val openerIdx = g.openerIndex[tokens[pos]] ?: return null
            pos++
            writer.append(openerIdx, g.openerBits)
            val structure = g.structures[g.structOf[openerIdx]]
            for (element in structure) {
                when (element) {
                    is Element.Literal -> {
                        if (pos >= tokens.size || tokens[pos] != element.word) return null
                        pos++
                    }
                    is Element.Slot -> {
                        if (pos >= tokens.size) return null
                        val idx = g.slotIndex[element.type][tokens[pos]] ?: return null
                        writer.append(idx, g.slotBits[element.type])
                        pos++
                    }
                }
            }
        }
        return frameDecode(writer.bytes())
    }

    private fun render(opener: String, kind: Int, parts: List<String>): String {
        val sb = StringBuilder()
        sb.append(opener.replaceFirstChar { it.uppercase() })
        if (kind == 0) sb.append(",")
        for (part in parts) {
            if (part in commaBefore) sb.append(",")
            sb.append(" ").append(part)
        }
        return sb.toString()
    }

    private fun assemble(bodies: List<String>, seed: Int): String {
        var x = (seed xor 0x3B) and 0xFF
        fun next(): Int {
            x = (x * 197 + 91) and 0xFF
            return x
        }
        val sb = StringBuilder()
        for ((i, body) in bodies.withIndex()) {
            if (i > 0) sb.append(if (next() % 7 == 0) "\n" else " ")
            sb.append(body)
            sb.append(if (next() % 10 == 9) "!" else ".")
        }
        return sb.toString()
    }

    private fun frameEncode(data: ByteArray, seed: Int): ByteArray {
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
        return framed
    }

    private fun frameDecode(bytes: ByteArray): ByteArray? {
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

    private fun tokenize(text: String): List<String> = StegoTokenizer.split(text)

    private val englishGrammar by lazy { Grammar(SmartStegoData.english) }
    private val russianGrammar by lazy { Grammar(SmartStegoData.russian) }
    private val grammars by lazy { listOf(englishGrammar, russianGrammar) }

    private fun grammar(language: StegoLanguage): Grammar =
        if (language == StegoLanguage.RUSSIAN) russianGrammar else englishGrammar

    private sealed class Element {
        class Literal(val word: String) : Element()
        class Slot(val type: Int) : Element()
    }

    private class Grammar(raw: SmartStegoData.Grammar) {
        val openers: List<String> = raw.openers.map { nfc(it) }
        val openerKind: List<Int> = raw.openerKind
        val structOf: List<Int> = raw.structOf
        val slots: List<List<String>> = raw.slots.map { list -> list.map { nfc(it) } }
        val openerBits: Int = bits(openers.size)
        val slotBits: List<Int> = slots.map { bits(it.size) }
        val structures: List<List<Element>> = raw.structures.map { row ->
            row.map { token ->
                if (token.startsWith("#")) Element.Slot(token.substring(1).toInt())
                else Element.Literal(nfc(token))
            }
        }
        val openerIndex: Map<String, Int> = openers.withIndex().associate { (i, w) -> w to i }
        val slotIndex: List<Map<String, Int>> = slots.map { list ->
            list.withIndex().associate { (i, w) -> w to i }
        }
        val vocab: Set<String> = buildSet {
            addAll(openers)
            for (list in slots) addAll(list)
            for (row in structures) for (element in row) {
                if (element is Element.Literal) add(element.word)
            }
        }

        private fun nfc(w: String): String = Normalizer.normalize(w, Normalizer.Form.NFC)

        private fun bits(n: Int): Int {
            require(n > 0 && (n and (n - 1)) == 0) { "grammar list size must be a power of two" }
            return Integer.numberOfTrailingZeros(n)
        }
    }

    private class BitReader(private val bytes: ByteArray) {
        val total: Int = bytes.size * 8
        var position: Int = 0
            private set

        fun read(n: Int): Int {
            var value = 0
            repeat(n) {
                val bit = if (position < total) {
                    (bytes[position ushr 3].toInt() ushr (7 - (position and 7))) and 1
                } else 0
                value = (value shl 1) or bit
                position++
            }
            return value
        }
    }

    private class BitWriter {
        private val out = ArrayList<Byte>()
        private var current = 0
        private var count = 0

        fun append(value: Int, n: Int) {
            var i = n - 1
            while (i >= 0) {
                current = (current shl 1) or ((value ushr i) and 1)
                count++
                if (count == 8) {
                    out.add(current.toByte())
                    current = 0
                    count = 0
                }
                i--
            }
        }

        fun bytes(): ByteArray {
            if (count > 0) {
                val padded = ByteArray(out.size + 1)
                for (i in out.indices) padded[i] = out[i]
                padded[out.size] = ((current shl (8 - count)) and 0xFF).toByte()
                return padded
            }
            return out.toByteArray()
        }
    }
}
