package com.kryptos.android.core


object LetterStego {
    private val MAGIC = byteArrayOf(0xC5.toByte(), 0x3A)
    private const val MIN_FRAME_BYTES = 5
    private const val PREFILTER_CHARS = 24
    private const val SCAN_WINDOW = 256

    private class Alphabet(source: String, val blockBytes: Int) {
        val letters: CharArray = source.toCharArray()
        val base: Int = letters.size
        val index: Map<Char, Int> = letters.withIndex().associate { (i, c) -> c to i }
        val charsForBytes = IntArray(blockBytes + 1)
        val bytesForChars = HashMap<Int, Int>()
        val limits = LongArray(blockBytes + 1)
        val blockChars: Int
        val minChars: Int

        private val first: Char = letters.min()
        private val member = BooleanArray(letters.max() - first + 1).also { flags ->
            for (c in letters) flags[c - first] = true
        }

        fun holds(c: Char): Boolean {
            val i = c - first
            return i >= 0 && i < member.size && member[i]
        }

        init {
            check(base >= 2) { "letter alphabet is too small" }
            check(index.size == base) { "letter alphabet has duplicates" }
            for (r in 1..blockBytes) {
                var limit = 1L
                repeat(r) { limit *= 256L }
                limits[r] = limit
                var chars = 0
                var capacity = 1L
                while (capacity < limit) {
                    capacity *= base.toLong()
                    chars++
                }
                charsForBytes[r] = chars
                if (r < blockBytes) bytesForChars[chars] = r
            }
            blockChars = charsForBytes[blockBytes]
            check(!bytesForChars.containsKey(blockChars)) { "tail length collides with a full block" }
            val rest = MIN_FRAME_BYTES % blockBytes
            minChars = (MIN_FRAME_BYTES / blockBytes) * blockChars +
                if (rest == 0) 0 else charsForBytes[rest]
        }
    }

    private val russian by lazy { Alphabet("абвгдежзийклмнопрстуфхцчшщъыьэюя", 5) }
    private val english by lazy { Alphabet("abcdefghijklmnopqrstuvwxyz", 7) }
    private val chinese by lazy { Alphabet("一不与丢个中为久么之乔也买了事于些什仍从令以们份但住使信俩借假停像先克全关其再写冷刚别到前办加却又另只叫号吃名后向吧听吹呀呆呢和哇哈哎哦哪哭唱啊啥啦喂喝嗨嘛嘿回因在坏块多够太她好如妳字它完家对将少就已带年弧当往很得德心忘忙快怎怕怪总恨恩您想懂成我或戴所才扔把抓抱拍拜拿挂指按挺换掉提搞搬救敢斯新於无时是晚更曾替最月有未来查梦欠次正此每比水求没滚点热爸特猜猫玩球用由画留疼病盯盾看真着睡瞧碰祝神离秒穿站笑第等算米糟约给美老而耶能脚脸腿臭船花若著街被要见让讲读谁谈谢赢跑跟躲躺车达还这远连送选都酒里金错长问间陪饿高鱼", 1) }

    private val tables by lazy { listOf(russian, english, chinese) }

    private fun table(language: StegoLanguage): Alphabet =
        when (language) {
            StegoLanguage.RUSSIAN -> russian
            StegoLanguage.CHINESE -> chinese
            StegoLanguage.ENGLISH, StegoLanguage.GERMAN -> english
        }

    internal fun alphabetCharacters(language: StegoLanguage): CharArray = table(language).letters

    fun encode(data: ByteArray, language: StegoLanguage = StegoLanguage.forSystem()): String? =
        StegoSafety.firstCleanCover(data.size) { seed -> encode(data, language, seed) }

    fun encode(data: ByteArray, language: StegoLanguage, seed: Int): String {
        require(data.isNotEmpty()) { "letter stego needs a payload" }
        val inner = ByteArray(MAGIC.size + data.size + 1)
        MAGIC.copyInto(inner, 0)
        data.copyInto(inner, MAGIC.size)
        inner[inner.size - 1] = crc8(inner, 0, inner.size - 1)

        val framed = ByteArray(1 + inner.size)
        framed[0] = (seed and 0xFF).toByte()
        var x = seed and 0xFF
        for (i in inner.indices) {
            x = (x * 197 + 91) and 0xFF
            framed[i + 1] = (inner[i].toInt() xor x).toByte()
        }
        return pack(framed, table(language))
    }

    fun decode(text: String): ByteArray? {
        for (token in StegoTokenizer.runs(text)) {
            for (a in tables) {
                if (token.length < a.minChars) continue
                if (!a.index.containsKey(token[0]) || !structurallyValid(token.length, a)) continue
                val framed = unpack(token, a) ?: continue
                val payload = unframe(framed)
                if (payload != null) return payload
            }
        }
        return null
    }

    private fun structurallyValid(count: Int, a: Alphabet): Boolean {
        val tail = count % a.blockChars
        return tail == 0 || a.bytesForChars.containsKey(tail)
    }

    fun looksLikeStego(text: String): Boolean = decode(text) != null

    fun mightBeStego(text: String): Boolean {
        val limit = minOf(text.length, SCAN_WINDOW)
        val alphabets = tables
        val runs = IntArray(alphabets.size)
        for (i in 0 until limit) {
            val c = text[i].lowercaseChar()
            for (k in alphabets.indices) {
                if (alphabets[k].holds(c)) {
                    if (++runs[k] >= PREFILTER_CHARS) return true
                } else {
                    runs[k] = 0
                }
            }
        }
        return false
    }

    private fun pack(bytes: ByteArray, a: Alphabet): String {
        val out = StringBuilder(bytes.size * a.blockChars / a.blockBytes + a.blockChars)
        var i = 0
        while (i < bytes.size) {
            val r = minOf(a.blockBytes, bytes.size - i)
            val c = a.charsForBytes[r]
            var value = 0L
            for (k in 0 until r) value = (value shl 8) or (bytes[i + k].toLong() and 0xFF)
            val digits = IntArray(c)
            var k = c - 1
            while (k >= 0) {
                digits[k] = (value % a.base).toInt()
                value /= a.base
                k--
            }
            for (d in digits) out.append(a.letters[d])
            i += r
        }
        return out.toString()
    }

    private fun unpack(token: String, a: Alphabet): ByteArray? {
        val out = ByteArray(token.length * a.blockBytes / a.blockChars + a.blockBytes)
        var written = 0
        var i = 0
        while (i < token.length) {
            val remaining = token.length - i
            val c: Int
            val r: Int
            if (remaining >= a.blockChars) {
                c = a.blockChars
                r = a.blockBytes
            } else {
                r = a.bytesForChars[remaining] ?: return null
                c = remaining
            }
            var value = 0L
            for (k in 0 until c) {
                val digit = a.index[token[i + k]] ?: return null
                value = value * a.base + digit
            }
            if (value >= a.limits[r]) return null
            var k = r - 1
            while (k >= 0) {
                out[written++] = ((value shr (8 * k)) and 0xFF).toByte()
                k--
            }
            i += c
        }
        return if (written == out.size) out else out.copyOf(written)
    }

    private fun unframe(framed: ByteArray): ByteArray? {
        if (framed.size < MAGIC.size + 3) return null
        var x = framed[0].toInt() and 0xFF
        val inner = ByteArray(framed.size - 1)
        for (i in 1 until framed.size) {
            x = (x * 197 + 91) and 0xFF
            inner[i - 1] = (framed[i].toInt() xor x).toByte()
        }
        for (i in MAGIC.indices) if (inner[i] != MAGIC[i]) return null
        if (inner[inner.size - 1] != crc8(inner, 0, inner.size - 1)) return null
        return inner.copyOfRange(MAGIC.size, inner.size - 1)
    }

    private fun crc8(data: ByteArray, from: Int, to: Int): Byte {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc.toByte()
    }
}
