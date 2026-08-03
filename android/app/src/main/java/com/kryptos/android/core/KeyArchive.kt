package com.kryptos.android.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

@Serializable
data class ArchivedContact(val fingerprint: String, val displayName: String)

@Serializable
data class ArchivedRetired(val signedPreKeyId: Long, val kyberPreKeyId: Long, val retiredAt: Long)

@Serializable
data class ArchivedProfile(
    val id: String,
    val name: String,
    val identity: String,
    val registrationId: Long,
    val signedPreKeyId: Long,
    val signedPreKeyPub: String,
    val signedPreKeySig: String,
    val kyberPreKeyId: Long,
    val kyberPreKeyPub: String,
    val kyberPreKeySig: String,
    val prekeyCreatedAt: Long? = null,
    val nextSignedPreKeyId: Long,
    val nextKyberPreKeyId: Long,
    val nextOneTimePreKeyId: Long,
    val oneTimePreKeyIds: List<Long> = emptyList(),
    val retired: List<ArchivedRetired> = emptyList(),
    val autoDelete: Map<String, Double> = emptyMap(),
    val contacts: List<ArchivedContact> = emptyList(),
    val preKeys: Map<String, String> = emptyMap(),
    val signedPreKeys: Map<String, String> = emptyMap(),
    val kyberPreKeys: Map<String, String> = emptyMap(),
    val sessions: Map<String, String> = emptyMap(),
    val identities: Map<String, String> = emptyMap(),
)

@Serializable
data class ArchivedPgpIdentity(
    val id: String,
    val name: String,
    val email: String,
    val fingerprint: String,
    val algo: String,
    val created: Long,
    val publicKey: String,
    val secret: String,
)

@Serializable
data class ArchivedPgpRecipient(val name: String, val publicKey: String, val fingerprint: String)

@Serializable
data class KeyArchive(
    val kryptos: String = MAGIC,
    val v: Int = VERSION,
    val created: Long = System.currentTimeMillis(),
    val profiles: List<ArchivedProfile> = emptyList(),
    val pgpIdentities: List<ArchivedPgpIdentity> = emptyList(),
    val pgpRecipients: List<ArchivedPgpRecipient> = emptyList(),
) {
    val isEmpty: Boolean get() = profiles.isEmpty() && pgpIdentities.isEmpty() && pgpRecipients.isEmpty()

    val contactCount: Int get() = profiles.sumOf { it.contacts.size }

    companion object {
        const val MAGIC = "keys"
        const val VERSION = 1
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_FILE_BYTES = 8 * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private class WipingBuffer : java.io.OutputStream() {
            private var buf = ByteArray(8192)
            private var size = 0

            private fun ensure(extra: Int) {
                if (size + extra <= buf.size) return
                var capacity = buf.size
                while (capacity < size + extra) capacity *= 2
                val grown = buf.copyOf(capacity)
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
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun seal(archive: KeyArchive, password: String): String {
            if (password.length < MIN_PASSWORD_LENGTH) throw ArchiveException(ArchiveException.Kind.PASSWORD_TOO_SHORT)
            if (archive.isEmpty) throw ArchiveException(ArchiveException.Kind.NOTHING_TO_EXPORT)
            val buffer = WipingBuffer()
            val plain = try {
                json.encodeToStream(serializer(), archive, buffer)
                buffer.drain()
            } catch (e: Exception) {
                buffer.drain().fill(0)
                throw e
            }
            try {
                return WireFormat.token(PasswordCipher.encrypt(plain, password))
            } finally {
                plain.fill(0)
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun open(text: String, password: String): KeyArchive {
            val raw = WireFormat.tokenBytes(text.trim())
                ?: throw ArchiveException(ArchiveException.Kind.UNREADABLE)
            val plain = try {
                PasswordCipher.decrypt(raw, password)
            } catch (e: Exception) {
                throw ArchiveException(ArchiveException.Kind.UNREADABLE)
            }
            val archive = try {
                json.decodeFromStream<KeyArchive>(java.io.ByteArrayInputStream(plain))
            } catch (e: Exception) {
                throw ArchiveException(ArchiveException.Kind.UNREADABLE)
            } finally {
                plain.fill(0)
            }
            if (archive.kryptos != MAGIC || archive.v != VERSION || archive.isEmpty) {
                throw ArchiveException(ArchiveException.Kind.UNREADABLE)
            }
            return archive
        }
    }
}

class ArchiveException(val kind: Kind) : Exception(kind.name) {
    enum class Kind { PASSWORD_TOO_SHORT, UNREADABLE, NOTHING_TO_EXPORT, WRITE_FAILED }
}
