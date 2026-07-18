package com.kryptos.android.screen

import com.kryptos.android.core.CachePurge
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import com.kryptos.android.signal.SignalService
import java.security.MessageDigest

object ScreenDecryptor {
    private const val MAX_ENTRIES = 500
    private const val NEG_RETRY_MS = 60_000L
    private const val MAX_STEGO_CHARS = 64_000

    class Result(val name: String, val text: String, val mine: Boolean)

    private class Entry(val result: Result?, val at: Long)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > MAX_ENTRIES
    }
    private val lock = Any()

    init {
        CachePurge.register { synchronized(lock) { cache.clear() } }
    }

    fun quickCheck(text: String): Boolean {
        if (WireFormat.extractToken(text) != null) return true
        return text.length in 40..MAX_STEGO_CHARS &&
            (TextStego.mightBeStego(text) || SmartTextStego.mightBeStego(text))
    }

    fun decryptIfPresent(text: String): Result? {
        val key = dedupKey(text) ?: return null
        val now = System.currentTimeMillis()

        synchronized(lock) {
            cache[key]?.let { e ->
                if (e.result != null) return e.result
                if (now - e.at < NEG_RETRY_MS) return null
            }
        }

        val result = attempt(text)
        synchronized(lock) { cache[key] = Entry(result, System.currentTimeMillis()) }
        return result
    }

    private fun attempt(text: String): Result? {
        runCatching { SignalService.cachedDecryptHit(text) }.getOrNull()?.let {
            return Result(it.contact.displayName, it.text, it.mine)
        }
        for (contact in SignalService.contacts.value) {
            runCatching { SignalService.decrypt(text, contact) }.getOrNull()?.let {
                return Result(contact.displayName, it, mine = false)
            }
        }
        return null
    }

    private fun dedupKey(text: String): String? {
        WireFormat.extractToken(text)?.let { run ->
            WireFormat.tokenBytes(run)?.let { return "W" + sha256(it) }
        }
        if (text.length in 40..MAX_STEGO_CHARS) {
            TextStego.decode(text)?.let { return "S" + sha256(it) }
            SmartTextStego.decode(text)?.let { return "M" + sha256(it) }
        }
        return null
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}
