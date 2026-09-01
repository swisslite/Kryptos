package com.kryptos.android.keyboard

import android.content.Context
import com.kryptos.android.store.SecureStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

data class PinyinCandidate(val text: String, val consumed: Int)

object PinyinEngine {
    private const val JOIN_PENALTY = 45000
    private const val BOUNDARY_PENALTY = 400000
    private const val INITIALS_PENALTY = 20000
    private const val OVERSHOOT_PENALTY = 1 shl 20
    private const val PARTIAL_BASE = 1 shl 22
    private const val USER_BOOST = 4000
    private const val PERSONAL_CAP = 400
    private const val MAX_INPUT = 32
    private const val STORE_KEY = "kbpinyin"

    private val lock = Any()
    @Volatile private var loaded = false

    private var keyChars = ByteArray(0)
    private var keyStarts = IntArray(0)
    private var initChars = ByteArray(0)
    private var initStarts = IntArray(0)
    private var wordChars = CharArray(0)
    private var wordStarts = IntArray(0)
    private var ranks = IntArray(0)
    private var byInitials = IntArray(0)
    private var syllables = HashSet<String>()
    private var maxSyllable = 6

    private val personal = HashMap<String, Int>()
    private val sessionNoted = HashMap<String, Int>()
    private var personalLoaded = false
    private var personalDirty = false
    private var storeGeneration = 0

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), Int.serializer())

    val isLoaded: Boolean get() = loaded

    private val loading = AtomicBoolean(false)

    fun warmUp(context: Context, onReady: (() -> Unit)? = null) {
        if (loaded && personalLoaded) return
        if (!loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                loadDictionary(app)
                loadPersonal()
            } finally {
                loading.set(false)
            }
            if (loaded) onReady?.invoke()
        }, "kryptos-pinyin").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun loadDictionary(app: Context) {
        if (loaded) return
        val built = runCatching {
            app.assets.open("dict/pinyin-zh.txt").use { dict ->
                app.assets.open("dict/pinyin-syllables.txt").use { syl ->
                    parse(dict, syl)
                }
            }
        }.getOrNull() ?: return
        synchronized(lock) {
            if (!loaded) {
                adopt(built)
                loaded = true
            }
        }
    }

    private fun loadPersonal() {
        synchronized(lock) {
            if (personalLoaded) return
            personalLoaded = true
            val raw = runCatching { SecureStore.read(STORE_KEY) }.getOrNull() ?: return
            runCatching {
                json.decodeFromString(mapSerializer, String(raw, Charsets.UTF_8))
            }.getOrNull()?.let { personal.putAll(it) }
        }
    }

    fun note(word: String) {
        if (word.isEmpty()) return
        synchronized(lock) {
            personal[word] = (personal[word] ?: 0) + 1
            sessionNoted[word] = (sessionNoted[word] ?: 0) + 1
            if (personal.size > PERSONAL_CAP) {
                val keep = personal.entries.sortedByDescending { it.value }.take(PERSONAL_CAP)
                personal.clear()
                keep.forEach { personal[it.key] = it.value }
            }
            personalDirty = true
        }
    }

    fun beginTypingSession() {
        synchronized(lock) { sessionNoted.clear() }
    }

    fun forgetTypingSession() {
        synchronized(lock) {
            if (sessionNoted.isEmpty()) return
            for ((word, count) in sessionNoted) {
                val left = (personal[word] ?: 0) - count
                if (left > 0) personal[word] = left else personal.remove(word)
            }
            sessionNoted.clear()
            personalDirty = true
        }
    }

    fun persist() {
        var generation = 0
        val snapshot = synchronized(lock) {
            if (!personalDirty || !personalLoaded) return
            generation = storeGeneration
            HashMap(personal)
        }
        val payload = json.encodeToString(mapSerializer, snapshot).toByteArray(Charsets.UTF_8)
        if (synchronized(lock) { generation != storeGeneration }) {
            payload.fill(0)
            return
        }
        val ok = runCatching {
            SecureStore.write(STORE_KEY, payload)
            true
        }.getOrDefault(false)
        payload.fill(0)
        synchronized(lock) {
            when {
                generation != storeGeneration -> runCatching { SecureStore.delete(STORE_KEY) }
                ok -> personalDirty = false
            }
        }
    }

    fun forget() {
        synchronized(lock) {
            storeGeneration++
            personal.clear()
            sessionNoted.clear()
            personalDirty = false
        }
        runCatching { SecureStore.delete(STORE_KEY) }
    }

    fun candidates(buffer: String, limit: Int): List<PinyinCandidate> {
        if (!loaded) return emptyList()

        val letters = ByteArray(MAX_INPUT)
        val origin = IntArray(MAX_INPUT)
        val forced = HashSet<Int>()
        var n = 0
        for (i in buffer.indices) {
            val c = buffer[i]
            if (c in 'a'..'z') {
                if (n >= MAX_INPUT) break
                letters[n] = c.code.toByte()
                origin[n] = i
                n++
            } else if (c == '\'') {
                forced.add(n)
            }
        }
        if (n == 0) return emptyList()
        val input = letters.copyOf(n)
        val boosts = synchronized(lock) { HashMap(personal) }

        fun consumedFor(count: Int): Int =
            if (count <= 0) 0 else if (count < n) origin[count - 1] + 1 else buffer.length

        val texts = HashSet<String>()
        val costs = ArrayList<Int>()
        val tiers = ArrayList<Int>()
        val cands = ArrayList<PinyinCandidate>()

        fun offer(text: String, consumed: Int, cost: Int, tier: Int) {
            if (text.isEmpty() || !texts.add(text)) return
            val boost = (boosts[text] ?: 0) * USER_BOOST
            costs.add(maxOf(0, cost - boost))
            tiers.add(tier)
            cands.add(PinyinCandidate(text, consumed))
        }

        val segments = forced.count { it > 0 && it < n } + 1
        val fullLo = lowerBound(ranks.size, input, ::compareKey)
        val fullHi = upperBound(ranks.size, input, ::compareKey)

        var i = fullLo
        while (i < fullHi && keyLength(i) == n) {
            val short = if (initialsLength(i) < segments) BOUNDARY_PENALTY else 0
            offer(word(i), consumedFor(n), ranks[i] + short, 0)
            i++
        }

        val path = compose(input, forced)
        if (path != null && path.size > 1) {
            val sb = StringBuilder()
            var cost = (path.size - 1) * JOIN_PENALTY
            for (e in path) {
                sb.append(word(e))
                cost += ranks[e]
            }
            offer(sb.toString(), consumedFor(n), cost, 0)
        }

        var k = lowerBound(byInitials.size, input, ::compareInitials)
        val kHi = upperBound(byInitials.size, input, ::compareInitials)
        while (k < kHi) {
            val e = byInitials[k]
            if (initialsLength(e) != n) break
            offer(word(e), consumedFor(n), ranks[e] + INITIALS_PENALTY, 1)
            k++
        }

        var j = i
        while (j < fullHi && cands.size < limit * 3) {
            offer(word(j), consumedFor(n), ranks[j] + OVERSHOOT_PENALTY, 2)
            j++
        }

        var length = n - 1
        while (length >= 1 && cands.size < limit * 3) {
            val prefix = input.copyOf(length)
            var p = lowerBound(ranks.size, prefix, ::compareKey)
            val pHi = upperBound(ranks.size, prefix, ::compareKey)
            var added = 0
            while (p < pHi && keyLength(p) == length && added < limit) {
                offer(word(p), consumedFor(length), ranks[p] + (n - length) * PARTIAL_BASE, 3)
                p++
                added++
            }
            length--
        }

        val order = (0 until cands.size).sortedWith(
            compareBy({ tiers[it] }, { costs[it] }, { cands[it].text }),
        )
        return order.take(limit).map { cands[it] }
    }

    private fun compose(input: ByteArray, forced: Set<Int>): IntArray? {
        val n = input.size
        val forward = BooleanArray(n + 1)
        forward[0] = true
        for (a in 0 until n) {
            if (!forward[a]) continue
            var l = 1
            while (l <= maxSyllable && a + l <= n) {
                if (!crosses(a, a + l, n, forced) && syllables.contains(String(input, a, l, Charsets.US_ASCII))) {
                    forward[a + l] = true
                }
                l++
            }
        }
        if (!forward[n]) return null

        val backward = BooleanArray(n + 1)
        backward[n] = true
        for (a in n - 1 downTo 0) {
            if (!forward[a]) continue
            var l = 1
            while (l <= maxSyllable && a + l <= n) {
                if (backward[a + l] && !crosses(a, a + l, n, forced) &&
                    syllables.contains(String(input, a, l, Charsets.US_ASCII))
                ) {
                    backward[a] = true
                    break
                }
                l++
            }
        }

        val stops = ArrayList<Int>()
        for (a in 0..n) if (forward[a] && backward[a]) stops.add(a)
        if (stops.size < 2) return null

        val best = IntArray(n + 1) { Int.MAX_VALUE }
        val viaEntry = IntArray(n + 1) { -1 }
        val viaFrom = IntArray(n + 1) { -1 }
        best[0] = 0
        for (a in stops) {
            if (best[a] == Int.MAX_VALUE) continue
            for (b in stops) {
                if (b <= a) continue
                val slice = input.copyOfRange(a, b)
                val lo = lowerBound(ranks.size, slice, ::compareKey)
                val hi = upperBound(ranks.size, slice, ::compareKey)
                if (lo >= hi || keyLength(lo) != b - a) continue
                val cost = best[a] + ranks[lo] + if (a > 0) JOIN_PENALTY else 0
                if (cost < best[b]) {
                    best[b] = cost
                    viaEntry[b] = lo
                    viaFrom[b] = a
                }
            }
        }
        if (best[n] == Int.MAX_VALUE) return null

        val reversed = ArrayList<Int>()
        var at = n
        while (at > 0 && viaEntry[at] >= 0) {
            reversed.add(viaEntry[at])
            at = viaFrom[at]
        }
        val out = IntArray(reversed.size)
        for (idx in reversed.indices) out[idx] = reversed[reversed.size - 1 - idx]
        return out
    }

    private fun crosses(from: Int, to: Int, n: Int, forced: Set<Int>): Boolean {
        for (p in from + 1..to) if (p < n && forced.contains(p)) return true
        return false
    }

    private fun keyLength(i: Int) = keyStarts[i + 1] - keyStarts[i]
    private fun initialsLength(i: Int) = initStarts[i + 1] - initStarts[i]

    private fun word(i: Int): String = String(wordChars, wordStarts[i], wordStarts[i + 1] - wordStarts[i])

    private fun compareKey(i: Int, prefix: ByteArray): Int {
        val from = keyStarts[i]
        val len = keyLength(i)
        for (p in prefix.indices) {
            if (p >= len) return -1
            val a = keyChars[from + p].toInt() and 0xFF
            val b = prefix[p].toInt() and 0xFF
            if (a != b) return if (a < b) -1 else 1
        }
        return 0
    }

    private fun compareInitials(slot: Int, prefix: ByteArray): Int {
        val i = byInitials[slot]
        val from = initStarts[i]
        val len = initialsLength(i)
        for (p in prefix.indices) {
            if (p >= len) return -1
            val a = initChars[from + p].toInt() and 0xFF
            val b = prefix[p].toInt() and 0xFF
            if (a != b) return if (a < b) -1 else 1
        }
        return 0
    }

    private inline fun lowerBound(count: Int, prefix: ByteArray, compare: (Int, ByteArray) -> Int): Int {
        if (count == 0 || prefix.isEmpty()) return 0
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, prefix) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private inline fun upperBound(count: Int, prefix: ByteArray, compare: (Int, ByteArray) -> Int): Int {
        if (count == 0 || prefix.isEmpty()) return 0
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, prefix) <= 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private class Built(
        val keyChars: ByteArray, val keyStarts: IntArray,
        val initChars: ByteArray, val initStarts: IntArray,
        val wordChars: CharArray, val wordStarts: IntArray,
        val ranks: IntArray, val byInitials: IntArray,
        val syllables: HashSet<String>, val maxSyllable: Int,
    )

    private fun adopt(b: Built) {
        keyChars = b.keyChars; keyStarts = b.keyStarts
        initChars = b.initChars; initStarts = b.initStarts
        wordChars = b.wordChars; wordStarts = b.wordStarts
        ranks = b.ranks; byInitials = b.byInitials
        syllables = b.syllables; maxSyllable = b.maxSyllable
    }

    internal fun loadForTest(dict: InputStream, syllables: InputStream) {
        val built = parse(dict, syllables)
        synchronized(lock) {
            adopt(built)
            loaded = true
            personalLoaded = true
        }
    }

    private fun parse(dictStream: InputStream, syllableStream: InputStream): Built {
        val sylSet = HashSet<String>()
        var maxSyl = 1
        syllableStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            val s = line.trim()
            if (s.isNotEmpty()) {
                sylSet.add(s)
                if (s.length > maxSyl) maxSyl = s.length
            }
        }

        val raw = dictStream.readBytes()
        val n = raw.size
        var rows = 0
        for (b in raw) if (b.toInt() == 10) rows++

        val keyChars = ByteArray(n / 3)
        val keyStarts = IntArray(rows + 1)
        val initChars = ByteArray(n / 6)
        val initStarts = IntArray(rows + 1)
        val wordChars = CharArray(n / 4)
        val wordStarts = IntArray(rows + 1)
        val ranks = IntArray(rows)

        var kp = 0
        var ip = 0
        var wp = 0
        var row = 0
        var i = 0
        while (i < n && row < rows) {
            val keyFrom = i
            while (i < n && raw[i].toInt() != 9) i++
            if (i >= n) break
            val keyTo = i; i++

            val initFrom = i
            while (i < n && raw[i].toInt() != 9) i++
            if (i >= n) break
            val initTo = i; i++

            val wordFrom = i
            while (i < n && raw[i].toInt() != 9) i++
            if (i >= n) break
            val wordTo = i; i++

            var rank = 0
            while (i < n && raw[i] >= 48 && raw[i] <= 57) {
                rank = rank * 10 + (raw[i] - 48)
                i++
            }
            while (i < n && raw[i].toInt() != 10) i++
            i++

            for (p in keyFrom until keyTo) keyChars[kp++] = raw[p]
            keyStarts[row + 1] = kp
            for (p in initFrom until initTo) initChars[ip++] = raw[p]
            initStarts[row + 1] = ip

            var w = wordFrom
            while (w < wordTo) {
                val b0 = raw[w].toInt() and 0xFF
                when {
                    b0 < 0x80 -> {
                        wordChars[wp++] = b0.toChar(); w += 1
                    }
                    b0 < 0xE0 -> {
                        if (w + 1 >= wordTo) break
                        wordChars[wp++] = (((b0 and 0x1F) shl 6) or (raw[w + 1].toInt() and 0x3F)).toChar()
                        w += 2
                    }
                    b0 < 0xF0 -> {
                        if (w + 2 >= wordTo) break
                        wordChars[wp++] = (((b0 and 0x0F) shl 12) or
                            ((raw[w + 1].toInt() and 0x3F) shl 6) or
                            (raw[w + 2].toInt() and 0x3F)).toChar()
                        w += 3
                    }
                    else -> {
                        if (w + 3 >= wordTo) break
                        val scalar = ((b0 and 0x07) shl 18) or
                            ((raw[w + 1].toInt() and 0x3F) shl 12) or
                            ((raw[w + 2].toInt() and 0x3F) shl 6) or
                            (raw[w + 3].toInt() and 0x3F)
                        val cp = scalar - 0x10000
                        wordChars[wp++] = (0xD800 + (cp shr 10)).toChar()
                        wordChars[wp++] = (0xDC00 + (cp and 0x3FF)).toChar()
                        w += 4
                    }
                }
            }
            wordStarts[row + 1] = wp
            ranks[row] = rank
            row++
        }

        val boxed = arrayOfNulls<Int>(row)
        for (p in 0 until row) boxed[p] = p
        java.util.Arrays.sort(boxed) { x, y ->
            compareInitialsEntries(x!!, y!!, initChars, initStarts, ranks)
        }
        val order = IntArray(row)
        for (p in 0 until row) order[p] = boxed[p]!!

        return Built(
            keyChars.copyOf(kp), keyStarts.copyOf(row + 1),
            initChars.copyOf(ip), initStarts.copyOf(row + 1),
            wordChars.copyOf(wp), wordStarts.copyOf(row + 1),
            ranks.copyOf(row), order, sylSet, maxSyl,
        )
    }

    private fun compareInitialsEntries(
        a: Int, b: Int, chars: ByteArray, starts: IntArray, ranks: IntArray,
    ): Int {
        val fa = starts[a]
        val la = starts[a + 1] - fa
        val fb = starts[b]
        val lb = starts[b + 1] - fb
        var p = 0
        while (p < la && p < lb) {
            val x = chars[fa + p].toInt() and 0xFF
            val y = chars[fb + p].toInt() and 0xFF
            if (x != y) return if (x < y) -1 else 1
            p++
        }
        if (la != lb) return if (la < lb) -1 else 1
        if (ranks[a] != ranks[b]) return if (ranks[a] < ranks[b]) -1 else 1
        return a.compareTo(b)
    }
}
