package com.kryptos.android.core

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object PasswordCipher {
    const val SALT_LEN = 16
    const val TAG_LEN = 16
    const val KEY_LEN = 32
    const val DERIVED_LEN = 44

    private const val TAG_BITS = 128

    fun sealBody(plaintext: ByteArray, key: ByteArray, nonce: ByteArray, version: Byte, pad: Boolean): ByteArray {
        val compressed = Deflate.compress(plaintext)
        val deflate = compressed != null
        val content = if (deflate) compressed!! else plaintext
        val framed = if (pad) Padding.frame(content) else content
        val body = ByteArray(1 + framed.size)
        body[0] = ((if (deflate) 0x01 else 0x00) or (if (pad) 0x02 else 0x00)).toByte()
        framed.copyInto(body, 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(byteArrayOf(version))
        return cipher.doFinal(body)
    }

    fun openBody(sealed: ByteArray, key: ByteArray, nonce: ByteArray, version: Byte): ByteArray {
        if (sealed.size < TAG_LEN) throw CipherException(CipherException.Kind.DECRYPTION_FAILED)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(byteArrayOf(version))
        val body = try {
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            throw CipherException(CipherException.Kind.DECRYPTION_FAILED)
        }
        if (body.isEmpty()) throw CipherException(CipherException.Kind.DECRYPTION_FAILED)
        val flag = body[0].toInt()
        var content = body.copyOfRange(1, body.size)
        if (flag and 0x02 != 0) {
            content = Padding.unframe(content) ?: throw CipherException(CipherException.Kind.DECRYPTION_FAILED)
        }
        if (flag and 0x01 != 0) {
            return Deflate.decompress(content) ?: throw CipherException(CipherException.Kind.DECRYPTION_FAILED)
        }
        return content
    }

    fun encrypt(plaintext: ByteArray, password: String, pad: Boolean = false): ByteArray {
        val salt = randomBytes(SALT_LEN)
        val version = Argon2id.PROFILE_VERSION
        val km = Argon2id.derive(password, salt, DERIVED_LEN)
        val key = km.copyOfRange(0, KEY_LEN)
        val nonce = km.copyOfRange(KEY_LEN, DERIVED_LEN)
        km.fill(0)
        val sealed = try {
            sealBody(plaintext, key, nonce, version, pad)
        } finally {
            key.fill(0)
            nonce.fill(0)
        }
        return salt + version + sealed
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        if (data.size < SALT_LEN + 1 + TAG_LEN) throw CipherException(CipherException.Kind.MALFORMED)
        val salt = data.copyOfRange(0, SALT_LEN)
        val version = data[SALT_LEN]
        if (version != Argon2id.PROFILE_VERSION) throw CipherException(CipherException.Kind.MALFORMED)
        val sealed = data.copyOfRange(SALT_LEN + 1, data.size)
        val km = Argon2id.derive(password, salt, DERIVED_LEN)
        val key = km.copyOfRange(0, KEY_LEN)
        val nonce = km.copyOfRange(KEY_LEN, DERIVED_LEN)
        km.fill(0)
        try {
            return openBody(sealed, key, nonce, version)
        } finally {
            key.fill(0)
            nonce.fill(0)
        }
    }
}
