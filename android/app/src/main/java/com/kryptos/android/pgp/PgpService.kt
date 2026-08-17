package com.kryptos.android.pgp

import com.kryptos.android.R
import com.kryptos.android.store.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.pgpainless.PGPainless
import org.pgpainless.algorithm.DocumentSignatureType
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.encryption_signing.SigningOptions
import org.pgpainless.key.OpenPgpFingerprint
import org.pgpainless.key.generation.type.rsa.RsaLength
import org.pgpainless.key.protection.SecretKeyRingProtector
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
                generateBlocking(name = "My key", email = "", algo = PgpAlgo.CURVE25519)
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

    private fun persistIndex() {
        SecureStore.write(
            INDEX_KEY,
            json.encodeToString(PgpIndex.serializer(), PgpIndex(identities.value, currentID.value)).toByteArray()
        )
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

    private fun saveRecipients() {
        ringCacheKey = null
        SecureStore.write(RECIPIENTS_KEY, json.encodeToString(recipients.value).toByteArray())
    }

    private fun secretRing(id: String): PGPSecretKeyRing? {
        val raw = SecureStore.readStrict(secretKeyName(id)) ?: return null
        val ring = try {
            PGPainless.readKeyRing().secretKeyRing(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            throw PgpException(R.string.pgp_key_unreadable)
        }
        return ring ?: throw PgpException(R.string.pgp_key_unreadable)
    }

    private fun generateRing(userId: String, algo: PgpAlgo): PGPSecretKeyRing = when (algo) {
        PgpAlgo.CURVE25519 -> PGPainless.generateKeyRing().modernKeyRing(userId)
        PgpAlgo.RSA3072 -> PGPainless.generateKeyRing().simpleRsaKeyRing(userId, RsaLength._3072)
        PgpAlgo.RSA4096 -> PGPainless.generateKeyRing().simpleRsaKeyRing(userId, RsaLength._4096)
    }

    private fun prettyFingerprint(fp: OpenPgpFingerprint): String =
        fp.toString().uppercase().chunked(4).joinToString(" ")

    fun generateBlocking(name: String, email: String, algo: PgpAlgo): PgpIdentity = synchronized(lock) {
        busy.value = true
        try {
            val ident = PgpIdentity(name = name, email = email, fingerprint = "", algo = algo.label, createdAt = System.currentTimeMillis())
            val ring = generateRing(ident.userId, algo)
            val publicArmored = ArmorUtils.toAsciiArmoredString(PGPainless.extractCertificate(ring).encoded)
            val secretArmored = ArmorUtils.toAsciiArmoredString(ring.encoded)
            val done = ident.copy(
                fingerprint = prettyFingerprint(OpenPgpFingerprint.of(ring)),
                publicKey = publicArmored,
            )
            val previousIdentities = identities.value
            val previousCurrent = currentID.value
            SecureStore.write(secretKeyName(done.id), secretArmored.toByteArray())
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

    fun switchTo(id: String) = synchronized(lock) {
        if (identities.value.none { it.id == id }) return
        currentID.value = id
        persistIndex()
    }

    fun deleteIdentity(id: String) = synchronized(lock) {
        if (identities.value.none { it.id == id }) return
        val remaining = identities.value.filter { it.id != id }
        identities.value = remaining
        if (currentID.value == id) currentID.value = remaining.firstOrNull()?.id ?: ""
        persistIndex()
        SecureStore.delete(secretKeyName(id))
        if (remaining.isEmpty()) generateBlocking(name = "My key", email = "", algo = PgpAlgo.CURVE25519)
    }

    fun addRecipient(name: String, armoredKey: String) = synchronized(lock) {
        if (armoredKey.length > MAX_ARMORED_CHARS) throw PgpException(R.string.pgp_too_large)
        ready()
        val ring = runCatching { PGPainless.readKeyRing().publicKeyRing(armoredKey) }.getOrNull()
            ?: throw PgpException(R.string.pgp_invalid_key)
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
    private var ringCache: List<Pair<PgpRecipient, PGPPublicKeyRing>> = emptyList()

    private fun recipientRings(): List<Pair<PgpRecipient, PGPPublicKeyRing>> {
        val current = recipients.value
        if (ringCacheKey === current) return ringCache
        val built = current.mapNotNull { recipient ->
            runCatching { PGPainless.readKeyRing().publicKeyRing(recipient.publicKey) }.getOrNull()
                ?.let { recipient to it }
        }
        ringCacheKey = current
        ringCache = built
        return built
    }

    fun encrypt(text: String, to: PgpRecipient): String = synchronized(lock) {
        ready()
        val secret = secretRing(currentID.value) ?: throw PgpException(R.string.pgp_no_key)
        val recipientRing = runCatching { PGPainless.readKeyRing().publicKeyRing(to.publicKey) }.getOrNull()
            ?: throw PgpException(R.string.pgp_invalid_key)
        val ownCert = PGPainless.extractCertificate(secret)

        val out = ByteArrayOutputStream()
        val encryptionOptions = EncryptionOptions.encryptCommunications()
            .addRecipient(recipientRing)
            .addRecipient(ownCert)
        val signingOptions = SigningOptions.get()
            .addInlineSignature(SecretKeyRingProtector.unprotectedKeys(), secret, DocumentSignatureType.BINARY_DOCUMENT)
        val stream = PGPainless.encryptAndOrSign()
            .onOutputStream(out)
            .withOptions(
                ProducerOptions.signAndEncrypt(encryptionOptions, signingOptions).setAsciiArmor(true)
            )
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
        } finally {
            stream.close()
        }
        out.toString("UTF-8")
    }

    fun decrypt(armored: String): PgpDecryption = synchronized(lock) {
        if (armored.length > MAX_ARMORED_CHARS) throw PgpException(R.string.pgp_too_large)
        ready()
        val secret = secretRing(currentID.value) ?: throw PgpException(R.string.pgp_no_key)
        val ownCert = PGPainless.extractCertificate(secret)
        val known = recipientRings()

        val options = ConsumerOptions.get()
            .addDecryptionKey(secret, SecretKeyRingProtector.unprotectedKeys())
        known.forEach { options.addVerificationCert(it.second) }
        options.addVerificationCert(ownCert)

        val stream = runCatching {
            PGPainless.decryptAndOrVerify()
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
            val armored = String(raw, Charsets.UTF_8)
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
            val valid = runCatching { PGPainless.readKeyRing().secretKeyRing(entry.secret) != null }.getOrDefault(false)
            if (!valid) continue
            val written = runCatching {
                SecureStore.write(secretKeyName(entry.id), entry.secret.toByteArray(Charsets.UTF_8))
            }.isSuccess
            if (!written) continue
            restored.add(
                PgpIdentity(
                    id = entry.id, name = entry.name, email = entry.email,
                    fingerprint = entry.fingerprint, algo = entry.algo,
                    createdAt = entry.created, publicKey = entry.publicKey,
                )
            )
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
            recipients.value = incoming.map {
                PgpRecipient(name = it.name, publicKey = it.publicKey, fingerprint = it.fingerprint)
            }
            saveRecipients()
        }
        true
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
