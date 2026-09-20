package com.kryptos.android.signal

import com.kryptos.android.core.hexOf
import com.kryptos.android.core.wipingBytes
import com.kryptos.android.store.SecureStore
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64
import java.util.UUID

class KryptosSignalStore(
    private val identity: IdentityKeyPair,
    private val registrationId: Int,
    private val storageKey: String,
) : SignalProtocolStore {

    @Serializable
    private data class Snapshot(
        var preKeys: MutableMap<String, Blob> = mutableMapOf(),
        var signedPreKeys: MutableMap<String, Blob> = mutableMapOf(),
        var kyberPreKeys: MutableMap<String, Blob> = mutableMapOf(),
        var sessions: MutableMap<String, Blob> = mutableMapOf(),
        var identities: MutableMap<String, Blob> = mutableMapOf(),
        var usedBaseKeys: MutableSet<String> = mutableSetOf(),
    ) {
        fun copied() = Snapshot(
            preKeys.toMutableMap(), signedPreKeys.toMutableMap(), kyberPreKeys.toMutableMap(),
            sessions.toMutableMap(), identities.toMutableMap(), usedBaseKeys.toMutableSet(),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var snap = Snapshot()
    private val senderKeys = mutableMapOf<String, SenderKeyRecord>()

    init {
        SecureStore.readStrict(storageKey)?.let { dec ->
            snap = try {
                json.decodeFromString<Snapshot>(String(dec, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalStateException("KryptosSignalStore: '$storageKey' exists but cannot be parsed", e)
            }
        }
    }

    private var batchDepth = 0
    private var pendingWrite = false

    private fun persist() {
        if (batchDepth > 0) {
            pendingWrite = true
            return
        }
        writeSnapshot()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeSnapshot() {
        writeWiped(storageKey, wipingBytes { json.encodeToStream(Snapshot.serializer(), snap, it) })
    }

    fun <T> batch(body: () -> T): T {
        val committed = if (batchDepth == 0) snap.copied() else null
        batchDepth++
        val result = try {
            body()
        } catch (t: Throwable) {
            batchDepth--
            if (committed != null) rollBack(committed)
            throw t
        }
        batchDepth--
        if (committed != null && pendingWrite) {
            pendingWrite = false
            try {
                writeSnapshot()
            } catch (t: Throwable) {
                rollBack(committed)
                throw t
            }
        }
        return result
    }

    private fun rollBack(committed: Snapshot) {
        snap = committed
        pendingWrite = false
    }

    private fun addrKey(a: SignalProtocolAddress) = "${a.name}|${a.deviceId}"

    override fun getIdentityKeyPair(): IdentityKeyPair = identity

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val serialized = identityKey.serialize()
        val existing = snap.identities[addrKey(address)]
        snap.identities[addrKey(address)] = serialized
        persist()
        return if (existing != null && !existing.contentEquals(serialized)) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val serialized = identityKey.serialize()
        val existing = snap.identities[addrKey(address)] ?: return hexOf(serialized) == address.name
        return existing.contentEquals(serialized)
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        snap.identities[addrKey(address)]?.let { IdentityKey(it) }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        snap.preKeys[preKeyId.toString()]?.let { PreKeyRecord(it) }
            ?: throw InvalidKeyIdException("no prekey $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        snap.preKeys[preKeyId.toString()] = record.serialize(); persist()
    }

    override fun containsPreKey(preKeyId: Int): Boolean = snap.preKeys.containsKey(preKeyId.toString())

    override fun removePreKey(preKeyId: Int) {
        if (snap.preKeys.remove(preKeyId.toString()) != null) persist()
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        snap.sessions[addrKey(address)]?.let { SessionRecord(it) }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw NoSessionException("no session for $it") }

    override fun getSubDeviceSessions(name: String): List<Int> =
        snap.sessions.keys.mapNotNull {
            val parts = it.split("|")
            if (parts.size == 2 && parts[0] == name && parts[1] != "1") parts[1].toIntOrNull() else null
        }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        snap.sessions[addrKey(address)] = record.serialize(); persist()
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        snap.sessions.containsKey(addrKey(address))

    override fun deleteSession(address: SignalProtocolAddress) {
        if (snap.sessions.remove(addrKey(address)) != null) persist()
    }

    override fun deleteAllSessions(name: String) {
        val prefix = "$name|"
        val before = snap.sessions.size
        snap.sessions.keys.removeAll { it.startsWith(prefix) }
        if (snap.sessions.size != before) persist()
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        snap.signedPreKeys[signedPreKeyId.toString()]?.let { SignedPreKeyRecord(it) }
            ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        snap.signedPreKeys.values.map { SignedPreKeyRecord(it) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        snap.signedPreKeys[signedPreKeyId.toString()] = record.serialize(); persist()
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        snap.signedPreKeys.containsKey(signedPreKeyId.toString())

    override fun removeSignedPreKey(signedPreKeyId: Int) = removeRetiredSignedPreKey(signedPreKeyId.toLong())

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        snap.kyberPreKeys[kyberPreKeyId.toString()]?.let { KyberPreKeyRecord(it) }
            ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        snap.kyberPreKeys.values.map { KyberPreKeyRecord(it) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        snap.kyberPreKeys[kyberPreKeyId.toString()] = record.serialize(); persist()
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        snap.kyberPreKeys.containsKey(kyberPreKeyId.toString())

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val mark = "$kyberPreKeyId|$signedPreKeyId|${hexOf(baseKey.serialize())}"
        if (!snap.usedBaseKeys.add(mark)) throw ReusedBaseKeyException("base key already used with these pre-keys")
        persist()
    }

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        senderKeys["${addrKey(sender)}|$distributionId"] = record
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        senderKeys["${addrKey(sender)}|$distributionId"]

    fun removeRetiredSignedPreKey(id: Long) {
        val removed = snap.signedPreKeys.remove(id.toString()) != null
        if (forgetUsedBaseKeys(SIGNED_PART, id) || removed) persist()
    }

    fun removeRetiredKyberPreKey(id: Long) {
        val removed = snap.kyberPreKeys.remove(id.toString()) != null
        if (forgetUsedBaseKeys(KYBER_PART, id) || removed) persist()
    }

    private fun forgetUsedBaseKeys(part: Int, id: Long): Boolean {
        val wanted = id.toString()
        return snap.usedBaseKeys.removeAll { it.split('|').getOrNull(part) == wanted }
    }

    fun removeSessionAndIdentity(name: String) {
        val prefix = "$name|"
        snap.sessions.keys.removeAll { it.startsWith(prefix) }
        snap.identities.keys.removeAll { it.startsWith(prefix) }
        persist()
    }

    fun removeAllSessionsAndPeerIdentities() {
        snap.sessions.clear()
        snap.identities.clear()
        persist()
    }

    class Archive(
        val parts: Map<String, Map<String, String>>,
        val usedBaseKeys: List<String>,
    )

    companion object {
        private const val KYBER_PART = 0
        private const val SIGNED_PART = 1

        private val archiveJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun exportArchive(storageKey: String): Archive? {
            val raw = runCatching { SecureStore.readStrict(storageKey) }.getOrElse { return null }
                ?: return Archive(emptyMap(), emptyList())
            val snap = runCatching {
                archiveJson.decodeFromString<Snapshot>(String(raw, Charsets.UTF_8))
            }.getOrNull() ?: return null
            fun enc(m: Map<String, Blob>) = m.mapValues { Base64.getEncoder().encodeToString(it.value) }
            return Archive(
                mapOf(
                    "preKeys" to enc(snap.preKeys),
                    "signedPreKeys" to enc(snap.signedPreKeys),
                    "kyberPreKeys" to enc(snap.kyberPreKeys),
                    "sessions" to enc(snap.sessions),
                    "identities" to enc(snap.identities),
                ),
                snap.usedBaseKeys.toList(),
            )
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun writeArchive(storageKey: String, archive: Archive) {
            fun dec(name: String): MutableMap<String, Blob> {
                val out = mutableMapOf<String, Blob>()
                archive.parts[name]?.forEach { (k, v) -> out[k] = Base64.getDecoder().decode(v) }
                return out
            }
            val snap = Snapshot(
                preKeys = dec("preKeys"),
                signedPreKeys = dec("signedPreKeys"),
                kyberPreKeys = dec("kyberPreKeys"),
                sessions = dec("sessions"),
                identities = dec("identities"),
                usedBaseKeys = archive.usedBaseKeys.toMutableSet(),
            )
            writeWiped(storageKey, wipingBytes { archiveJson.encodeToStream(Snapshot.serializer(), snap, it) })
        }

        internal fun writeWiped(name: String, data: ByteArray) {
            try {
                SecureStore.write(name, data)
            } finally {
                data.fill(0)
            }
        }
    }
}
