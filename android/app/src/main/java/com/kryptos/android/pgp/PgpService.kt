package com.kryptos.android.pgp

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

class PgpException(message: String) : Exception(message)

@Serializable
private data class PgpIndex(var identities: List<PgpIdentity> = emptyList(), var currentID: String = "")

object PgpService {
    private const val INDEX_KEY = "pgp.index"
    private const val RECIPIENTS_KEY = "pgp.recipients"
    private const val MAX_PLAINTEXT_BYTES = 8L * 1024 * 1024
    private fun secretKeyName(id: String) = "pgp.secret.$id"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    val identities = MutableStateFlow<List<PgpIdentity>>(emptyList())
    val currentID = MutableStateFlow("")
    val recipients = MutableStateFlow<List<PgpRecipient>>(emptyList())
    val busy = MutableStateFlow(false)

    val currentIdentity: PgpIdentity? get() = identities.value.firstOrNull { it.id == currentID.value }
    val myPublicKey: String get() = currentIdentity?.publicKey ?: ""

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
            initialized = true
            if (identities.value.isEmpty()) {
                generateBlocking(name = "My key", email = "", algo = PgpAlgo.CURVE25519)
            }
        }
    }

    private fun persistIndex() {
        SecureStore.write(
            INDEX_KEY,
            json.encodeToString(PgpIndex.serializer(), PgpIndex(identities.value, currentID.value)).toByteArray()
        )
    }

    private fun loadRecipients() {
        recipients.value = SecureStore.readStrict(RECIPIENTS_KEY)?.let {
            try {
                json.decodeFromString<List<PgpRecipient>>(String(it, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalStateException("PgpService: recipients exist but cannot be parsed", e)
            }
        } ?: emptyList()
    }

    private fun saveRecipients() {
        SecureStore.write(RECIPIENTS_KEY, json.encodeToString(recipients.value).toByteArray())
    }

    private fun secretRing(id: String): PGPSecretKeyRing? =
        SecureStore.read(secretKeyName(id))?.let {
            runCatching { PGPainless.readKeyRing().secretKeyRing(String(it, Charsets.UTF_8)) }.getOrNull()
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
            SecureStore.write(secretKeyName(done.id), secretArmored.toByteArray())
            identities.value = identities.value + done
            currentID.value = done.id
            persistIndex()
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
        SecureStore.delete(secretKeyName(id))
        identities.value = identities.value.filter { it.id != id }
        if (identities.value.isEmpty()) {
            persistIndex()
            generateBlocking(name = "My key", email = "", algo = PgpAlgo.CURVE25519)
            return
        }
        if (currentID.value == id) currentID.value = identities.value[0].id
        persistIndex()
    }

    fun addRecipient(name: String, armoredKey: String) = synchronized(lock) {
        val ring = runCatching { PGPainless.readKeyRing().publicKeyRing(armoredKey) }.getOrNull()
            ?: throw PgpException("This is not a valid PGP public key.")
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

    private fun recipientRings(): List<PGPPublicKeyRing> =
        recipients.value.mapNotNull { runCatching { PGPainless.readKeyRing().publicKeyRing(it.publicKey) }.getOrNull() }

    fun encrypt(text: String, to: PgpRecipient): String = synchronized(lock) {
        val secret = secretRing(currentID.value) ?: throw PgpException("No PGP key is selected.")
        val recipientRing = runCatching { PGPainless.readKeyRing().publicKeyRing(to.publicKey) }.getOrNull()
            ?: throw PgpException("This is not a valid PGP public key.")
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
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.close()
        out.toString("UTF-8")
    }

    fun decrypt(armored: String): Pair<String, PgpVerification> = synchronized(lock) {
        val secret = secretRing(currentID.value) ?: throw PgpException("No PGP key is selected.")
        val verifyCerts: List<PGPPublicKeyRing> = recipientRings() + listOf(PGPainless.extractCertificate(secret))

        val options = ConsumerOptions.get()
            .addDecryptionKey(secret, SecretKeyRingProtector.unprotectedKeys())
        verifyCerts.forEach { options.addVerificationCert(it) }

        val stream = runCatching {
            PGPainless.decryptAndOrVerify()
                .onInputStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8)))
                .withOptions(options)
        }.getOrNull() ?: throw PgpException("No PGP message found.")

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
                    throw PgpException("Message too large.")
                }
                out.write(buf, 0, n)
            }
            stream.close()
        } catch (e: PgpException) {
            throw e
        } catch (e: Exception) {
            throw PgpException("No PGP message found.")
        }
        val verified = stream.metadata.verifiedSignatures.isNotEmpty()
        out.toString("UTF-8") to (if (verified) PgpVerification.VERIFIED else PgpVerification.UNVERIFIED)
    }

    fun eraseAllStorage() = synchronized(lock) {
        identities.value.forEach { SecureStore.delete(secretKeyName(it.id)) }
        SecureStore.delete(INDEX_KEY)
        SecureStore.delete(RECIPIENTS_KEY)
        identities.value = emptyList()
        recipients.value = emptyList()
        currentID.value = ""
        initialized = false
    }
}
