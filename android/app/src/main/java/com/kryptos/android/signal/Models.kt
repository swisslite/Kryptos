package com.kryptos.android.signal

import com.kryptos.android.core.CachePurge
import com.kryptos.android.core.hexOf
import com.kryptos.android.core.sha256Hex
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import com.kryptos.android.store.SecureStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64
import java.util.UUID

object B64 : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("B64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) =
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}

typealias Blob = @Serializable(with = B64::class) ByteArray

@Serializable
data class Profile(var id: String = UUID.randomUUID().toString(), var name: String) {
    override fun toString(): String = "Profile(id=$id)"
}

@Serializable
data class Contact(val fingerprint: String, var displayName: String) {
    val safetyNumber: String get() = SignalFormat.safetyNumber(fingerprint)

    override fun toString(): String = "Contact(fingerprint=${fingerprint.take(8)})"
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val mine: Boolean,
    val date: Long = System.currentTimeMillis(),
    val expiresAfter: Double? = null,
) {
    val expiryAt: Long?
        get() = expiresAfter?.takeIf { it > 0 }?.let { date + (it * 1000).toLong() }

    override fun toString(): String = "ChatMessage(id=$id, mine=$mine, date=$date)"
}

object SignalFormat {
    fun hex(d: ByteArray): String = hexOf(d)

    fun bytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    fun safetyNumber(fingerprintHex: String): String =
        fingerprintHex.take(24).chunked(4).joinToString(" ").uppercase()
}

data class BundlePayload(
    val registrationId: Long,
    val deviceId: Long,
    val identityKey: ByteArray,
    val signedPreKeyId: Long,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val kyberPreKeyId: Long,
    val kyberPreKey: ByteArray,
    val kyberPreKeySignature: ByteArray,
    val oneTimePreKeyId: Long? = null,
    val oneTimePreKey: ByteArray? = null,
)

@Serializable
data class RetiredPreKeyGen(val signedPreKeyId: Long, val kyberPreKeyId: Long, val retiredAt: Long)

@Serializable
data class CachedDecrypt(
    val fingerprint: String,
    val text: String,
    val date: Long = System.currentTimeMillis(),
    val mine: Boolean = false,
    val expiresAt: Long? = null,
) {
    fun expired(now: Long): Boolean = expiresAt != null && expiresAt <= now

    override fun toString(): String = "CachedDecrypt(fingerprint=$fingerprint, mine=$mine, date=$date)"
}

object DecryptCacheKey {
    private val STEGO_CHARS = 40..64_000

    private val memoLock = Any()
    private var memoText: String? = null
    private var memoKey: String? = null

    init {
        CachePurge.register { synchronized(memoLock) { memoText = null; memoKey = null } }
    }

    fun of(armored: String): String {
        synchronized(memoLock) {
            val key = memoKey
            if (key != null && armored == memoText) return key
        }
        val payload = stegoPayload(armored)
            ?: tokenPayload(armored)
            ?: armored.trim().toByteArray(Charsets.UTF_8)
        val key = sha256Hex(payload)
        synchronized(memoLock) {
            memoText = armored
            memoKey = key
        }
        return key
    }

    private fun tokenPayload(armored: String): ByteArray? =
        WireFormat.extractToken(armored)?.let { WireFormat.tokenBytes(it) }

    private fun stegoPayload(armored: String): ByteArray? {
        if (armored.length !in STEGO_CHARS) return null
        return TextStego.decode(armored) ?: SmartTextStego.decode(armored) ?: LetterStego.decode(armored)
    }
}

object OwnCipherMarker {
    private const val STORE_KEY = "clip.own"

    @Volatile private var hash: String? = null
    @Volatile private var loaded = false
    @Volatile private var lastMarked: String? = null

    init {
        CachePurge.register { synchronized(this) { hash = null; loaded = false; lastMarked = null } }
    }

    fun mark(cipher: String) {
        if (lastMarked == cipher) return
        val key = DecryptCacheKey.of(cipher)
        synchronized(this) {
            hash = key
            loaded = true
            lastMarked = cipher
        }
        runCatching { SecureStore.write(STORE_KEY, key.toByteArray(Charsets.UTF_8)) }
    }

    fun clear() {
        synchronized(this) {
            hash = null
            loaded = true
            lastMarked = null
        }
        runCatching { SecureStore.delete(STORE_KEY) }
    }

    fun matches(text: String): Boolean {
        val stored = stored() ?: return false
        return stored == DecryptCacheKey.of(text)
    }

    private fun stored(): String? = synchronized(this) {
        if (!loaded) {
            hash = runCatching { SecureStore.read(STORE_KEY)?.toString(Charsets.UTF_8) }.getOrNull()
            loaded = true
        }
        hash
    }
}

object PreKeyMark {
    const val LENGTH = 32

    fun of(identityKey: ByteArray, oneTimePreKey: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return sha256Hex(digest.digest(identityKey) + oneTimePreKey).take(LENGTH)
    }
}

class DecryptedForOtherContactException(val displayName: String) :
    Exception("This message is from another contact: $displayName.")

@Serializable
data class Meta(
    var registrationId: Long,
    var signedPreKeyId: Long,
    var signedPreKeyPub: Blob,
    var signedPreKeySig: Blob,
    var kyberPreKeyId: Long,
    var kyberPreKeyPub: Blob,
    var kyberPreKeySig: Blob,
    var contacts: List<Contact> = emptyList(),
    var messages: Map<String, List<ChatMessage>> = emptyMap(),
    var prekeyCreatedAt: Long? = null,
    var retiredPreKeyGens: List<RetiredPreKeyGen> = emptyList(),
    var nextSignedPreKeyId: Long = 3,
    var nextKyberPreKeyId: Long = 4,
    var nextOneTimePreKeyId: Long = 1,
    var oneTimePreKeyIds: List<Long> = emptyList(),
    var autoDelete: Map<String, Double> = emptyMap(),
    var pinned: List<String> = emptyList(),
    var decryptCache: Map<String, CachedDecrypt> = emptyMap(),
    var usedPreKeys: List<String> = emptyList(),
    var seenIncoming: List<String> = emptyList(),
    var expiryStamped: Boolean = false,
) {
    fun expiry(fingerprint: String): Double? = autoDelete[fingerprint]?.takeIf { it > 0 }

    fun rememberUsedPreKey(mark: String) {
        if (mark in usedPreKeys) return
        val cap = 512
        val next = usedPreKeys + mark
        usedPreKeys = if (next.size > cap) next.takeLast(cap) else next
    }

    fun rememberIncoming(cacheKey: String) {
        val mark = incomingMark(cacheKey)
        if (mark in seenIncoming) return
        val cap = 512
        val next = seenIncoming + mark
        seenIncoming = if (next.size > cap) next.takeLast(cap) else next
    }

    fun hasSeenIncoming(cacheKey: String): Boolean = incomingMark(cacheKey) in seenIncoming

    fun cachedDecrypt(armored: String, now: Long = System.currentTimeMillis()): CachedDecrypt? =
        decryptCache[DecryptCacheKey.of(armored)]?.takeUnless { it.expired(now) }

    fun rememberDecrypt(
        armored: String,
        fingerprint: String,
        text: String,
        mine: Boolean = false,
        expiresAt: Long? = null,
    ) {
        val entry = CachedDecrypt(fingerprint, text, mine = mine, expiresAt = expiresAt)
        var cache = decryptCache + (DecryptCacheKey.of(armored) to entry)
        val cap = 300
        if (cache.size > cap) {
            val evict = cache.entries.sortedBy { it.value.date }.take(cache.size - cap).map { it.key }.toSet()
            cache = cache.filterKeys { it !in evict }
        }
        decryptCache = cache
    }

    fun purgeDecrypted(fingerprint: String, text: String) {
        if (decryptCache.isEmpty()) return
        decryptCache = decryptCache.filterValues { !(it.fingerprint == fingerprint && it.text == text) }
    }

    fun purgeDecryptCache(fingerprint: String? = null, maxAgeMs: Long? = null) {
        if (decryptCache.isEmpty()) return
        val now = System.currentTimeMillis()
        decryptCache = decryptCache.filterValues { entry ->
            when {
                fingerprint != null && entry.fingerprint != fingerprint -> true
                maxAgeMs != null -> now - entry.date < maxAgeMs
                else -> false
            }
        }
    }

    fun stampCarriedOverMessages(): Boolean {
        if (expiryStamped) return false
        expiryStamped = true
        for ((fingerprint, list) in messages) {
            val seconds = expiry(fingerprint) ?: continue
            if (list.none { it.expiresAfter == null }) continue
            val stamped = list.map { if (it.expiresAfter == null) it.copy(expiresAfter = seconds) else it }
            messages = messages + (fingerprint to stamped)
        }
        return true
    }

    fun purgeExpired(): Boolean {
        var changed = stampCarriedOverMessages()
        val now = System.currentTimeMillis()
        val cacheBefore = decryptCache.size
        if (decryptCache.values.any { it.expired(now) }) {
            decryptCache = decryptCache.filterValues { !it.expired(now) }
        }
        for ((fingerprint, seconds) in autoDelete) {
            if (seconds <= 0) continue
            purgeDecryptCache(fingerprint, (seconds * 1000).toLong())
        }
        for ((fingerprint, list) in messages) {
            val kept = list.filter { message -> message.expiryAt?.let { it > now } ?: true }
            if (kept.size == list.size) continue
            messages = if (kept.isEmpty()) messages - fingerprint else messages + (fingerprint to kept)
            changed = true
        }
        return changed || decryptCache.size != cacheBefore
    }

    override fun toString(): String =
        "Meta(registrationId=$registrationId, contacts=${contacts.size}, chats=${messages.size})"

    companion object {
        const val INCOMING_MARK_LENGTH = 32

        fun incomingMark(cacheKey: String): String = cacheKey.take(INCOMING_MARK_LENGTH)
    }
}

@Serializable
data class ProfilesIndex(var profiles: List<Profile>, var currentID: String)

class BadKeyStringException : Exception("This is not a valid Kryptos key.")

class OwnKeyException : Exception("This is your own key.")

class NoSessionForContactException : Exception("No Signal session with this contact.")

class PreKeyUnavailableException : Exception("The key material for this message is no longer available.")

class StorageUnavailableException(cause: Throwable?) : Exception("Secure storage is unavailable.", cause)
