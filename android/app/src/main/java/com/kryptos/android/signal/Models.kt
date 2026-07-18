package com.kryptos.android.signal

import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.security.MessageDigest
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
data class Profile(var id: String = UUID.randomUUID().toString(), var name: String)

@Serializable
data class Contact(val fingerprint: String, var displayName: String) {
    val safetyNumber: String get() = SignalFormat.safetyNumber(fingerprint)
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val mine: Boolean,
    val date: Long = System.currentTimeMillis(),
)

object SignalFormat {
    fun hex(d: ByteArray): String = d.joinToString("") { "%02x".format(it) }

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
)

object DecryptCacheKey {
    fun of(armored: String): String {
        val payload = TextStego.decode(armored)
            ?: SmartTextStego.decode(armored)
            ?: WireFormat.tokenBytes(armored)
            ?: armored.trim().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    }
}

object OwnCipherMarker {
    @Volatile private var hash: String? = null
    fun mark(cipher: String) { hash = DecryptCacheKey.of(cipher) }
    fun matches(text: String): Boolean = hash?.equals(DecryptCacheKey.of(text)) == true
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
    var decryptCache: Map<String, CachedDecrypt> = emptyMap(),
) {
    fun rememberDecrypt(armored: String, fingerprint: String, text: String, mine: Boolean = false) {
        var cache = decryptCache + (DecryptCacheKey.of(armored) to CachedDecrypt(fingerprint, text, mine = mine))
        val cap = 64
        if (cache.size > cap) {
            val evict = cache.entries.sortedBy { it.value.date }.take(cache.size - cap).map { it.key }.toSet()
            cache = cache.filterKeys { it !in evict }
        }
        decryptCache = cache
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
}

@Serializable
data class ProfilesIndex(var profiles: List<Profile>, var currentID: String)

class BadKeyStringException : Exception("This is not a valid Kryptos key.")
