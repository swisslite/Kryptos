package com.kryptos.android.keyboard

import android.content.Context
import com.kryptos.android.store.SecureStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

object SuggestionEngine {

    internal class Dict(byFrequency: List<String>) {
        val rank = HashMap<String, Int>(byFrequency.size * 2)
        val sorted: Array<String>
        val size: Int

        val capitalized = HashSet<String>()

        init {
            byFrequency.forEachIndexed { i, raw ->
                val w = raw.lowercase()
                if (rank.putIfAbsent(w, i) == null && raw.first().isUpperCase()) capitalized.add(w)
            }
            sorted = rank.keys.toTypedArray()
            sorted.sort()
            size = max(1, rank.size)
        }

        fun display(w: String): String =
            if (w in capitalized) w.replaceFirstChar { it.uppercase() } else w

        fun contains(word: String) = rank.containsKey(word)

        private fun lowerBound(prefix: String): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < prefix) lo = mid + 1 else hi = mid
            }
            return lo
        }

        fun isPrefix(prefix: String): Boolean {
            if (prefix.isEmpty()) return false
            val lo = lowerBound(prefix)
            return lo < sorted.size && sorted[lo].startsWith(prefix)
        }

        fun complete(prefix: String, limit: Int): List<String> {
            if (prefix.isEmpty()) return emptyList()
            val best = ArrayList<Pair<String, Int>>(32)
            var i = lowerBound(prefix)
            while (i < sorted.size && sorted[i].startsWith(prefix)) {
                val w = sorted[i]
                if (w.length > prefix.length) best.add(w to (rank[w] ?: Int.MAX_VALUE))
                i++
            }
            best.sortBy { it.second }
            return best.take(limit).map { it.first }
        }
    }

    internal class Lexicon private constructor(
        private val chars: CharArray,
        private val starts: IntArray,
        private val ranks: IntArray,
    ) {
        val size: Int get() = starts.size - 1

        fun len(j: Int) = starts[j + 1] - starts[j]
        fun charAt(j: Int, p: Int) = chars[starts[j] + p]
        fun rank(j: Int) = ranks[j]
        fun word(j: Int) = String(chars, starts[j], len(j))

        fun contains(w: String): Boolean {
            var lo = 0
            var hi = size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = cmpWord(mid, w)
                when {
                    c == 0 -> return true
                    c < 0 -> lo = mid + 1
                    else -> hi = mid - 1
                }
            }
            return false
        }

        private fun cmpWord(j: Int, w: String): Int {
            val from = starts[j]
            val to = starts[j + 1]
            var i = from
            var k = 0
            while (i < to && k < w.length) {
                val d = chars[i] - w[k]
                if (d != 0) return d
                i++; k++
            }
            return (to - from) - w.length
        }

        fun childEnd(from: Int, hi: Int, p: Int, c: Char): Int {
            var lo = from + 1
            var h = hi
            while (lo < h) {
                val mid = (lo + h) ushr 1
                if (charAt(mid, p) <= c) lo = mid + 1 else h = mid
            }
            return lo
        }

        fun findChild(lo: Int, hi: Int, p: Int, c: Char): Long {
            var a = lo + if (len(lo) == p) 1 else 0
            var b = hi
            while (a < b) {
                val mid = (a + b) ushr 1
                if (charAt(mid, p) < c) a = mid + 1 else b = mid
            }
            if (a >= hi || charAt(a, p) != c) return -1L
            return pack(a, childEnd(a, hi, p, c))
        }

        companion object {
            const val NO_RANK = Int.MAX_VALUE

            fun pack(lo: Int, hi: Int) = (lo.toLong() shl 32) or hi.toLong()
            fun unpackLo(v: Long) = (v ushr 32).toInt()
            fun unpackHi(v: Long) = (v and 0xFFFFFFFFL).toInt()

            private const val NEWLINE = '\n'.code.toByte()

            fun build(dictSorted: Array<String>, rankOf: (String) -> Int, vocabBlob: ByteArray): Lexicon {
                var lineCount = 0
                var vocabChars = 0
                var lineLength = 0
                for (b in vocabBlob) {
                    if (b == NEWLINE) {
                        if (lineLength > 0) lineCount++
                        lineLength = 0
                        continue
                    }
                    lineLength++
                    val v = b.toInt() and 0xFF
                    if (v and 0xC0 != 0x80) vocabChars++
                    if (v and 0xF8 == 0xF0) vocabChars++
                }
                if (lineLength > 0) lineCount++

                val maxWords = dictSorted.size + lineCount
                val starts = IntArray(maxWords + 1)
                val ranks = IntArray(maxWords)
                var dictChars = 0
                for (w in dictSorted) dictChars += w.length
                val chars = CharArray(vocabChars + dictChars)
                var n = 0
                var cp = 0
                var di = 0
                var pos = 0
                var lineEnd = 0
                var line: String? = null

                fun appendDict() {
                    val w = dictSorted[di]
                    starts[n] = cp
                    ranks[n] = rankOf(w)
                    w.toCharArray(chars, cp, 0, w.length)
                    cp += w.length
                    n++; di++
                }

                fun appendVocab(w: String) {
                    starts[n] = cp
                    ranks[n] = NO_RANK
                    w.toCharArray(chars, cp, 0, w.length)
                    cp += w.length
                    n++
                }

                fun nextLine(): String? {
                    while (pos < vocabBlob.size) {
                        var end = pos
                        while (end < vocabBlob.size && vocabBlob[end] != NEWLINE) end++
                        if (end > pos) {
                            lineEnd = end
                            return String(vocabBlob, pos, end - pos, Charsets.UTF_8)
                        }
                        pos = end + 1
                    }
                    return null
                }

                line = nextLine()
                while (line != null || di < dictSorted.size) {
                    val current = line
                    if (current == null) { appendDict(); continue }
                    if (di >= dictSorted.size) {
                        appendVocab(current)
                        pos = lineEnd + 1
                        line = nextLine()
                        continue
                    }
                    val c = current.compareTo(dictSorted[di])
                    when {
                        c < 0 -> { appendVocab(current); pos = lineEnd + 1; line = nextLine() }
                        c > 0 -> appendDict()
                        else -> { appendDict(); pos = lineEnd + 1; line = nextLine() }
                    }
                }
                starts[n] = cp
                return Lexicon(chars, starts.copyOf(n + 1), ranks.copyOf(n))
            }
        }
    }

    internal class Bigrams(lines: List<String>) {
        val rank = HashMap<String, Int>(lines.size * 2)
        val next = HashMap<String, MutableList<String>>()
        val vocab = HashSet<String>()
        val size: Int

        init {
            var i = 0
            for (line in lines) {
                val parts = line.trim().split(' ')
                if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) continue
                val key = "${parts[0]} ${parts[1]}"
                if (rank.putIfAbsent(key, i) == null) {
                    next.getOrPut(parts[0]) { ArrayList(4) }.add(parts[1])
                    vocab.add(parts[0]); vocab.add(parts[1])
                    i++
                }
            }
            size = max(1, i)
        }
    }

    private val RU_ROWS = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
    private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val DE_ROWS = listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnmß")
    private val FA_ROWS = listOf("ضصثقفغعهخحجچ", "شسیبلاتنمکگ", "ظطژزرذدپوآ")

    private fun buildConfusable(groups: List<String>): Set<String> {
        val out = HashSet<String>()
        for (group in groups) {
            for (a in group) for (b in group) if (a != b) out.add("$a$b")
        }
        return out
    }

    private fun buildNeighbors(rows: List<String>): Map<Char, Set<Char>> {
        val map = HashMap<Char, MutableSet<Char>>()
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                val set = map.getOrPut(ch) { HashSet() }
                if (c > 0) set.add(row[c - 1])
                if (c < row.length - 1) set.add(row[c + 1])
                for (nr in intArrayOf(r - 1, r + 1)) {
                    if (nr !in rows.indices) continue
                    for (nc in intArrayOf(c - 1, c, c + 1)) {
                        if (nc in rows[nr].indices) set.add(rows[nr][nc])
                    }
                }
            }
        }
        return map
    }

    private val RU_NEIGHBORS = buildNeighbors(RU_ROWS)
    private val EN_NEIGHBORS = buildNeighbors(EN_ROWS)
    private val DE_NEIGHBORS = buildNeighbors(DE_ROWS)
    private val FA_NEIGHBORS = buildNeighbors(FA_ROWS)

    private val RU_CONFUSABLE = setOf("еи", "ие", "ао", "оа", "ея", "яе", "ьъ", "ъь")
    private val FA_CONFUSABLE = buildConfusable(listOf("سصث", "زذضظ", "تط", "هح", "قغ", "اآ", "یئ", "وؤ"))
    private val DE_CONFUSABLE = setOf("äa", "aä", "öo", "oö", "üu", "uü", "ßs", "sß", "ei", "ie")
    private const val EN_VOWELS = "aeiou"
    private const val DE_VOWELS = "aeiouäöü"

    private val FA_ALPHABET = "آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیءئؤ".toCharArray()
    private val RU_ALPHABET = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя".toCharArray()
    private val EN_ALPHABET = "abcdefghijklmnopqrstuvwxyz".toCharArray()
    private val DE_ALPHABET = "abcdefghijklmnopqrstuvwxyzäöüß".toCharArray()

    private const val START_TOKEN = "^"
    private val RU_COMMON = listOf("привет", "да", "нет", "спасибо", "как", "хорошо", "я", "что")
    private val EN_COMMON = listOf("hi", "yes", "no", "thanks", "how", "okay", "i", "the")
    private val DE_COMMON = listOf("hallo", "ja", "nein", "danke", "wie", "gut", "ich", "das")
    private val FA_COMMON = listOf("سلام", "بله", "نه", "ممنون", "چطوری", "خوبم", "من", "که")

val SUPPORTED_LANGUAGES = setOf("en", "ru", "de", "fa")

    private fun alphabetOf(code: String) = when (code) {
        "ru" -> RU_ALPHABET
        "de" -> DE_ALPHABET
        "fa" -> FA_ALPHABET
        else -> EN_ALPHABET
    }

    private fun neighborsOf(code: String) = when (code) {
        "ru" -> RU_NEIGHBORS
        "de" -> DE_NEIGHBORS
        "fa" -> FA_NEIGHBORS
        else -> EN_NEIGHBORS
    }

    private fun commonOf(code: String) = when (code) {
        "ru" -> RU_COMMON
        "de" -> DE_COMMON
        "fa" -> FA_COMMON
        else -> EN_COMMON
    }

    private fun confusableOf(code: String) = when (code) {
        "ru" -> RU_CONFUSABLE
        "de" -> DE_CONFUSABLE
        "fa" -> FA_CONFUSABLE
        else -> emptySet()
    }

    private fun vowelsOf(code: String) = when (code) {
        "ru", "fa" -> ""
        "de" -> DE_VOWELS
        else -> EN_VOWELS
    }

private fun isLatin(code: String) = code != "ru" && code != "fa"

    private fun codeFor(firstChar: Char?, active: String): String = when {
        firstChar == null -> active
        firstChar in 'а'..'я' || firstChar in 'А'..'Я' || firstChar == 'ё' || firstChar == 'Ё' -> "ru"
        firstChar in "äöüßÄÖÜ" -> "de"
        firstChar in '\u0600'..'\u06FF' -> "fa"
        firstChar in 'a'..'z' || firstChar in 'A'..'Z' -> if (isLatin(active)) active else "en"
        else -> active
    }

    private val dicts = java.util.concurrent.ConcurrentHashMap<String, Dict>()
    private val bigramTables = java.util.concurrent.ConcurrentHashMap<String, Bigrams>()
    private val lexicons = java.util.concurrent.ConcurrentHashMap<String, Lexicon>()

    private fun buildLexicon(dict: Dict, vocabBlob: ByteArray): Lexicon =
        Lexicon.build(dict.sorted, { dict.rank[it] ?: Lexicon.NO_RANK }, vocabBlob)

    private val claimed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val personalLoaded = AtomicBoolean(false)

    @Volatile private var loadGeneration = 0

    private fun release(code: String) {
        dicts.remove(code); bigramTables.remove(code); lexicons.remove(code)
    }

    fun warmUp(context: Context, languages: Set<String>, typingAids: Boolean) {
        val wanted = if (typingAids) languages.intersect(SUPPORTED_LANGUAGES) else emptySet()
        val toLoad: List<String>
        synchronized(claimed) {
            val stale = claimed.filter { it !in wanted }
            if (stale.isNotEmpty()) {
                loadGeneration++
                stale.forEach { claimed.remove(it); release(it) }
            }
            toLoad = wanted.filter { claimed.add(it) }
        }
        val needPersonal = personalLoaded.compareAndSet(false, true)
        if (toLoad.isEmpty() && !needPersonal) return
        val app = context.applicationContext
        val generation = loadGeneration
        Thread({
            if (needPersonal) runCatching { loadPersonal() }
            for (code in toLoad.sorted()) {
                runCatching {
                    fun read(name: String) = app.assets.open(name).bufferedReader(Charsets.UTF_8)
                        .readLines().filter { it.isNotBlank() }
                    val dict = Dict(read("dict/$code.txt"))
                    val pairs = Bigrams(read("dict/bigrams-$code.txt"))
                    val lex = buildLexicon(dict, app.assets.open("dict/vocab-$code.txt").readBytes())
                    synchronized(claimed) {
                        if (generation != loadGeneration || code !in claimed) return@synchronized
                        bigramTables[code] = pairs; lexicons[code] = lex; dicts[code] = dict
                    }
                }.onFailure { synchronized(claimed) { claimed.remove(code) } }
            }
        }, "kryptos-dict").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    internal fun loadForTest(
        ruWords: List<String>, enWords: List<String>, ruPairs: List<String>, enPairs: List<String>,
        ruForms: String = "", enForms: String = "",
    ) {
        val ruDict = Dict(ruWords)
        val enDict = Dict(enWords)
        dicts["ru"] = ruDict
        dicts["en"] = enDict
        bigramTables["ru"] = Bigrams(ruPairs)
        bigramTables["en"] = Bigrams(enPairs)
        lexicons["ru"] = buildLexicon(ruDict, ruForms.toByteArray(Charsets.UTF_8))
        lexicons["en"] = buildLexicon(enDict, enForms.toByteArray(Charsets.UTF_8))
        claimed.addAll(listOf("en", "ru"))
        personalLoaded.set(true)
    }

    internal fun loadLanguageForTest(code: String, words: List<String>, pairs: List<String>, forms: String) {
        val dict = Dict(words)
        dicts[code] = dict
        bigramTables[code] = Bigrams(pairs)
        lexicons[code] = buildLexicon(dict, forms.toByteArray(Charsets.UTF_8))
        claimed.add(code)
        personalLoaded.set(true)
    }

    private const val MAX_WORDS = 800
    private const val MAX_BIGRAMS = 1600
    private const val STORE_WORDS = "kb.words"
    private const val STORE_BIGRAMS = "kb.bigrams"
    private const val PREF_WORDS = "kb.learned.words"
    private const val PREF_BIGRAMS = "kb.learned.bigrams"

    private val userWords = HashMap<String, Int>()
    private val userBigrams = HashMap<String, Int>()
    private val sessionWords = HashMap<String, Int>()
    private val sessionBigrams = HashMap<String, Int>()
    private var wordsDirty = false
    private var bigramsDirty = false

    private var storeGeneration = 0

    @Synchronized private fun loadPersonal() {
        runCatching {
            migrateLegacyPlaintext()
            SecureStore.read(STORE_WORDS)?.let { parseCounts(String(it, Charsets.UTF_8), userWords) }
            SecureStore.read(STORE_BIGRAMS)?.let { parseCounts(String(it, Charsets.UTF_8), userBigrams) }
        }
    }

    private fun parseCounts(text: String, into: HashMap<String, Int>) {
        text.lineSequence().forEach { line ->
            val cut = line.lastIndexOf(' ')
            if (cut > 0) line.substring(cut + 1).toIntOrNull()?.let { into[line.substring(0, cut)] = it }
        }
    }

    @Synchronized fun migrateLegacyPlaintext() {
        val prefs = SecureStore.prefs()
        val words = prefs.getString(PREF_WORDS, null)
        val bigrams = prefs.getString(PREF_BIGRAMS, null)
        if (words == null && bigrams == null) return
        if (words != null && SecureStore.read(STORE_WORDS) == null) {
            SecureStore.write(STORE_WORDS, words.toByteArray(Charsets.UTF_8))
        }
        if (bigrams != null && SecureStore.read(STORE_BIGRAMS) == null) {
            SecureStore.write(STORE_BIGRAMS, bigrams.toByteArray(Charsets.UTF_8))
        }
        prefs.edit().remove(PREF_WORDS).remove(PREF_BIGRAMS).commit()
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "kryptos-kbdict").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    fun persistAsync() {
        synchronized(this) { if (!wordsDirty && !bigramsDirty) return }
        runCatching { writer.execute { persist() } }
    }

    fun persist() {
        flush(STORE_WORDS, userWords, { wordsDirty }, { wordsDirty = it })
        flush(STORE_BIGRAMS, userBigrams, { bigramsDirty }, { bigramsDirty = it })
    }

    private fun flush(
        name: String,
        counts: HashMap<String, Int>,
        isDirty: () -> Boolean,
        setDirty: (Boolean) -> Unit,
    ) {
        var generation = 0
        var payload: ByteArray? = null
        synchronized(this) {
            if (!isDirty()) return
            setDirty(false)
            generation = storeGeneration
            payload = serializeCounts(counts)
        }
        val data = payload ?: return
        if (synchronized(this) { generation != storeGeneration }) {
            data.fill(0)
            return
        }
        val ok = runCatching { SecureStore.write(name, data); true }.getOrDefault(false)
        data.fill(0)
        synchronized(this) {
            when {
                generation != storeGeneration -> runCatching { SecureStore.delete(name) }
                !ok -> setDirty(true)
            }
        }
    }

    private fun serializeCounts(map: HashMap<String, Int>): ByteArray =
        map.entries.joinToString("\n") { "${it.key} ${it.value}" }.toByteArray(Charsets.UTF_8)

    @Synchronized fun clearPersonal() {
        storeGeneration++
        userWords.clear()
        userBigrams.clear()
        sessionWords.clear()
        sessionBigrams.clear()
        sessionSkip.clear()
        wordsDirty = false
        bigramsDirty = false
        runCatching {
            SecureStore.delete(STORE_WORDS)
            SecureStore.delete(STORE_BIGRAMS)
            SecureStore.prefs().edit().remove(PREF_WORDS).remove(PREF_BIGRAMS).commit()
        }
    }

    @Synchronized internal fun learnedForTest(word: String): Int = userWords[word] ?: 0

    @Synchronized internal fun learnedBigramForTest(key: String): Int = userBigrams[key] ?: 0

    @Synchronized fun beginTypingSession() {
        sessionWords.clear()
        sessionBigrams.clear()
        sessionSkip.clear()
    }

    @Synchronized fun forgetTypingSession() {
        if (sessionWords.isEmpty() && sessionBigrams.isEmpty()) return
        for ((word, count) in sessionWords) {
            val left = (userWords[word] ?: 0) - count
            if (left > 0) userWords[word] = left else userWords.remove(word)
        }
        for ((key, count) in sessionBigrams) {
            val left = (userBigrams[key] ?: 0) - count
            if (left > 0) userBigrams[key] = left else userBigrams.remove(key)
        }
        sessionWords.clear()
        sessionBigrams.clear()
        sessionSkip.clear()
        wordsDirty = true
        bigramsDirty = true
    }

    private fun decay(map: HashMap<String, Int>) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val half = e.value / 2
            if (half == 0) it.remove() else e.setValue(half)
        }
    }

    @Synchronized fun learn(rawWord: String, rawPrevious: String?) {
        val word = normalize(rawWord) ?: return
        if (word.length < 2) return
        userWords[word] = (userWords[word] ?: 0) + 1
        sessionWords[word] = (sessionWords[word] ?: 0) + 1
        if (userWords.size > MAX_WORDS) decay(userWords)
        val prev = rawPrevious?.let { normalize(it) }
        val key = if (prev != null) "$prev $word" else "$START_TOKEN $word"
        userBigrams[key] = (userBigrams[key] ?: 0) + 1
        sessionBigrams[key] = (sessionBigrams[key] ?: 0) + 1
        if (userBigrams.size > MAX_BIGRAMS) decay(userBigrams)
        wordsDirty = true
        bigramsDirty = true
    }

    @Synchronized fun noteUndoneCorrection(rawWord: String) {
        normalize(rawWord)?.let { sessionSkip.add(it) }
    }

    private val sessionSkip = HashSet<String>()

    @Synchronized fun noteRejectedCorrection(rawWord: String) {
        val word = normalize(rawWord) ?: return
        if (word.length < 2) return
        userWords[word] = (userWords[word] ?: 0) + 2
        sessionWords[word] = (sessionWords[word] ?: 0) + 2
        wordsDirty = true
    }

    private fun normalize(raw: String): String? {
        val w = raw.trim().lowercase()
        if (w.isEmpty() || w.length > 32) return null
        if (!w.all { it.isLetter() || it == '\'' || it == '-' || it == '’' }) return null
        if (!w.first().isLetter()) return null
        return w
    }

    private class Lang(
        val code: String,
        val dict: Dict,
        val bigrams: Bigrams?,
        val lex: Lexicon?,
        val alphabet: CharArray,
        val neighbors: Map<Char, Set<Char>>,
    ) {
        fun knows(w: String) = dict.contains(w) || lex?.contains(w) == true
    }

    private fun langFor(firstChar: Char?, active: String): Lang? {
        val code = codeFor(firstChar, active)
        val dict = dicts[code] ?: return null
        return Lang(code, dict, bigramTables[code], lexicons[code], alphabetOf(code), neighborsOf(code))
    }

    private const val OOV_LM = -12.0
    private const val PERSONAL_PER_USE = 0.8
    private const val PERSONAL_CAP = 8
    private const val USER_BIGRAM_BASE = 3.6
    private const val USER_BIGRAM_PER_USE = 0.25
    private const val EMBEDDED_BIGRAM_TOP = 3.4
    private const val EMBEDDED_BIGRAM_SPAN = 1.6

    private const val SUB_ADJACENT = -1.1
    private const val SUB_YO = -0.4
    private const val SUB_RU_CONFUSE = -0.9
    private const val SUB_EN_VOWEL = -1.3
    private const val SUB_OTHER = -2.6
    private const val TRANSPOSE = -1.2
    private const val DELETE_DUP = -0.9
    private const val INSERT_DUP = -0.6
    private const val DELETE = -1.6
    private const val INSERT = -1.6
    private const val COMPLETE_PER_CHAR = -0.5
    private const val SPLIT = -1.9
    private const val SPLIT_PAIR_BONUS = 2.6
    private const val EXT_PREFERENCE = -0.15
    private const val FUZZY_EXTRA = -0.3

    private fun lm(word: String, dict: Dict): Double? = dict.rank[word]?.let { -ln(it + 1.5) }

    private const val EXTRA_LM = -9.5

    private fun lmFull(word: String, lang: Lang): Double? =
        lm(word, lang.dict) ?: if (lang.lex?.contains(word) == true) EXTRA_LM else null

    @Synchronized private fun base(word: String, lang: Lang, prevNorm: String?): Double {
        var s = lmFull(word, lang) ?: OOV_LM
        userWords[word]?.let { s += min(it, PERSONAL_CAP) * PERSONAL_PER_USE }
        if (prevNorm != null) {
            var big = 0.0
            userBigrams["$prevNorm $word"]?.let { big = USER_BIGRAM_BASE + min(it, 6) * USER_BIGRAM_PER_USE }
            lang.bigrams?.rank?.get("$prevNorm $word")?.let { rk ->
                big = max(big, EMBEDDED_BIGRAM_TOP - EMBEDDED_BIGRAM_SPAN * rk / lang.bigrams.size)
            }
            s += big
        }
        return s
    }

    private class Cand(var adjust: Double, var contextual: Boolean = false)

    private fun subAdjust(orig: Char, a: Char, lang: Lang): Double = when {
        (orig == 'е' && a == 'ё') || (orig == 'ё' && a == 'е') -> SUB_YO
        "$orig$a" in confusableOf(lang.code) -> SUB_RU_CONFUSE
        lang.neighbors[orig]?.contains(a) == true -> SUB_ADJACENT
        orig in vowelsOf(lang.code) && a in vowelsOf(lang.code) -> SUB_EN_VOWEL
        else -> SUB_OTHER
    }

    private const val FUZZY_SUGGEST_BUDGET = 3.35
    private const val FUZZY_AC_BUDGET = 3.9
    private const val FUZZY_TWO_EDIT_CAP = 3.6
    private const val FUZZY_THREE_EDIT_CAP = 3.9
    private const val SINGLE_EDIT_LEAD = 1.2
    private const val FUZZY_POPS_SUGGEST = 3500
    private const val FUZZY_POPS_AC = 6000
    private const val FUZZY_SCAN_RANGE = 4000
    private const val FUZZY_SCAN_TOTAL = 12000
    private const val FUZZY_EXT_MAX = 12
    private const val FUZZY_RESULTS = 12
    private const val CHEAP_TWO_EDITS = 2.4

    internal class FuzzyMatch(val word: String, val cost: Double, val edits: Int, val ext: Int, val rank: Int)

    private class FState(val lo: Int, val hi: Int, val p: Int, val i: Int, val cost: Double, val edits: Int)

    private class FuzzyHit(var cost: Double, var edits: Int, var ext: Int, var proxy: Double)

    private fun fuzzy(
        lex: Lexicon,
        lang: Lang,
        q: CharArray,
        prefixMode: Boolean,
        maxEdits: Int,
        budget: Double,
        maxPops: Int,
    ): List<FuzzyMatch> {
        val qn = q.size
        if (qn == 0 || lex.size == 0) return emptyList()
        val pq = java.util.PriorityQueue<FState>(64, compareBy { it.cost })
        val seen = HashMap<Long, Double>()
        val hits = HashMap<Int, FuzzyHit>()
        var pops = 0
        var scanBudget = FUZZY_SCAN_TOTAL

        fun key(lo: Int, p: Int, i: Int) = (lo.toLong() shl 24) or (p.toLong() shl 8) or i.toLong()

        fun push(lo: Int, hi: Int, p: Int, i: Int, cost: Double, edits: Int) {
            if (cost > budget || edits > maxEdits) return
            val k = key(lo, p, i)
            val old = seen[k]
            if (old != null && old <= cost) return
            seen[k] = cost
            pq.add(FState(lo, hi, p, i, cost, edits))
        }

        fun emit(j: Int, cost: Double, edits: Int, ext: Int) {
            val rank = lex.rank(j)
            val lmProxy = if (rank == Lexicon.NO_RANK) EXTRA_LM else -ln(rank + 1.5)
            val proxy = lmProxy - cost + EXT_PREFERENCE * ext
            val h = hits[j]
            if (h == null) hits[j] = FuzzyHit(cost, edits, ext, proxy)
            else if (proxy > h.proxy) {
                h.cost = cost; h.edits = edits; h.ext = ext; h.proxy = proxy
            }
        }

        fun scanRange(lo: Int, hi: Int, cost: Double, edits: Int) {
            val count = hi - lo
            if (count > FUZZY_SCAN_RANGE || scanBudget <= 0) return
            scanBudget -= count
            for (j in lo until hi) {
                val ext = lex.len(j) - qn
                if (ext > FUZZY_EXT_MAX) continue
                emit(j, cost, edits, max(0, ext))
            }
        }

        push(0, lex.size, 0, 0, 0.0, 0)
        while (pops < maxPops) {
            val s = pq.poll() ?: break
            pops++
            val recorded = seen[key(s.lo, s.p, s.i)]
            if (recorded != null && recorded < s.cost) continue
            val terminal = lex.len(s.lo) == s.p
            if (s.i == qn) {
                if (prefixMode) {
                    if (s.p >= 2) scanRange(s.lo, s.hi, s.cost, s.edits)
                    continue
                }
                if (terminal) emit(s.lo, s.cost, s.edits, 0)
            } else {
                val qc = q[s.i]
                val dup = (s.i > 0 && q[s.i - 1] == qc) || (s.i + 1 < qn && q[s.i + 1] == qc)
                push(s.lo, s.hi, s.p, s.i + 1, s.cost + (if (dup) -DELETE_DUP else -DELETE), s.edits + 1)
            }
            val lastLex = if (s.p > 0) lex.charAt(s.lo, s.p - 1) else '\u0000'
            var j = s.lo + if (terminal) 1 else 0
            while (j < s.hi) {
                val c = lex.charAt(j, s.p)
                val end = lex.childEnd(j, s.hi, s.p, c)
                if (s.i < qn) {
                    val qc = q[s.i]
                    if (c == qc) {
                        push(j, end, s.p + 1, s.i + 1, s.cost, s.edits)
                    } else {
                        push(j, end, s.p + 1, s.i + 1, s.cost - subAdjust(qc, c, lang), s.edits + 1)
                        if (s.i + 1 < qn && q[s.i + 1] == c && qc != c) {
                            val g = lex.findChild(j, end, s.p + 1, qc)
                            if (g >= 0) {
                                push(
                                    Lexicon.unpackLo(g), Lexicon.unpackHi(g),
                                    s.p + 2, s.i + 2, s.cost - TRANSPOSE, s.edits + 1,
                                )
                            }
                        }
                    }
                    val dupIns = c == lastLex || c == qc
                    push(j, end, s.p + 1, s.i, s.cost + (if (dupIns) -INSERT_DUP else -INSERT), s.edits + 1)
                } else {
                    val dupIns = c == lastLex
                    push(j, end, s.p + 1, s.i, s.cost + (if (dupIns) -INSERT_DUP else -INSERT), s.edits + 1)
                }
                j = end
            }
        }
        return hits.entries
            .sortedByDescending { it.value.proxy }
            .take(FUZZY_RESULTS)
            .map { (j, h) -> FuzzyMatch(lex.word(j), h.cost, h.edits, h.ext, lex.rank(j)) }
    }

    private fun edits1(word: String, lang: Lang): HashMap<String, Double> {
        val out = HashMap<String, Double>()
        val chars = word.toCharArray()
        val n = chars.size
        fun offer(s: String, cost: Double) {
            if (s != word) out.merge(s, cost, ::max)
        }
        for (i in 0 until n) {
            val sb = StringBuilder(n - 1)
            for (j in 0 until n) if (j != i) sb.append(chars[j])
            val dup = (i > 0 && chars[i] == chars[i - 1]) || (i < n - 1 && chars[i] == chars[i + 1])
            offer(sb.toString(), if (dup) DELETE_DUP else DELETE)
        }
        for (i in 0 until n - 1) {
            if (chars[i] == chars[i + 1]) continue
            val c = chars.copyOf()
            val t = c[i]; c[i] = c[i + 1]; c[i + 1] = t
            offer(String(c), TRANSPOSE)
        }
        for (i in 0 until n) {
            val orig = chars[i]
            for (a in lang.alphabet) {
                if (a == orig) continue
                val c = chars.copyOf(); c[i] = a
                offer(String(c), subAdjust(orig, a, lang))
            }
        }
        for (i in 0..n) {
            for (a in lang.alphabet) {
                val sb = StringBuilder(n + 1)
                sb.append(chars, 0, i); sb.append(a); sb.append(chars, i, n - i)
                val dup = (i < n && a == chars[i]) || (i > 0 && a == chars[i - 1])
                offer(sb.toString(), if (dup) INSERT_DUP else INSERT)
            }
        }
        return out
    }

    fun suggest(prefix: String, previous: String?, language: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty()) return predictEmpty(previous, language, limit)
        val folded = prefix.lowercase()
        val lang = langFor(folded.first(), language) ?: return emptyList()
        val prevNorm = previous?.let { normalize(it) }

        val cands = HashMap<String, Cand>()
        fun offer(w: String, adjust: Double, contextual: Boolean = false) {
            if (w == folded) return
            val c = cands.getOrPut(w) { Cand(adjust, contextual) }
            if (adjust > c.adjust) c.adjust = adjust
            if (contextual) c.contextual = true
        }

        val completions = lang.dict.complete(folded, limit + 9)
        completions.forEach { offer(it, EXT_PREFERENCE * (it.length - folded.length)) }

        if (prevNorm != null) {
            continuationsOf(prevNorm, lang, limit * 3).forEach {
                if (it.startsWith(folded)) offer(it, 0.0, contextual = true)
            }
        }

        personalMatches(folded).forEach { offer(it, 0.0) }

        val isPrefix = lang.dict.isPrefix(folded)
        if (folded.length in 2..18 && !(isPrefix && completions.size >= limit)) {
            val edits = edits1(folded, lang)
            for ((cand, cost) in edits) {
                if (lang.knows(cand)) offer(cand, cost)
            }
            if (folded.length >= 3 && completions.size < 2 && !isPrefix) {
                var budget = 12
                for ((cand, cost) in edits) {
                    if (budget <= 0) break
                    if (!lang.dict.isPrefix(cand)) continue
                    for (full in lang.dict.complete(cand, 2)) {
                        offer(full, cost + FUZZY_EXTRA + EXT_PREFERENCE * (full.length - folded.length))
                        budget--
                    }
                }
            }
        }

        if (folded.length in 5..18 && !(isPrefix && completions.size >= limit)) {
            val lex = lang.lex
            if (lex != null) {
                val maxE = if (folded.length >= 7) 3 else 2
                for (m in fuzzy(lex, lang, folded.toCharArray(), prefixMode = true, maxE, FUZZY_SUGGEST_BUDGET, FUZZY_POPS_SUGGEST)) {
                    val adjust = -m.cost + EXT_PREFERENCE * m.ext + if (m.edits > 0) FUZZY_EXTRA else 0.0
                    offer(m.word, adjust)
                }
            }
        }

        val ranked = cands.entries
            .map { it.key to base(it.key, lang, prevNorm) + it.value.adjust }
            .sortedByDescending { it.second }
        return ranked.take(limit).map { matchCase(prefix, lang.dict.display(it.first)) }
    }

    @Synchronized private fun continuationsOf(prev: String, lang: Lang, limit: Int): List<String> {
        val out = LinkedHashSet<String>()
        val marker = "$prev "
        userBigrams.entries
            .asSequence()
            .filter { it.key.startsWith(marker) }
            .sortedByDescending { it.value }
            .take(limit)
            .forEach { out.add(it.key.substring(marker.length)) }
        lang.bigrams?.next?.get(prev)?.forEach { if (out.size < limit) out.add(it) }
        return out.toList()
    }

    private fun outranks(a: Pair<String, Int>, b: Pair<String, Int>): Boolean =
        if (a.second != b.second) a.second > b.second else a.first < b.first

    private fun keepTop(item: Pair<String, Int>, best: MutableList<Pair<String, Int>>, limit: Int) {
        if (limit <= 0) return
        if (best.size < limit) {
            val at = best.indexOfFirst { outranks(item, it) }
            best.add(if (at < 0) best.size else at, item)
            return
        }
        if (!outranks(item, best[best.size - 1])) return
        val at = best.indexOfFirst { outranks(item, it) }
        best.add(if (at < 0) best.size - 1 else at, item)
        best.removeAt(best.size - 1)
    }

    @Synchronized private fun personalMatches(folded: String): List<String> {
        val best = ArrayList<Pair<String, Int>>()
        for ((word, count) in userWords) {
            if (count >= 2 && word.length > folded.length && word.startsWith(folded)) {
                keepTop(word to count, best, 3)
            }
        }
        return best.map { it.first }
    }

    @Synchronized private fun predictEmpty(previous: String?, language: String, limit: Int): List<String> {
        val atStart = previous == null
        val prevNorm = previous?.let { normalize(it) }
        val out = LinkedHashSet<String>()
        fun add(raw: String) {
            if (raw == prevNorm) return
            if (!matchesScript(raw, language)) return
            out.add(if (atStart) raw.replaceFirstChar { it.uppercaseChar() } else raw)
        }
        if (atStart) {
            continuationsOfUser(START_TOKEN, limit * 2).forEach { add(it) }
        } else if (prevNorm != null) {
            continuationsOfUser(prevNorm, limit * 2).forEach { add(it) }
            val bigrams = bigramTables[codeFor(prevNorm.first(), language)]
            bigrams?.next?.get(prevNorm)?.forEach { if (out.size < limit) add(it) }
        }
        if (out.size < limit) {
            val best = ArrayList<Pair<String, Int>>()
            for ((word, count) in userWords) {
                if (count >= 2) keepTop(word to count, best, limit * 2)
            }
            best.forEach { add(it.first) }
        }
        if (atStart && out.size < limit) {
            commonOf(language).forEach { if (out.size < limit) add(it) }
        }
        return out.take(limit)
    }

    private fun continuationsOfUser(prev: String, limit: Int): List<String> {
        val marker = "$prev "
        val best = ArrayList<Pair<String, Int>>()
        for ((key, count) in userBigrams) {
            if (key.startsWith(marker)) keepTop(key.substring(marker.length) to count, best, limit)
        }
        return best.map { it.first }
    }

    private const val AC_MARGIN_OOV = 0.8
    private const val AC_MARGIN_IN_VOCAB = 2.2
    private const val AC_RARE_RANK = 3000
    private const val AC_NEAR_TIE = 0.4
    private const val AC_DEEP_FREQ_CAP = 8000
    private const val AC_PREFIX_LEAD = 0.5

    private val INFORMAL_EN = setOf(
        "dont", "cant", "didnt", "doesnt", "isnt", "wasnt", "arent", "havent", "hasnt", "hadnt",
        "couldnt", "shouldnt", "wouldnt", "wont", "aint", "im", "ive", "youre", "youve", "youll",
        "youd", "theyre", "theyve", "weve", "thats", "whats", "theres", "heres", "lets",
    )
    private val INFORMAL_RU = setOf("спс", "пж", "плз", "мб", "хз", "крч", "оч", "прив", "пон", "лан", "збс", "кст", "чел")

    fun autocorrect(word: String, previous: String?, language: String, deep: Boolean = true): String? {
        val folded = word.lowercase()
        if (!folded.all { it.isLetter() || it == '\'' || it == '-' || it == '’' }) return null
        if (folded == "i" && word != "I") {
            synchronized(this) { if ((userWords["i"] ?: 0) >= 2) return null }
            return "I"
        }
        if (folded.length !in 3..18) return null
        val cyr = folded.count { it in 'а'..'я' || it == 'ё' }
        val lat = folded.count { it in 'a'..'z' }
        if (cyr > 0 && lat > 0) return null
        val lang = langFor(folded.first(), language) ?: return null

        if (lang.bigrams?.vocab?.contains(folded) == true) return null
        if (folded in INFORMAL_EN || folded in INFORMAL_RU) return null
        val personalUses = synchronized(this) {
            if (folded in sessionSkip) return null
            userWords[folded] ?: 0
        }
        if (personalUses >= 2) return null

        val prevNorm = previous?.let { normalize(it) }
        val typedRank = lang.dict.rank[folded]
        val inVocab = typedRank != null
        if (!inVocab && lang.lex?.contains(folded) == true) return null
        if (inVocab && (typedRank!! < AC_RARE_RANK || folded.length < 4 || personalUses > 0)) return null

        val typedBase = if (inVocab) base(folded, lang, prevNorm) else OOV_LM
        val margin = if (inVocab) AC_MARGIN_IN_VOCAB else AC_MARGIN_OOV

        val kindEdit = 0; val kindCompletion = 1; val kindTwoEdits = 2
        data class Best(val word: String, val score: Double, val kind: Int, val contextual: Boolean, val adjust: Double)
        var best: Best? = null
        var second: Double = Double.NEGATIVE_INFINITY
        fun offer(w: String, s: Double, kind: Int, contextual: Boolean, adjust: Double) {
            val b = best
            when {
                b == null -> best = Best(w, s, kind, contextual, adjust)
                w == b.word -> if (s > b.score) best = Best(w, s, kind, contextual, adjust)
                s > b.score -> { second = max(second, b.score); best = Best(w, s, kind, contextual, adjust) }
                s > second -> second = s
            }
        }
        fun pairKnown(w: String): Boolean = prevNorm != null && synchronized(this) {
            userBigrams.containsKey("$prevNorm $w") || lang.bigrams?.rank?.containsKey("$prevNorm $w") == true
        }
        fun offerAdjusted(w: String, adjust: Double, kind: Int) =
            offer(w, base(w, lang, prevNorm) + adjust, kind, pairKnown(w), adjust)

        val isPrefix = lang.dict.isPrefix(folded)
        var bestSingle = Double.NEGATIVE_INFINITY

        for ((cand, cost) in edits1(folded, lang)) {
            if (!lang.knows(cand)) continue
            if (inVocab && (cand.length != folded.length || cost < SUB_ADJACENT)) continue
            if (!inVocab && isPrefix && cand.length != folded.length) continue
            val s = base(cand, lang, prevNorm) + cost
            if (s > bestSingle) bestSingle = s
            offer(cand, s, kindEdit, pairKnown(cand), cost)
        }

        if (deep && !inVocab) {
            if (folded.length >= 5) {
                for (full in lang.dict.complete(folded, 3)) {
                    val ext = full.length - folded.length
                    if (ext <= 2) offerAdjusted(full, COMPLETE_PER_CHAR * ext, kindCompletion)
                }
            }
        }
        if (deep) {
            for (i in 1 until folded.length) {
                val l = folded.substring(0, i)
                val r = folded.substring(i)
                val lLm = lm(l, lang.dict) ?: continue
                val rLm = lm(r, lang.dict) ?: continue
                val known = lang.bigrams?.rank?.containsKey("$l $r") == true ||
                    synchronized(this) { userBigrams.containsKey("$l $r") }
                if (!known) {
                    if (l.length < 3 || r.length < 3) continue
                    if ((lang.dict.rank[l] ?: Int.MAX_VALUE) >= 2000 || (lang.dict.rank[r] ?: Int.MAX_VALUE) >= 2000) continue
                }
                val s = min(lLm, rLm) + SPLIT + (if (known) SPLIT_PAIR_BONUS else 0.0)
                offer("$l $r", s, kindEdit, contextual = known, adjust = SPLIT)
            }
            if (!inVocab && folded.length >= 5 && !isPrefix) {
                val lex = lang.lex
                if (lex != null) {
                    val maxE = if (folded.length >= 8) 3 else 2
                    val matches = fuzzy(lex, lang, folded.toCharArray(), prefixMode = false, maxE, FUZZY_AC_BUDGET, FUZZY_POPS_AC)
                        .filter {
                            it.cost <= when {
                                it.edits <= 1 -> Double.MAX_VALUE
                                it.edits == 2 -> FUZZY_TWO_EDIT_CAP
                                else -> FUZZY_THREE_EDIT_CAP
                            }
                        }
                    for (m in matches) {
                        if (m.edits > 1) continue
                        val s = base(m.word, lang, prevNorm) - m.cost
                        if (s > bestSingle) bestSingle = s
                    }
                    for (m in matches) {
                        val s = base(m.word, lang, prevNorm) - m.cost
                        if (m.edits >= 2 && s < bestSingle + SINGLE_EDIT_LEAD) continue
                        offer(m.word, s, if (m.edits >= 2) kindTwoEdits else kindEdit, pairKnown(m.word), -m.cost)
                    }
                }
            }
        }

        val b = best ?: return null
        if (b.score < typedBase + margin) return null
        if (inVocab && !b.contextual) return null
        if (b.kind == kindTwoEdits && (lang.dict.rank[b.word] ?: Int.MAX_VALUE) >= AC_DEEP_FREQ_CAP &&
            b.adjust < -CHEAP_TWO_EDITS
        ) return null
        if (!inVocab && isPrefix && b.kind != kindCompletion) {
            val topCompletion = lang.dict.complete(folded, 1).firstOrNull()
            if (topCompletion != null) {
                val ext = min(topCompletion.length - folded.length, 2)
                val prior = (lm(topCompletion, lang.dict) ?: OOV_LM) + COMPLETE_PER_CHAR * ext
                if (b.score < prior + AC_PREFIX_LEAD) return null
            }
        }
        if (!b.contextual && second > b.score - AC_NEAR_TIE) return null
        return matchCase(word, lang.dict.display(b.word))
    }

    private fun matchesScript(w: String, language: String): Boolean {
        val c = w.firstOrNull { it.isLetter() } ?: return true
        if (c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё') return language == "ru"
        if (c in 'a'..'z' || c in 'A'..'Z' || c in "äöüßÄÖÜ") return isLatin(language)
        if (c in '\u0600'..'\u06FF') return language == "fa"
        return true
    }

    private fun matchCase(typed: String, suggestion: String): String = when {
        typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() } -> suggestion.uppercase()
        typed.first().isUpperCase() -> suggestion.replaceFirstChar { it.uppercaseChar() }
        else -> suggestion
    }
}
