package com.kryptos.android

import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.pgpainless.PGPainless
import org.pgpainless.algorithm.DocumentSignatureType
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.encryption_signing.SigningOptions
import org.pgpainless.key.protection.SecretKeyRingProtector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PgpEnvelopeTests {

    companion object {
        private lateinit var ring: PGPSecretKeyRing

        @JvmStatic
        @BeforeClass
        fun generate() {
            ring = PGPainless.generateKeyRing().modernKeyRing("Kryptos Test <test@example.invalid>")
        }
    }

    private fun sealed(text: String): String {
        val out = ByteArrayOutputStream()
        val cert = PGPainless.extractCertificate(ring)
        val stream = PGPainless.encryptAndOrSign()
            .onOutputStream(out)
            .withOptions(
                ProducerOptions.signAndEncrypt(
                    EncryptionOptions.encryptCommunications().addRecipient(cert),
                    SigningOptions.get().addInlineSignature(
                        SecretKeyRingProtector.unprotectedKeys(),
                        ring,
                        DocumentSignatureType.BINARY_DOCUMENT,
                    ),
                ).setAsciiArmor(true)
            )
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toString("UTF-8")
    }

    private fun bare(text: String): String {
        val out = ByteArrayOutputStream()
        val stream = PGPainless.encryptAndOrSign()
            .onOutputStream(out)
            .withOptions(ProducerOptions.noEncryptionNoSigning().setAsciiArmor(true))
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toString("UTF-8")
    }

    private class Opened(val text: String, val encrypted: Boolean, val verified: Boolean)

    private fun open(armored: String): Opened {
        val cert = PGPainless.extractCertificate(ring)
        val options = ConsumerOptions.get()
            .addDecryptionKey(ring, SecretKeyRingProtector.unprotectedKeys())
            .addVerificationCert(cert)
        val stream = PGPainless.decryptAndOrVerify()
            .onInputStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8)))
            .withOptions(options)
        val out = ByteArrayOutputStream()
        stream.copyTo(out)
        stream.close()
        val metadata = stream.metadata
        return Opened(out.toString("UTF-8"), metadata.isEncrypted, metadata.isVerifiedSignedBy(cert))
    }

    @Test
    fun sealedMessageRoundTripsAndReportsItselfEncrypted() {
        val opened = open(sealed("привет"))
        assertEquals("привет", opened.text)
        assertTrue(opened.encrypted)
        assertTrue(opened.verified)
    }

    @Test
    fun bareLiteralPacketIsReadableButNotReportedEncrypted() {
        val opened = open(bare("подделка"))
        assertEquals("подделка", opened.text)
        assertFalse(opened.encrypted)
        assertFalse(opened.verified)
    }
}
