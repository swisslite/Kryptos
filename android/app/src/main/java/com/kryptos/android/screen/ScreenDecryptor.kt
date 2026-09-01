package com.kryptos.android.screen

import android.os.SystemClock
import com.kryptos.android.core.CachePurge
import com.kryptos.android.core.ExpiringCache
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import com.kryptos.android.core.sha256Hex
import com.kryptos.android.signal.SignalService

object ScreenDecryptor {
    private const val MAX_ENTRIES = 500
    private const val HIT_TTL_MS = 5 * 60 * 1000L
    private const val NEG_RETRY_MS = 60_000L
    private const val MAX_STEGO_CHARS = 64_000
    private const val NO_PAYLOAD = 'N'

    const val MAX_SCAN_CHARS = MAX_STEGO_CHARS

    class Result(val name: String, val text: String, val mine: Boolean)

    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
    private val cache = ExpiringCache<Result>(MAX_ENTRIES, HIT_TTL_MS, NEG_RETRY_MS, clock)
    private val keys = ExpiringCache<String>(MAX_ENTRIES, HIT_TTL_MS, HIT_TTL_MS, clock)

    init {
        CachePurge.register { forget() }
        CachePurge.registerDecrypted { forget() }
    }

    fun forget() {
        cache.clear()
        keys.clear()
    }

    fun quickCheck(text: String): Boolean {
        if (text.length > MAX_STEGO_CHARS) return false
        if (WireFormat.extractToken(text) != null) return true
        return text.length >= 40 &&
            (TextStego.mightBeStego(text) || SmartTextStego.mightBeStego(text) || LetterStego.mightBeStego(text))
    }

    fun decryptIfPresent(text: String): Result? {
        val textKey = sha256Hex(text.toByteArray(Charsets.UTF_8))
        val key = keys.lookup(textKey)?.value ?: dedupKey(text).also { keys.remember(textKey, it) }
        cache.lookup(key)?.let { return it.value }
        val result = if (key[0] == NO_PAYLOAD) null else attempt(text)
        cache.remember(key, result)
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

    private fun dedupKey(text: String): String {
        WireFormat.extractToken(text)?.let { run ->
            WireFormat.tokenBytes(run)?.let { return "W" + sha256Hex(it) }
        }
        if (text.length in 40..MAX_STEGO_CHARS) {
            TextStego.decode(text)?.let { return "S" + sha256Hex(it) }
            SmartTextStego.decode(text)?.let { return "M" + sha256Hex(it) }
            LetterStego.decode(text)?.let { return "L" + sha256Hex(it) }
        }
        return NO_PAYLOAD + sha256Hex(text.trim().toByteArray(Charsets.UTF_8))
    }
}
