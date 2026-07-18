package com.kryptos.android.signal

import com.kryptos.android.core.BinaryReader
import com.kryptos.android.core.BinaryWriter
import com.kryptos.android.core.CachePurge
import com.kryptos.android.store.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.security.SecureRandom
import java.util.Base64

object SignalService {
    private const val KEY_PREFIX = "KRYPTOS-KEY:"
    private const val BUNDLE_FORMAT: Int = 0x01

    private const val ROTATION_INTERVAL_MS = 2L * 24 * 3600 * 1000
    private const val RETENTION_MS = 30L * 24 * 3600 * 1000
    private const val ONE_TIME_POOL_LIMIT = 100

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val rng = SecureRandom()

    val profiles = MutableStateFlow<List<Profile>>(emptyList())
    val currentID = MutableStateFlow("")
    val contacts = MutableStateFlow<List<Contact>>(emptyList())
    val messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val myFingerprint = MutableStateFlow("")
    val mySafetyNumber = MutableStateFlow("")

    private lateinit var identity: IdentityKeyPair
    private lateinit var store: KryptosSignalStore
    private lateinit var meta: Meta

    private object StoreKey {
        const val index = "index"
        fun identity(id: String) = "identity.$id"
        fun meta(id: String) = "meta.$id"
        fun store(id: String) = "store.$id"
    }

    @Volatile private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            var index = loadIndex()
            if (index.profiles.isEmpty()) {
                val p = Profile(name = defaultProfileName(1))
                index = ProfilesIndex(profiles = listOf(p), currentID = p.id)
                saveIndex(index)
            }
            profiles.value = index.profiles
            currentID.value = if (index.profiles.any { it.id == index.currentID }) index.currentID else index.profiles[0].id
            load(currentID.value)
            initialized = true
        }
    }

    private fun defaultProfileName(n: Int): String =
        if (java.util.Locale.getDefault().language.startsWith("ru")) "Профиль $n" else "Profile $n"

    private fun loadIndex(): ProfilesIndex =
        SecureStore.readStrict(StoreKey.index)?.let {
            try {
                json.decodeFromString<ProfilesIndex>(String(it, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalStateException("SignalService: profiles index exists but cannot be parsed", e)
            }
        } ?: ProfilesIndex(emptyList(), "")

    private fun saveIndex(index: ProfilesIndex) {
        SecureStore.write(StoreKey.index, json.encodeToString(ProfilesIndex.serializer(), index).toByteArray())
    }

    private fun persistIndex() = saveIndex(ProfilesIndex(profiles.value, currentID.value))

    fun switchTo(id: String) = synchronized(lock) {
        if (profiles.value.none { it.id == id }) return
        currentID.value = id
        persistIndex()
        load(id)
    }

    fun createProfile(name: String): Profile = synchronized(lock) {
        val trimmed = name.trim()
        val profile = Profile(name = trimmed.ifEmpty { defaultProfileName(profiles.value.size + 1) })
        profiles.value = profiles.value + profile
        currentID.value = profile.id
        persistIndex()
        load(profile.id)
        profile
    }

    fun deleteProfile(id: String) = synchronized(lock) {
        CachePurge.purgeAll()
        wipeStorage(id)
        var list = profiles.value.filter { it.id != id }
        if (list.isEmpty()) {
            list = listOf(Profile(name = defaultProfileName(1)))
            currentID.value = list[0].id
        } else if (currentID.value == id) {
            currentID.value = list[0].id
        }
        profiles.value = list
        persistIndex()
        load(currentID.value)
    }

    fun regenerateCurrentIdentity() = synchronized(lock) {
        CachePurge.purgeAll()
        wipeStorage(currentID.value)
        load(currentID.value)
    }

    private fun wipeStorage(id: String) {
        SecureStore.delete(StoreKey.identity(id))
        SecureStore.delete(StoreKey.meta(id))
        SecureStore.delete(StoreKey.store(id))
        AppSettingsStore.clearKeyboardContact(id)
    }

    private fun load(id: String) {
        val identityBytes = SecureStore.readStrict(StoreKey.identity(id))
        val identity_ = if (identityBytes != null) {
            try {
                IdentityKeyPair(identityBytes)
            } catch (e: Exception) {
                throw IllegalStateException("SignalService: identity '$id' exists but cannot be parsed", e)
            }
        } else {
            IdentityKeyPair.generate().also { SecureStore.write(StoreKey.identity(id), it.serialize()) }
        }
        identity = identity_
        myFingerprint.value = SignalFormat.hex(identity_.publicKey.serialize())
        mySafetyNumber.value = SignalFormat.safetyNumber(myFingerprint.value)

        val loadedMeta = SecureStore.readStrict(StoreKey.meta(id))?.let {
            try {
                json.decodeFromString<Meta>(String(it, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalStateException("SignalService: meta '$id' exists but cannot be parsed", e)
            }
        }
        val regId = loadedMeta?.registrationId ?: (1L + rng.nextInt(0x3FFF))
        store = KryptosSignalStore(identity_, regId.toInt(), StoreKey.store(id))

        meta = loadedMeta ?: provisionInitial(regId)
        contacts.value = meta.contacts
        messages.value = meta.messages
        maintainPreKeys()
        saveMeta()
        purgeExpiredMessages()
    }

    private fun provisionInitial(regId: Long): Meta {
        val gen = generateSignedAndKyber(signedId = 1, kyberId = 2)
        return Meta(
            registrationId = regId,
            signedPreKeyId = gen.signedId, signedPreKeyPub = gen.signedPub, signedPreKeySig = gen.signedSig,
            kyberPreKeyId = gen.kyberId, kyberPreKeyPub = gen.kyberPub, kyberPreKeySig = gen.kyberSig,
            prekeyCreatedAt = System.currentTimeMillis(),
            nextSignedPreKeyId = 3, nextKyberPreKeyId = 4, nextOneTimePreKeyId = 1,
        )
    }

    private fun maintainPreKeys() {
        if (meta.prekeyCreatedAt == null) meta.prekeyCreatedAt = System.currentTimeMillis()

        meta.prekeyCreatedAt?.let { created ->
            if (System.currentTimeMillis() - created > ROTATION_INTERVAL_MS) rotateSignedAndKyber()
        }

        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val kept = ArrayList<RetiredPreKeyGen>()
        for (g in meta.retiredPreKeyGens) {
            if (g.retiredAt < cutoff) {
                store.removeRetiredSignedPreKey(g.signedPreKeyId)
                store.removeRetiredKyberPreKey(g.kyberPreKeyId)
            } else {
                kept.add(g)
            }
        }
        meta.retiredPreKeyGens = kept
    }

    private fun rotateSignedAndKyber() {
        meta.retiredPreKeyGens = meta.retiredPreKeyGens +
            RetiredPreKeyGen(meta.signedPreKeyId, meta.kyberPreKeyId, System.currentTimeMillis())

        val signedId = meta.nextSignedPreKeyId
        val kyberId = meta.nextKyberPreKeyId
        val gen = generateSignedAndKyber(signedId, kyberId)
        meta.signedPreKeyId = gen.signedId; meta.signedPreKeyPub = gen.signedPub; meta.signedPreKeySig = gen.signedSig
        meta.kyberPreKeyId = gen.kyberId; meta.kyberPreKeyPub = gen.kyberPub; meta.kyberPreKeySig = gen.kyberSig
        meta.prekeyCreatedAt = System.currentTimeMillis()
        meta.nextSignedPreKeyId = signedId + 2
        meta.nextKyberPreKeyId = kyberId + 2
    }

    private data class GenKeys(
        val signedId: Long, val signedPub: ByteArray, val signedSig: ByteArray,
        val kyberId: Long, val kyberPub: ByteArray, val kyberSig: ByteArray,
    )

    private fun generateSignedAndKyber(signedId: Long, kyberId: Long): GenKeys {
        val now = System.currentTimeMillis()
        val signed = ECKeyPair.generate()
        val signedSig = identity.privateKey.calculateSignature(signed.publicKey.serialize())
        store.storeSignedPreKey(signedId.toInt(), SignedPreKeyRecord(signedId.toInt(), now, signed, signedSig))
        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSig = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
        store.storeKyberPreKey(kyberId.toInt(), KyberPreKeyRecord(kyberId.toInt(), now, kyber, kyberSig))
        return GenKeys(signedId, signed.publicKey.serialize(), signedSig, kyberId, kyber.publicKey.serialize(), kyberSig)
    }

    private fun nextOneTimePreKeyForBundle(): Pair<Long, ByteArray> {
        var pool = meta.oneTimePreKeyIds.filter { store.containsPreKey(it.toInt()) }

        val id = meta.nextOneTimePreKeyId
        val priv = ECPrivateKey.generate()
        store.storePreKey(id.toInt(), PreKeyRecord(id.toInt(), ECKeyPair(priv.getPublicKey(), priv)))
        pool = pool + id
        var next = id + 1
        if (next > 0xFFFFFFFFL || next == 0L) next = 1
        meta.nextOneTimePreKeyId = next

        if (pool.size > ONE_TIME_POOL_LIMIT) {
            for (old in pool.take(pool.size - ONE_TIME_POOL_LIMIT)) store.removePreKey(old.toInt())
            pool = pool.takeLast(ONE_TIME_POOL_LIMIT)
        }
        meta.oneTimePreKeyIds = pool
        return id to priv.getPublicKey().serialize()
    }

    private fun saveMeta() {
        meta.contacts = contacts.value
        meta.messages = messages.value
        SecureStore.write(StoreKey.meta(currentID.value), json.encodeToString(Meta.serializer(), meta).toByteArray())
    }

    fun myKeyString(): String = synchronized(lock) {
        val opk = nextOneTimePreKeyForBundle()
        saveMeta()
        val payload = BundlePayload(
            registrationId = meta.registrationId, deviceId = 1,
            identityKey = identity.publicKey.serialize(),
            signedPreKeyId = meta.signedPreKeyId, signedPreKey = meta.signedPreKeyPub,
            signedPreKeySignature = meta.signedPreKeySig,
            kyberPreKeyId = meta.kyberPreKeyId, kyberPreKey = meta.kyberPreKeyPub,
            kyberPreKeySignature = meta.kyberPreKeySig,
            oneTimePreKeyId = opk.first, oneTimePreKey = opk.second,
        )
        KEY_PREFIX + Base64.getEncoder().encodeToString(encodeBundle(payload))
    }

    private fun encodeBundle(p: BundlePayload): ByteArray {
        val w = BinaryWriter()
        w.writeByte(BUNDLE_FORMAT)
        w.writeUInt32(p.registrationId)
        w.writeUInt32(p.deviceId)
        w.writeUInt32(p.signedPreKeyId)
        w.writeUInt32(p.kyberPreKeyId)
        w.writeVar(p.identityKey)
        w.writeVar(p.signedPreKey)
        w.writeVar(p.signedPreKeySignature)
        w.writeVar(p.kyberPreKey)
        w.writeVar(p.kyberPreKeySignature)
        val otpId = p.oneTimePreKeyId
        val otp = p.oneTimePreKey
        if (otpId != null && otp != null) {
            w.writeByte(1); w.writeUInt32(otpId); w.writeVar(otp)
        } else {
            w.writeByte(0)
        }
        return w.data
    }

    private fun decodeBundle(data: ByteArray): BundlePayload {
        val r = BinaryReader(data)
        if (r.readByte() != BUNDLE_FORMAT) throw BadKeyStringException()
        val reg = r.readUInt32(); val dev = r.readUInt32()
        val spkId = r.readUInt32(); val kyId = r.readUInt32()
        val ik = r.readVar(); val spk = r.readVar(); val spkSig = r.readVar()
        val ky = r.readVar(); val kySig = r.readVar()
        var otpId: Long? = null; var otp: ByteArray? = null
        if (r.readByte() == 1) { otpId = r.readUInt32(); otp = r.readVar() }
        return BundlePayload(reg, dev, ik, spkId, spk, spkSig, kyId, ky, kySig, otpId, otp)
    }

    fun addContact(keyString: String, displayName: String): Contact = synchronized(lock) {
        val trimmed = keyString.trim()
        val idx = trimmed.indexOf(KEY_PREFIX)
        if (idx < 0) throw BadKeyStringException()
        val b64 = trimmed.substring(idx + KEY_PREFIX.length).takeWhile { !it.isWhitespace() }
        val blob = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: throw BadKeyStringException()
        val peer = runCatching { decodeBundle(blob) }.getOrNull() ?: throw BadKeyStringException()

        val fp = SignalFormat.hex(peer.identityKey)
        if (fp == myFingerprint.value) throw BadKeyStringException()

        val ik = IdentityKey(peer.identityKey)
        val spk = ECPublicKey(peer.signedPreKey)
        val kyber = KEMPublicKey(peer.kyberPreKey)
        val otpId = peer.oneTimePreKeyId
        val otpKey = peer.oneTimePreKey?.let { runCatching { ECPublicKey(it) }.getOrNull() }
        val bundle = if (otpId != null && otpKey != null) {
            PreKeyBundle(
                peer.registrationId.toInt(), peer.deviceId.toInt(),
                otpId.toInt(), otpKey,
                peer.signedPreKeyId.toInt(), spk, peer.signedPreKeySignature, ik,
                peer.kyberPreKeyId.toInt(), kyber, peer.kyberPreKeySignature,
            )
        } else {
            PreKeyBundle(
                peer.registrationId.toInt(), peer.deviceId.toInt(),
                PreKeyBundle.NULL_PRE_KEY_ID, null,
                peer.signedPreKeyId.toInt(), spk, peer.signedPreKeySignature, ik,
                peer.kyberPreKeyId.toInt(), kyber, peer.kyberPreKeySignature,
            )
        }

        val addr = SignalProtocolAddress(fp, 1)
        val myAddr = SignalProtocolAddress(myFingerprint.value, 1)
        SessionBuilder(store, addr, myAddr).process(bundle)

        val name = displayName.ifEmpty { fp.take(8) }
        val list = contacts.value.toMutableList()
        val existing = list.indexOfFirst { it.fingerprint == fp }
        if (existing >= 0) list[existing] = list[existing].copy(displayName = name)
        else list.add(Contact(fingerprint = fp, displayName = name))
        contacts.value = list
        saveMeta()
        Contact(fingerprint = fp, displayName = name)
    }

    fun removeContact(contact: Contact) = synchronized(lock) {
        CachePurge.purgeAll()
        store.removeSessionAndIdentity(contact.fingerprint)
        contacts.value = contacts.value.filter { it.fingerprint != contact.fingerprint }
        messages.value = messages.value - contact.fingerprint
        meta.autoDelete = meta.autoDelete - contact.fingerprint
        meta.purgeDecryptCache(contact.fingerprint)
        saveMeta()
    }

    fun encrypt(text: String, to: Contact): String = synchronized(lock) {
        ensureInitialized()
        val armored = SignalWire.encrypt(text, to.fingerprint, myFingerprint.value, store, AppSettingsStore.resolvedStegoLanguage(), AppSettingsStore.resolvedStegoSmart(), AppSettingsStore.lengthPadding)
        OwnCipherMarker.mark(armored)
        meta.rememberDecrypt(armored, to.fingerprint, text, mine = true)
        append(ChatMessage(text = text, mine = true), to.fingerprint)
        armored
    }

    fun decrypt(armored: String, from: Contact): String = synchronized(lock) {
        ensureInitialized()
        meta.decryptCache[DecryptCacheKey.of(armored)]?.let { hit ->
            if (hit.fingerprint != from.fingerprint) {
                val name = contacts.value.firstOrNull { it.fingerprint == hit.fingerprint }?.displayName
                    ?: hit.fingerprint.take(8)
                throw DecryptedForOtherContactException(name)
            }
            return@synchronized hit.text
        }
        val text = SignalWire.decrypt(armored, from.fingerprint, myFingerprint.value, store)
        meta.rememberDecrypt(armored, from.fingerprint, text)
        append(ChatMessage(text = text, mine = false), from.fingerprint)
        text
    }

    data class CacheHit(val contact: Contact, val text: String, val mine: Boolean)

    fun cachedDecryptHit(armored: String): CacheHit? = synchronized(lock) {
        ensureInitialized()
        val hit = meta.decryptCache[DecryptCacheKey.of(armored)] ?: return null
        val contact = contacts.value.firstOrNull { it.fingerprint == hit.fingerprint }
            ?: Contact(hit.fingerprint, hit.fingerprint.take(8))
        CacheHit(contact, hit.text, hit.mine)
    }

    fun cachedDecrypt(armored: String): Pair<Contact, String>? =
        cachedDecryptHit(armored)?.let { it.contact to it.text }

    private fun append(message: ChatMessage, fingerprint: String) {
        messages.value = messages.value + (fingerprint to ((messages.value[fingerprint] ?: emptyList()) + message))
        purgeExpired(fingerprint)
        saveMeta()
    }

    fun autoDeleteInterval(fingerprint: String): Double? =
        meta.autoDelete[fingerprint]?.takeIf { it > 0 }

    fun setAutoDelete(seconds: Double?, contact: Contact) = synchronized(lock) {
        meta.autoDelete = if (seconds != null && seconds > 0) {
            meta.autoDelete + (contact.fingerprint to seconds)
        } else {
            meta.autoDelete - contact.fingerprint
        }
        purgeExpired(contact.fingerprint)
        saveMeta()
    }

    private fun purgeExpired(fingerprint: String) {
        val secs = meta.autoDelete[fingerprint]?.takeIf { it > 0 } ?: return
        meta.purgeDecryptCache(fingerprint, maxAgeMs = (secs * 1000).toLong())
        val msgs = messages.value[fingerprint] ?: return
        val now = System.currentTimeMillis()
        val kept = msgs.filter { now - it.date < secs * 1000 }
        if (kept.size != msgs.size) messages.value = messages.value + (fingerprint to kept)
    }

    fun purgeExpiredMessages(): Boolean = synchronized(lock) {
        var changed = false
        val now = System.currentTimeMillis()
        var map = messages.value
        val cacheBefore = meta.decryptCache.size
        for ((fp, secs) in meta.autoDelete) {
            if (secs <= 0) continue
            meta.purgeDecryptCache(fp, maxAgeMs = (secs * 1000).toLong())
            val msgs = map[fp] ?: continue
            val kept = msgs.filter { now - it.date < secs * 1000 }
            if (kept.size != msgs.size) { map = map + (fp to kept); changed = true }
        }
        if (meta.decryptCache.size != cacheBefore) changed = true
        if (changed) { messages.value = map; saveMeta() }
        changed
    }

    fun clearChat(contact: Contact) = synchronized(lock) {
        CachePurge.purgeAll()
        messages.value = messages.value - contact.fingerprint
        meta.purgeDecryptCache(contact.fingerprint)
        saveMeta()
    }

    fun wipeAllChats() = synchronized(lock) {
        CachePurge.purgeAll()
        messages.value = emptyMap()
        meta.purgeDecryptCache()
        saveMeta()
    }

    fun wipeContactsAndChats() = synchronized(lock) {
        CachePurge.purgeAll()
        store.removeAllSessionsAndPeerIdentities()
        contacts.value = emptyList()
        messages.value = emptyMap()
        meta.autoDelete = emptyMap()
        meta.purgeDecryptCache()
        saveMeta()
    }

    fun eraseEverything() = synchronized(lock) {
        CachePurge.purgeAll()
        SecureStore.deleteAll()
        initialized = false
        ensureInitialized()
    }
}
