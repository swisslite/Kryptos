package com.kryptos.android.pgp

import com.kryptos.android.AppLanguage
import com.kryptos.android.R
import com.kryptos.android.core.wipingBytes
import com.kryptos.android.store.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.pgpainless.PGPainless
import org.pgpainless.algorithm.AlgorithmSuite
import org.pgpainless.algorithm.DocumentSignatureType
import org.pgpainless.algorithm.Feature
import org.pgpainless.algorithm.PublicKeyAlgorithm
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.encryption_signing.SigningOptions
import org.pgpainless.key.OpenPgpFingerprint
import org.pgpainless.key.generation.type.rsa.RsaLength
import org.pgpainless.key.protection.SecretKeyRingProtector
import org.pgpainless.policy.Policy
import org.pgpainless.util.ArmorUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@Serializable
data class PgpIdentity(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var email: String,
    var fingerprint: String,
    var algo: String,
    var createdAt: Long,
    var publicKey: String = "",
) {
    val userId: String
        get() {
            val n = name.trim().ifEmpty { "Kryptos" }
            val e = email.trim()
            return if (e.isEmpty()) n else "$n <$e>"
        }
}

@Serializable
data class PgpRecipient(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var publicKey: String,
    var fingerprint: String = "",
)

enum class PgpAlgo(val label: String) {
    CURVE25519("Curve25519"),
    RSA3072("RSA 3072"),
    RSA4096("RSA 4096"),
}

enum class PgpVerification { VERIFIED, UNVERIFIED }

class PgpDecryption(val text: String, val verification: PgpVerification, val signer: String?)

class PgpException(val res: Int) : Exception("pgp:$res")

@Serializable
private data class PgpIndex(var identities: List<PgpIdentity> = emptyList(), var currentID: String = "")

object PgpService {
    private const val INDEX_KEY = "pgp.index"
    private const val RECIPIENTS_KEY = "pgp.recipients"
    private const val MAX_PLAINTEXT_BYTES = 8L * 1024 * 1024
    private const val MAX_ARMORED_CHARS = 8 * 1024 * 1024
    private fun secretKeyName(id: String) = "pgp.secret.$id"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    internal val api = PGPainless(
        Policy().copy()
            .withMessageEncryptionAlgorithmPolicy(
                Policy.MessageEncryptionMechanismPolicy.rfc4880(
                    Policy.SymmetricKeyAlgorithmPolicy.symmetricKeyEncryptionPolicy2022(),
                ),
            )
            .withKeyGenerationAlgorithmSuite(
                AlgorithmSuite(
                    AlgorithmSuite.defaultSymmetricKeyAlgorithms,
                    AlgorithmSuite.defaultHashAlgorithms,
                    AlgorithmSuite.defaultCompressionAlgorithms,
                    null,
                    listOf(Feature.MODIFICATION_DETECTION),
                ),
            )
            .build(),
    )

    val identities = MutableStateFlow<List<PgpIdentity>>(emptyList())
    val currentID = MutableStateFlow("")
    val recipients = MutableStateFlow<List<PgpRecipient>>(emptyList())
    val busy = MutableStateFlow(false)

    val currentIdentity: PgpIdentity? get() = identities.value.firstOrNull { it.id == currentID.value }

    @Volatile private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            loadRecipients()
            val index = SecureStore.readStrict(INDEX_KEY)?.let {
                try {
                    json.decodeFromString<PgpIndex>(String(it, Charsets.UTF_8))
                } catch (e: Exception) {
                    throw IllegalStateException("PgpService: index exists but cannot be parsed", e)
                }
            } ?: PgpIndex()
            identities.value = index.identities
            currentID.value = if (index.identities.any { it.id == index.currentID }) index.currentID
            else index.identities.firstOrNull()?.id ?: ""
            if (identities.value.isEmpty()) {
                generateBlocking(name = defaultKeyName(), email = "", algo = PgpAlgo.CURVE25519)
                if (identities.value.isEmpty()) return
            }
            initialized = true
        }
    }

    private fun ready() {
        try {
            ensureInitialized()
        } catch (e: Exception) {
            throw PgpException(R.string.storage_unavailable)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun persistIndex() {
        val index = PgpIndex(identities.value, currentID.value)
        writeWiped(INDEX_KEY, wipingBytes { json.encodeToStream(PgpIndex.serializer(), index, it) })
    }

    private fun writeWiped(name: String, data: ByteArray) {
        try {
            SecureStore.write(name, data)
        } finally {
            data.fill(0)
        }
    }

    private fun loadRecipients() {
        ringCacheKey = null
        recipients.value = SecureStore.readStrict(RECIPIENTS_KEY)?.let {
            try {
                json.decodeFromString<List<PgpRecipient>>(String(it, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalStateException("PgpService: recipients exist but cannot be parsed", e)
            }
        } ?: emptyList()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun saveRecipients() {
        ringCacheKey = null
        val list = recipients.value
        writeWiped(
            RECIPIENTS_KEY,
            wipingBytes { json.encodeToStream(ListSerializer(PgpRecipient.serializer()), list, it) },
        )
    }

    private fun secretRing(id: String): OpenPGPKey? {
        val raw = SecureStore.readStrict(secretKeyName(id)) ?: return null
        return try {
            api.readKey().parseKey(raw)
        } catch (e: Exception) {
            throw PgpException(R.string.pgp_key_unreadable)
        } finally {
            raw.fill(0)
        }
    }

    internal fun generateRing(userId: String, algo: PgpAlgo): OpenPGPKey = when (algo) {
        PgpAlgo.CURVE25519 -> api.generateKey().modernKeyRing(userId)
        PgpAlgo.RSA3072 -> api.generateKey().simpleRsaKeyRing(userId, RsaLength._3072)
        PgpAlgo.RSA4096 -> api.generateKey().simpleRsaKeyRing(userId, RsaLength._4096)
    }

    private fun readCertificate(armored: String): OpenPGPCertificate? =
        runCatching { api.readKey().parseCertificate(armored) }.getOrNull()

    private fun readKey(armored: String): OpenPGPKey? =
        runCatching { api.readKey().parseKey(armored) }.getOrNull()

    private fun prettyFingerprint(fp: OpenPgpFingerprint): String =
        fp.toString().uppercase().chunked(4).joinToString(" ")

    private fun defaultKeyName(): String =
        AppLanguage.wrap(SecureStore.appContext()).getString(R.string.my_key)

    fun generateBlocking(name: String, email: String, algo: PgpAlgo): PgpIdentity = synchronized(lock) {
        busy.value = true
        try {
            val ident = PgpIdentity(name = name, email = email, fingerprint = "", algo = algo.label, createdAt = System.currentTimeMillis())
            val ring = generateRing(ident.userId, algo)
            val publicArmored = ArmorUtils.toAsciiArmoredString(ring.toCertificate().encoded)
            val secretArmored = ArmorUtils.toAsciiArmoredString(ring.encoded)
            val done = ident.copy(
                fingerprint = prettyFingerprint(OpenPgpFingerprint.of(ring)),
                publicKey = publicArmored,
            )
            val previousIdentities = identities.value
            val previousCurrent = currentID.value
            writeWiped(secretKeyName(done.id), secretArmored.toByteArray())
            identities.value = previousIdentities + done
            currentID.value = done.id
            try {
                persistIndex()
            } catch (t: Throwable) {
                identities.value = previousIdentities
                currentID.value = previousCurrent
                runCatching { SecureStore.delete(secretKeyName(done.id)) }
                throw t
            }
            done
        } finally {
            busy.value = false
        }
    }

    fun switchTo(id: String): Unit = synchronized(lock) {
        if (identities.value.none { it.id == id }) return
        currentID.value = id
        persistIndex()
    }

    fun deleteIdentity(id: String): Unit = synchronized(lock) {
        if (identities.value.none { it.id == id }) return
        val previousIdentities = identities.value
        val previousCurrent = currentID.value
        val remaining = previousIdentities.filter { it.id != id }
        identities.value = remaining
        if (currentID.value == id) currentID.value = remaining.firstOrNull()?.id ?: ""
        try {
            persistIndex()
        } catch (t: Throwable) {
            identities.value = previousIdentities
            currentID.value = previousCurrent
            throw t
        }
        SecureStore.delete(secretKeyName(id))
        if (remaining.isEmpty()) generateBlocking(name = defaultKeyName(), email = "", algo = PgpAlgo.CURVE25519)
    }

    fun addRecipient(name: String, armoredKey: String) = synchronized(lock) {
        if (armoredKey.length > MAX_ARMORED_CHARS) throw PgpException(R.string.pgp_too_large)
        ready()
        val ring = readCertificate(armoredKey) ?: throw PgpException(R.string.pgp_invalid_key)
        val fp = prettyFingerprint(OpenPgpFingerprint.of(ring))
        val list = recipients.value.toMutableList()
        val idx = list.indexOfFirst { it.fingerprint == fp && fp.isNotEmpty() }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = name.ifEmpty { list[idx].name }, publicKey = armoredKey)
        } else {
            list.add(PgpRecipient(name = name.ifEmpty { "Contact" }, publicKey = armoredKey, fingerprint = fp))
        }
        recipients.value = list
        saveRecipients()
    }

    fun removeRecipient(recipient: PgpRecipient) = synchronized(lock) {
        recipients.value = recipients.value.filter { it.id != recipient.id }
        saveRecipients()
    }

    private var ringCacheKey: List<PgpRecipient>? = null
    private var ringCache: List<Pair<PgpRecipient, OpenPGPCertificate>> = emptyList()

    private fun recipientRings(): List<Pair<PgpRecipient, OpenPGPCertificate>> {
        val current = recipients.value
        if (ringCacheKey === current) return ringCache
        val built = current.mapNotNull { recipient ->
            readCertificate(recipient.publicKey)?.let { recipient to it }
        }
        ringCacheKey = current
        ringCache = built
        return built
    }

    fun encrypt(text: String, to: PgpRecipient): String = synchronized(lock) {
        ready()
        val secret = secretRing(currentID.value) ?: throw PgpException(R.string.pgp_no_key)
        val recipientRing = readCertificate(to.publicKey) ?: throw PgpException(R.string.pgp_invalid_key)
        seal(text, secret, recipientRing)
    }

    internal fun seal(text: String, secret: OpenPGPKey, recipient: OpenPGPCertificate): String {
        val out = ByteArrayOutputStream()
        val encryptionOptions = EncryptionOptions.encryptCommunications(api)
            .addRecipient(recipient)
            .addRecipient(secret.toCertificate())
        val signingOptions = SigningOptions.get(api)
            .addInlineSignature(SecretKeyRingProtector.unprotectedKeys(), secret, DocumentSignatureType.BINARY_DOCUMENT)
        val stream = api.generateMessage()
            .onOutputStream(out)
            .withOptions(
                ProducerOptions.signAndEncrypt(encryptionOptions, signingOptions).setAsciiArmor(true)
            )
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
        } finally {
            stream.close()
        }
        return out.toString("UTF-8")
    }

    fun decrypt(armored: String): PgpDecryption = synchronized(lock) {
        if (armored.length > MAX_ARMORED_CHARS) throw PgpException(R.string.pgp_too_large)
        ready()
        val secret = secretRing(currentID.value) ?: throw PgpException(R.string.pgp_no_key)
        val ownCert = secret.toCertificate()
        val known = recipientRings()

        val options = ConsumerOptions.get(api)
            .addDecryptionKey(secret, SecretKeyRingProtector.unprotectedKeys())
        known.forEach { options.addVerificationCert(it.second) }
        options.addVerificationCert(ownCert)

        val stream = runCatching {
            api.processMessage()
                .onInputStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8)))
                .withOptions(options)
        }.getOrNull() ?: throw PgpException(R.string.pgp_no_message)

        val out = ByteArrayOutputStream()
        try {
            val buf = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_PLAINTEXT_BYTES) {
                    runCatching { stream.close() }
                    throw PgpException(R.string.pgp_too_large)
                }
                out.write(buf, 0, n)
            }
            stream.close()
        } catch (e: PgpException) {
            throw e
        } catch (e: Exception) {
            throw PgpException(R.string.pgp_no_message)
        }
        val metadata = stream.metadata
        if (!metadata.isEncrypted) throw PgpException(R.string.pgp_no_message)
        val signedBy = runCatching {
            known.firstOrNull { metadata.isVerifiedSignedBy(it.second) }?.first?.name
                ?: if (metadata.isVerifiedSignedBy(ownCert)) currentIdentity?.name else null
        }.getOrNull()
        val verified = signedBy != null
        PgpDecryption(
            out.toString("UTF-8"),
            if (verified) PgpVerification.VERIFIED else PgpVerification.UNVERIFIED,
            signedBy,
        )
    }

    fun archivedIdentities(): List<com.kryptos.android.core.ArchivedPgpIdentity>? = synchronized(lock) {
        identities.value.map { ident ->
            val raw = runCatching { SecureStore.readStrict(secretKeyName(ident.id)) }.getOrNull()
                ?: return@synchronized null
            val armored = try {
                String(raw, Charsets.UTF_8)
            } finally {
                raw.fill(0)
            }
            if (armored.isBlank()) return@synchronized null
            com.kryptos.android.core.ArchivedPgpIdentity(
                id = ident.id, name = ident.name, email = ident.email,
                fingerprint = ident.fingerprint, algo = ident.algo,
                created = ident.createdAt, publicKey = ident.publicKey, secret = armored,
            )
        }
    }

    fun archivedRecipients(): List<com.kryptos.android.core.ArchivedPgpRecipient> =
        recipients.value.map {
            com.kryptos.android.core.ArchivedPgpRecipient(it.name, it.publicKey, it.fingerprint)
        }

    fun restore(
        list: List<com.kryptos.android.core.ArchivedPgpIdentity>,
        incoming: List<com.kryptos.android.core.ArchivedPgpRecipient>,
    ): Boolean = synchronized(lock) {
        ensureInitialized()
        if (list.isEmpty() && incoming.isEmpty()) return@synchronized true

        val restored = ArrayList<PgpIdentity>()
        val seen = HashSet<String>()
        for (entry in list) {
            if (!seen.add(entry.id)) continue
            if (runCatching { java.util.UUID.fromString(entry.id) }.isFailure) continue
            if (entry.secret.isBlank()) continue
            val identity = restoredIdentity(entry) ?: continue
            val written = runCatching {
                writeWiped(secretKeyName(entry.id), entry.secret.toByteArray(Charsets.UTF_8))
            }.isSuccess
            if (!written) continue
            restored.add(identity)
        }

        if (list.isNotEmpty() && restored.isEmpty()) return@synchronized false

        if (restored.isNotEmpty()) {
            val keep = restored.mapTo(HashSet()) { it.id }
            val stale = identities.value.map { it.id }.filter { it !in keep }
            identities.value = restored
            currentID.value = restored[0].id
            persistIndex()
            stale.forEach { runCatching { SecureStore.delete(secretKeyName(it)) } }
        }

        if (restored.isNotEmpty() || incoming.isNotEmpty()) {
            recipients.value = incoming.mapNotNull(::restoredRecipient)
            saveRecipients()
        }
        true
    }

    internal fun restoredIdentity(entry: com.kryptos.android.core.ArchivedPgpIdentity): PgpIdentity? {
        val ring = readKey(entry.secret) ?: return null
        return PgpIdentity(
            id = entry.id, name = entry.name, email = entry.email,
            fingerprint = prettyFingerprint(OpenPgpFingerprint.of(ring)), algo = algoLabel(ring),
            createdAt = entry.created,
            publicKey = ArmorUtils.toAsciiArmoredString(ring.toCertificate().encoded),
        )
    }

    @Suppress("DEPRECATION")
    private fun algoLabel(ring: OpenPGPKey): String {
        val key = ring.primaryKey.pgpPublicKey
        return when (key.algorithm) {
            PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_ENCRYPT, PublicKeyAlgorithmTags.RSA_SIGN ->
                "RSA ${key.bitStrength}"
            PublicKeyAlgorithmTags.EDDSA_LEGACY, PublicKeyAlgorithmTags.Ed25519 -> PgpAlgo.CURVE25519.label
            else -> PublicKeyAlgorithm.fromId(key.algorithm)?.name ?: key.algorithm.toString()
        }
    }

    internal fun restoredRecipient(entry: com.kryptos.android.core.ArchivedPgpRecipient): PgpRecipient? {
        if (entry.publicKey.length > MAX_ARMORED_CHARS) return null
        val ring = readCertificate(entry.publicKey) ?: return null
        return PgpRecipient(
            name = entry.name,
            publicKey = entry.publicKey,
            fingerprint = prettyFingerprint(OpenPgpFingerprint.of(ring)),
        )
    }

    fun eraseAllStorage() = synchronized(lock) {
        identities.value.forEach { SecureStore.delete(secretKeyName(it.id)) }
        SecureStore.delete(INDEX_KEY)
        SecureStore.delete(RECIPIENTS_KEY)
        identities.value = emptyList()
        recipients.value = emptyList()
        currentID.value = ""
        ringCacheKey = null
        ringCache = emptyList()
        initialized = false
    }
}
