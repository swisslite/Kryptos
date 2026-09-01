package com.kryptos.android.signal

import com.kryptos.android.core.ArchivedContact
import com.kryptos.android.core.ArchivedProfile
import com.kryptos.android.core.ArchivedRetired
import com.kryptos.android.AppLanguage
import com.kryptos.android.R
import com.kryptos.android.core.BinaryReader
import com.kryptos.android.core.BinaryWriter
import com.kryptos.android.core.CachePurge
import com.kryptos.android.core.KeyText
import com.kryptos.android.security.ClipboardGuard
import com.kryptos.android.core.wipingBytes
import com.kryptos.android.store.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
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
    private val KEY_PREFIX = KeyText.PREFIX
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
    val autoDelete = MutableStateFlow<Map<String, Double>>(emptyMap())
    val pinned = MutableStateFlow<Set<String>>(emptySet())
    val myFingerprint = MutableStateFlow("")
    val mySafetyNumber = MutableStateFlow("")
    val unavailableProfiles = MutableStateFlow<Set<String>>(emptySet())

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

    val isReady: Boolean get() = initialized

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
            val localized = index.profiles.map(::relocalizedDefaultName)
            profiles.value = localized
            val preferred = if (localized.any { it.id == index.currentID }) index.currentID else localized[0].id
            val order = listOf(preferred) + localized.map { it.id }.filter { it != preferred }
            var failure: Throwable? = null
            for (id in order) {
                val loaded = try {
                    readProfile(id)
                } catch (t: Throwable) {
                    failure = t
                    markUnavailable(id)
                    continue
                }
                adopt(id, loaded)
                clearUnavailable(id)
                if (localized != index.profiles && id == preferred) persistIndex()
                initialized = true
                return
            }
            throw IllegalStateException("no Kryptos profile could be opened", failure)
        }
    }

    private fun markUnavailable(id: String) {
        unavailableProfiles.value = unavailableProfiles.value + id
    }

    private fun clearUnavailable(id: String) {
        if (id in unavailableProfiles.value) unavailableProfiles.value = unavailableProfiles.value - id
    }

    private fun defaultProfileName(n: Int): String =
        AppLanguage.wrap(SecureStore.appContext()).getString(R.string.profile_n, n)

    private val autoNamePrefixes = listOf("Profile ", "Профиль ", "Profil ", "个人资料 ", "نمایه ")

    private fun relocalizedDefaultName(profile: Profile): Profile {
        for (prefix in autoNamePrefixes) {
            if (!profile.name.startsWith(prefix)) continue
            val n = profile.name.substring(prefix.length).toIntOrNull() ?: continue
            val localized = defaultProfileName(n)
            return if (localized == profile.name) profile else profile.copy(name = localized)
        }
        return profile
    }

    private fun loadIndex(): ProfilesIndex =
        SecureStore.readStrict(StoreKey.index)?.let {
            try {
                json.decodeFromString<ProfilesIndex>(String(it, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalStateException("SignalService: profiles index exists but cannot be parsed", e)
            }
        } ?: ProfilesIndex(emptyList(), "")

    @OptIn(ExperimentalSerializationApi::class)
    private fun saveIndex(index: ProfilesIndex) {
        writeWiped(StoreKey.index, wipingBytes { json.encodeToStream(ProfilesIndex.serializer(), index, it) })
    }

    private fun writeWiped(name: String, data: ByteArray) {
        try {
            SecureStore.write(name, data)
        } finally {
            data.fill(0)
        }
    }

    private fun persistIndex() = saveIndex(ProfilesIndex(profiles.value, currentID.value))

    fun switchTo(id: String): Boolean = synchronized(lock) {
        if (profiles.value.none { it.id == id }) return false
        if (initialized && currentID.value == id) return true
        val loaded = try {
            readProfile(id)
        } catch (t: Throwable) {
            markUnavailable(id)
            return false
        }
        CachePurge.purgeAll()
        adopt(id, loaded)
        clearUnavailable(id)
        persistIndex()
        true
    }

    fun createProfile(name: String): Profile? = synchronized(lock) {
        val trimmed = name.trim()
        val profile = Profile(name = trimmed.ifEmpty { defaultProfileName(profiles.value.size + 1) })
        val loaded = try {
            readProfile(profile.id)
        } catch (t: Throwable) {
            runCatching { wipeStorage(profile.id) }
            return null
        }
        CachePurge.purgeAll()
        profiles.value = profiles.value + profile
        adopt(profile.id, loaded)
        persistIndex()
        profile
    }

    fun renameProfile(id: String, name: String) = synchronized(lock) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val list = profiles.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0 || list[idx].name == trimmed) return
        profiles.value = list.mapIndexed { i, p -> if (i == idx) p.copy(name = trimmed) else p }
        persistIndex()
    }

    fun renameContact(contact: Contact, name: String) = synchronized(lock) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !initialized) return
        val list = contacts.value
        val idx = list.indexOfFirst { it.fingerprint == contact.fingerprint }
        if (idx < 0 || list[idx].displayName == trimmed) return
        contacts.value = list.mapIndexed { i, c -> if (i == idx) c.copy(displayName = trimmed) else c }
        saveMeta()
        CachePurge.purgeDecrypted()
    }

    fun deleteProfile(id: String): Boolean = synchronized(lock) {
        CachePurge.purgeAll()
        wipeStorage(id)
        clearUnavailable(id)
        var list = profiles.value.filter { it.id != id }
        if (list.isEmpty()) list = listOf(Profile(name = defaultProfileName(1)))
        profiles.value = list
        val target = if (list.any { it.id == currentID.value } && currentID.value != id) currentID.value else list[0].id
        val order = listOf(target) + list.map { it.id }.filter { it != target }
        for (next in order) {
            val loaded = try {
                readProfile(next)
            } catch (t: Throwable) {
                markUnavailable(next)
                continue
            }
            adopt(next, loaded)
            clearUnavailable(next)
            persistIndex()
            return true
        }
        initialized = false
        persistIndex()
        false
    }

    fun regenerateCurrentIdentity(): Boolean = synchronized(lock) {
        val id = currentID.value
        CachePurge.purgeAll()
        wipeStorage(id)
        val loaded = try {
            readProfile(id)
        } catch (t: Throwable) {
            markUnavailable(id)
            initialized = false
            return false
        }
        adopt(id, loaded)
        clearUnavailable(id)
        true
    }

    private fun wipeStorage(id: String) {
        SecureStore.delete(StoreKey.identity(id))
        SecureStore.delete(StoreKey.meta(id))
        SecureStore.delete(StoreKey.store(id))
        AppSettingsStore.clearKeyboardContact(id)
        OwnCipherMarker.clear()
    }

    private fun rescheduleExpiry() {
        MessageExpiry.schedule(MessageExpiry.nextDueAt(messages.value, autoDelete.value))
    }

    private fun dropFromClipboard(texts: Collection<String>) {
        if (texts.isEmpty()) return
        runCatching { ClipboardGuard.clearIfHolds(SecureStore.appContext(), texts) }
    }

    private class Loaded(
        val identity: IdentityKeyPair,
        val fingerprint: String,
        val safetyNumber: String,
        val store: KryptosSignalStore,
        val meta: Meta,
        val digest: ByteArray?,
    )

    private fun readProfile(id: String): Loaded {
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

        val metaBytes = SecureStore.readStrict(StoreKey.meta(id))
        val loadedMeta = metaBytes?.let {
            try {
                json.decodeFromString<Meta>(String(it, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalStateException("SignalService: meta '$id' exists but cannot be parsed", e)
            }
        }
        val regId = loadedMeta?.registrationId ?: (1L + rng.nextInt(0x3FFF))
        val store_ = KryptosSignalStore(identity_, regId.toInt(), StoreKey.store(id))
        val meta_ = store_.batch {
            val m = loadedMeta ?: provisionInitial(regId, identity_, store_)
            maintainPreKeys(m, identity_, store_)
            m
        }
        val fingerprint = SignalFormat.hex(identity_.publicKey.serialize())
        return Loaded(
            identity = identity_,
            fingerprint = fingerprint,
            safetyNumber = SignalFormat.safetyNumber(fingerprint),
            store = store_,
            meta = meta_,
            digest = metaBytes?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it) },
        )
    }

    private fun adopt(id: String, loaded: Loaded) {
        identity = loaded.identity
        store = loaded.store
        meta = loaded.meta
        lastMetaDigest = loaded.digest
        currentID.value = id
        myFingerprint.value = loaded.fingerprint
        mySafetyNumber.value = loaded.safetyNumber
        autoDelete.value = loaded.meta.autoDelete
        pinned.value = loaded.meta.pinned.toSet()
        contacts.value = loaded.meta.contacts
        messages.value = loaded.meta.messages
        saveMeta()
        purgeExpiredMessages()
    }

    private fun provisionInitial(regId: Long, identity: IdentityKeyPair, store: KryptosSignalStore): Meta {
        val gen = generateSignedAndKyber(signedId = 1, kyberId = 2, identity = identity, store = store)
        return Meta(
            registrationId = regId,
            signedPreKeyId = gen.signedId, signedPreKeyPub = gen.signedPub, signedPreKeySig = gen.signedSig,
            kyberPreKeyId = gen.kyberId, kyberPreKeyPub = gen.kyberPub, kyberPreKeySig = gen.kyberSig,
            prekeyCreatedAt = System.currentTimeMillis(),
            nextSignedPreKeyId = 3, nextKyberPreKeyId = 4, nextOneTimePreKeyId = 1,
        )
    }

    private fun maintainPreKeys(meta: Meta, identity: IdentityKeyPair, store: KryptosSignalStore) {
        if (meta.prekeyCreatedAt == null) meta.prekeyCreatedAt = System.currentTimeMillis()

        meta.prekeyCreatedAt?.let { created ->
            if (System.currentTimeMillis() - created > ROTATION_INTERVAL_MS) {
                rotateSignedAndKyber(meta, identity, store)
            }
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

    private fun rotateSignedAndKyber(meta: Meta, identity: IdentityKeyPair, store: KryptosSignalStore) {
        meta.retiredPreKeyGens = meta.retiredPreKeyGens +
            RetiredPreKeyGen(meta.signedPreKeyId, meta.kyberPreKeyId, System.currentTimeMillis())

        val signedId = meta.nextSignedPreKeyId
        val kyberId = meta.nextKyberPreKeyId
        val gen = generateSignedAndKyber(signedId, kyberId, identity, store)
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

    private fun generateSignedAndKyber(
        signedId: Long,
        kyberId: Long,
        identity: IdentityKeyPair,
        store: KryptosSignalStore,
    ): GenKeys {
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
        if (next > Int.MAX_VALUE.toLong() || next <= 0L) next = 1
        meta.nextOneTimePreKeyId = next

        if (pool.size > ONE_TIME_POOL_LIMIT) {
            for (old in pool.take(pool.size - ONE_TIME_POOL_LIMIT)) store.removePreKey(old.toInt())
            pool = pool.takeLast(ONE_TIME_POOL_LIMIT)
        }
        meta.oneTimePreKeyIds = pool
        return id to priv.getPublicKey().serialize()
    }

    private var lastMetaDigest: ByteArray? = null

    @OptIn(ExperimentalSerializationApi::class)
    private fun saveMeta() {
        meta.contacts = contacts.value
        meta.messages = messages.value
        val encoded = wipingBytes { json.encodeToStream(Meta.serializer(), meta, it) }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
        if (lastMetaDigest?.contentEquals(digest) == true) {
            encoded.fill(0)
            return
        }
        writeWiped(StoreKey.meta(currentID.value), encoded)
        lastMetaDigest = digest
    }

    class KeyShare(val payload: ByteArray) {
        val text: String get() = KEY_PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    fun myKeyShare(): KeyShare = synchronized(lock) {
        ensureInitialized()
        val opk = store.batch { nextOneTimePreKeyForBundle() }
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
        KeyShare(encodeBundle(payload))
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

    private fun parseKeyPayload(blob: ByteArray): BundlePayload? =
        runCatching { decodeBundle(blob) }.getOrNull()

    private fun parseKeyText(keyString: String): BundlePayload? =
        KeyText.blobs(keyString).firstNotNullOfOrNull { parseKeyPayload(it) }

    fun addContact(keyString: String, displayName: String): Contact =
        addPeer(parseKeyText(keyString) ?: throw BadKeyStringException(), displayName)

    fun addContactFromScan(raw: ByteArray, displayName: String): Contact {
        val peer = parseKeyPayload(raw)
            ?: parseKeyText(String(raw, Charsets.ISO_8859_1))
            ?: throw BadKeyStringException()
        return addPeer(peer, displayName)
    }

    private fun addPeer(peer: BundlePayload, displayName: String): Contact = synchronized(lock) {
        ready()
        val fp = SignalFormat.hex(peer.identityKey)
        if (fp == myFingerprint.value) throw OwnKeyException()

        val ik = IdentityKey(peer.identityKey)
        val spk = ECPublicKey(peer.signedPreKey)
        val kyber = KEMPublicKey(peer.kyberPreKey)
        val otpId = peer.oneTimePreKeyId
        val otp = peer.oneTimePreKey
        val otpKey = otp?.let { runCatching { ECPublicKey(it) }.getOrNull() }
        val otpMark = if (otpId != null && otp != null && otpKey != null) {
            PreKeyMark.of(peer.identityKey, otp)
        } else {
            null
        }
        val spent = otpMark != null && otpMark in meta.usedPreKeys

        val addr = SignalProtocolAddress(fp, 1)
        val myAddr = SignalProtocolAddress(myFingerprint.value, 1)
        if (!spent || !hasUsableSession(addr)) {
            val useOneTime = otpMark != null && !spent
            if (useOneTime) {
                meta.rememberUsedPreKey(otpMark!!)
                saveMeta()
            }
            val bundle = if (useOneTime) {
                PreKeyBundle(
                    peer.registrationId.toInt(), peer.deviceId.toInt(),
                    otpId!!.toInt(), otpKey!!,
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
            store.batch { SessionBuilder(store, addr, myAddr).process(bundle) }
        }

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
        if (!initialized) return@synchronized
        CachePurge.purgeAll()
        store.removeSessionAndIdentity(contact.fingerprint)
        val erased = messages.value[contact.fingerprint].orEmpty().map { it.text }
        contacts.value = contacts.value.filter { it.fingerprint != contact.fingerprint }
        messages.value = messages.value - contact.fingerprint
        meta.autoDelete = meta.autoDelete - contact.fingerprint
        autoDelete.value = meta.autoDelete
        meta.pinned = meta.pinned - contact.fingerprint
        pinned.value = meta.pinned.toSet()
        meta.purgeDecryptCache(contact.fingerprint)
        saveMeta()
        AppSettingsStore.clearKeyboardContact(currentID.value, contact.fingerprint)
        OwnCipherMarker.clear()
        dropFromClipboard(erased)
        rescheduleExpiry()
    }

    private fun hasUsableSession(address: SignalProtocolAddress): Boolean =
        runCatching { store.loadSession(address)?.hasSenderChain(0.0) == true }.getOrDefault(false)

    private fun requireSession(fingerprint: String) {
        if (!store.containsSession(SignalProtocolAddress(fingerprint, 1))) {
            throw NoSessionForContactException()
        }
    }

    fun ready(): Boolean = try {
        ensureInitialized()
        true
    } catch (e: IllegalStateException) {
        throw StorageUnavailableException(e)
    }

    fun encrypt(text: String, to: Contact): SignalWire.Sealed = synchronized(lock) {
        ready()
        requireSession(to.fingerprint)
        val sealed = store.batch {
            SignalWire.encrypt(text, to.fingerprint, myFingerprint.value, store, AppSettingsStore.resolvedStegoLanguage(), AppSettingsStore.resolvedStegoMode(), AppSettingsStore.lengthPadding)
        }
        OwnCipherMarker.mark(sealed.armored)
        meta.rememberDecrypt(sealed.armored, to.fingerprint, text, mine = true)
        append(ChatMessage(text = text, mine = true), to.fingerprint)
        sealed
    }

    fun decrypt(armored: String, from: Contact): String = synchronized(lock) {
        ready()
        meta.decryptCache[DecryptCacheKey.of(armored)]?.let { hit ->
            if (hit.fingerprint != from.fingerprint) {
                val name = contacts.value.firstOrNull { it.fingerprint == hit.fingerprint }?.displayName
                    ?: hit.fingerprint.take(8)
                throw DecryptedForOtherContactException(name)
            }
            return@synchronized hit.text
        }
        val text = try {
            store.batch { SignalWire.decrypt(armored, from.fingerprint, myFingerprint.value, store) }
        } catch (e: org.signal.libsignal.protocol.NoSessionException) {
            throw NoSessionForContactException()
        } catch (e: org.signal.libsignal.protocol.InvalidKeyIdException) {
            throw PreKeyUnavailableException()
        }
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

    private fun append(message: ChatMessage, fingerprint: String) {
        messages.value = messages.value + (fingerprint to ((messages.value[fingerprint] ?: emptyList()) + message))
        if (!purgeExpiredMessages()) saveMeta()
    }

    fun setPinned(contact: Contact, value: Boolean) = synchronized(lock) {
        if (!initialized) return@synchronized
        val current = meta.pinned
        if (value == (contact.fingerprint in current)) return@synchronized
        val next = if (value) current + contact.fingerprint else current - contact.fingerprint
        meta.pinned = next
        pinned.value = next.toSet()
        saveMeta()
    }

    fun setAutoDelete(seconds: Double?, contact: Contact) = synchronized(lock) {
        if (!initialized) return@synchronized
        meta.autoDelete = if (seconds != null && seconds > 0) {
            meta.autoDelete + (contact.fingerprint to seconds)
        } else {
            meta.autoDelete - contact.fingerprint
        }
        autoDelete.value = meta.autoDelete
        purgeExpiredMessages()
        saveMeta()
    }

    fun purgeExpiredMessages(): Boolean = synchronized(lock) {
        if (!initialized) return@synchronized false
        val before = messages.value
        meta.messages = before
        val changed = meta.purgeExpired()
        if (changed) {
            val after = meta.messages
            messages.value = after
            saveMeta()
            CachePurge.purgeDecrypted()
            dropFromClipboard(expiredTexts(before, after))
        }
        rescheduleExpiry()
        changed
    }

    private fun expiredTexts(
        before: Map<String, List<ChatMessage>>,
        after: Map<String, List<ChatMessage>>,
    ): List<String> {
        val gone = ArrayList<String>()
        for ((fingerprint, list) in before) {
            val kept = after[fingerprint].orEmpty().mapTo(HashSet()) { it.id }
            for (message in list) if (message.id !in kept) gone.add(message.text)
        }
        return gone
    }

    fun deleteMessage(contact: Contact, messageId: String) = synchronized(lock) {
        if (!initialized) return
        val map = messages.value
        val list = map[contact.fingerprint] ?: return
        val target = list.firstOrNull { it.id == messageId } ?: return
        val next = list.filterNot { it.id == messageId }
        messages.value = if (next.isEmpty()) map - contact.fingerprint else map + (contact.fingerprint to next)
        meta.purgeDecrypted(contact.fingerprint, target.text)
        saveMeta()
        CachePurge.purgeDecrypted()
        dropFromClipboard(listOf(target.text))
        rescheduleExpiry()
    }

    fun clearChat(contact: Contact) = synchronized(lock) {
        if (!initialized) return@synchronized
        val erased = messages.value[contact.fingerprint].orEmpty().map { it.text }
        messages.value = messages.value - contact.fingerprint
        meta.purgeDecryptCache(contact.fingerprint)
        saveMeta()
        CachePurge.purgeDecrypted()
        dropFromClipboard(erased)
        rescheduleExpiry()
    }

    fun wipeAllChats() = synchronized(lock) {
        if (!initialized) return@synchronized
        val erased = messages.value.values.flatten().map { it.text }
        messages.value = emptyMap()
        meta.purgeDecryptCache()
        saveMeta()
        CachePurge.purgeDecrypted()
        OwnCipherMarker.clear()
        dropFromClipboard(erased)
        rescheduleExpiry()
    }

    fun wipeContactsAndChats() = synchronized(lock) {
        if (!initialized) return@synchronized
        CachePurge.purgeAll()
        store.removeAllSessionsAndPeerIdentities()
        val erased = messages.value.values.flatten().map { it.text }
        contacts.value = emptyList()
        messages.value = emptyMap()
        meta.autoDelete = emptyMap()
        autoDelete.value = emptyMap()
        meta.pinned = emptyList()
        pinned.value = emptySet()
        meta.purgeDecryptCache()
        saveMeta()
        AppSettingsStore.clearKeyboardContact(currentID.value)
        OwnCipherMarker.clear()
        dropFromClipboard(erased)
        rescheduleExpiry()
    }

    fun archivedProfiles(): List<ArchivedProfile>? = synchronized(lock) {
        ensureInitialized()
        saveMeta()
        val out = ArrayList<ArchivedProfile>()
        for (profile in profiles.value) {
            val identityBytes = runCatching { SecureStore.readStrict(StoreKey.identity(profile.id)) }
                .getOrNull() ?: return@synchronized null
            val metaBytes = runCatching { SecureStore.readStrict(StoreKey.meta(profile.id)) }
                .getOrNull() ?: return@synchronized null
            val m = runCatching { json.decodeFromString<Meta>(String(metaBytes, Charsets.UTF_8)) }
                .getOrNull() ?: return@synchronized null
            val store = KryptosSignalStore.exportArchive(StoreKey.store(profile.id))
                ?: return@synchronized null
            out.add(
                ArchivedProfile(
                    id = profile.id,
                    name = profile.name,
                    identity = Base64.getEncoder().encodeToString(identityBytes),
                    registrationId = m.registrationId,
                    signedPreKeyId = m.signedPreKeyId,
                    signedPreKeyPub = Base64.getEncoder().encodeToString(m.signedPreKeyPub),
                    signedPreKeySig = Base64.getEncoder().encodeToString(m.signedPreKeySig),
                    kyberPreKeyId = m.kyberPreKeyId,
                    kyberPreKeyPub = Base64.getEncoder().encodeToString(m.kyberPreKeyPub),
                    kyberPreKeySig = Base64.getEncoder().encodeToString(m.kyberPreKeySig),
                    prekeyCreatedAt = m.prekeyCreatedAt,
                    nextSignedPreKeyId = m.nextSignedPreKeyId,
                    nextKyberPreKeyId = m.nextKyberPreKeyId,
                    nextOneTimePreKeyId = m.nextOneTimePreKeyId,
                    oneTimePreKeyIds = m.oneTimePreKeyIds,
                    retired = m.retiredPreKeyGens.map {
                        ArchivedRetired(it.signedPreKeyId, it.kyberPreKeyId, it.retiredAt)
                    },
                    autoDelete = m.autoDelete,
                    pinned = m.pinned,
                    usedPreKeys = m.usedPreKeys,
                    contacts = m.contacts.map { ArchivedContact(it.fingerprint, it.displayName) },
                    preKeys = store["preKeys"].orEmpty(),
                    signedPreKeys = store["signedPreKeys"].orEmpty(),
                    kyberPreKeys = store["kyberPreKeys"].orEmpty(),
                    sessions = store["sessions"].orEmpty(),
                    identities = store["identities"].orEmpty(),
                )
            )
        }
        out
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun restoreProfiles(list: List<ArchivedProfile>): Boolean = synchronized(lock) {
        CachePurge.purgeAll()
        val previous = profiles.value.map { it.id }
        val restored = ArrayList<Profile>()
        val seen = HashSet<String>()
        for (entry in list) {
            if (!seen.add(entry.id)) continue
            if (runCatching { java.util.UUID.fromString(entry.id) }.isFailure) continue
            val identityBytes = runCatching { Base64.getDecoder().decode(entry.identity) }.getOrNull() ?: continue
            if (runCatching { IdentityKeyPair(identityBytes) }.isFailure) continue
            val signedPub = runCatching { Base64.getDecoder().decode(entry.signedPreKeyPub) }.getOrNull() ?: continue
            val signedSig = runCatching { Base64.getDecoder().decode(entry.signedPreKeySig) }.getOrNull() ?: continue
            val kyberPub = runCatching { Base64.getDecoder().decode(entry.kyberPreKeyPub) }.getOrNull() ?: continue
            val kyberSig = runCatching { Base64.getDecoder().decode(entry.kyberPreKeySig) }.getOrNull() ?: continue

            val restoredMeta = Meta(
                registrationId = entry.registrationId,
                signedPreKeyId = entry.signedPreKeyId,
                signedPreKeyPub = signedPub, signedPreKeySig = signedSig,
                kyberPreKeyId = entry.kyberPreKeyId,
                kyberPreKeyPub = kyberPub, kyberPreKeySig = kyberSig,
                contacts = entry.contacts.map { Contact(it.fingerprint, it.displayName) },
                messages = emptyMap(),
                prekeyCreatedAt = entry.prekeyCreatedAt,
                retiredPreKeyGens = entry.retired.map {
                    RetiredPreKeyGen(it.signedPreKeyId, it.kyberPreKeyId, it.retiredAt)
                },
                nextSignedPreKeyId = entry.nextSignedPreKeyId,
                nextKyberPreKeyId = entry.nextKyberPreKeyId,
                nextOneTimePreKeyId = entry.nextOneTimePreKeyId,
                oneTimePreKeyIds = entry.oneTimePreKeyIds,
                autoDelete = entry.autoDelete,
                pinned = entry.pinned.filter { fp -> entry.contacts.any { it.fingerprint == fp } },
                decryptCache = emptyMap(),
                usedPreKeys = entry.usedPreKeys,
            )
            val ok = runCatching {
                writeWiped(StoreKey.identity(entry.id), identityBytes)
                writeWiped(
                    StoreKey.meta(entry.id),
                    wipingBytes { json.encodeToStream(Meta.serializer(), restoredMeta, it) },
                )
                KryptosSignalStore.writeArchive(
                    StoreKey.store(entry.id),
                    mapOf(
                        "preKeys" to entry.preKeys,
                        "signedPreKeys" to entry.signedPreKeys,
                        "kyberPreKeys" to entry.kyberPreKeys,
                        "sessions" to entry.sessions,
                        "identities" to entry.identities,
                    ),
                )
            }.isSuccess
            if (ok) {
                restored.add(relocalizedDefaultName(Profile(id = entry.id, name = entry.name)))
            } else {
                runCatching { wipeStorage(entry.id) }
            }
        }

        if (restored.isEmpty()) return@synchronized false
        val keep = restored.map { it.id }.toSet()
        for (old in previous) if (old !in keep) wipeStorage(old)
        unavailableProfiles.value = emptySet()
        profiles.value = restored
        val loaded = try {
            readProfile(restored[0].id)
        } catch (t: Throwable) {
            currentID.value = restored[0].id
            markUnavailable(restored[0].id)
            persistIndex()
            initialized = false
            return@synchronized false
        }
        adopt(restored[0].id, loaded)
        persistIndex()
        initialized = true
        true
    }

    fun eraseStorage() = synchronized(lock) {
        CachePurge.purgeAll()
        MessageExpiry.schedule(null)
        initialized = false
        lastMetaDigest = null
        unavailableProfiles.value = emptySet()
        SecureStore.deleteAll()
        SecureStore.prefs().edit().clear().commit()
        AppSettingsStore.invalidateCaches()
        contacts.value = emptyList()
        messages.value = emptyMap()
        autoDelete.value = emptyMap()
        pinned.value = emptySet()
        profiles.value = emptyList()
        currentID.value = ""
        myFingerprint.value = ""
        mySafetyNumber.value = ""
    }

    fun eraseAndReinit(between: () -> Unit) = synchronized(lock) {
        eraseStorage()
        runCatching { between() }
        runCatching { ensureInitialized() }
    }
}
