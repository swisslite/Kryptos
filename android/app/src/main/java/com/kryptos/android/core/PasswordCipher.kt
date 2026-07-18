package com.kryptos.android.core

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PasswordCipher {
    const val ITERATIONS = 210_000

    private const val SALT_LEN = 16
    private const val TAG_LEN = 16
    private const val TAG_BITS = 128

    fun encrypt(plaintext: ByteArray, password: String, pad: Boolean = false): ByteArray {
        val salt = randomBytes(SALT_LEN)
        val km = derive(password, salt)
        val key = km.copyOfRange(0, 32)
        val nonce = km.copyOfRange(32, 44)
        val compressed = Deflate.compress(plaintext)
        val deflate = compressed != null
        val content = if (deflate) compressed!! else plaintext
        val framed = if (pad) Padding.frame(content) else content
        val body = ByteArray(1 + framed.size)
        body[0] = ((if (deflate) 0x01 else 0x00) or (if (pad) 0x02 else 0x00)).toByte()
        framed.copyInto(body, 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        key.fill(0)
        val ctAndTag = cipher.doFinal(body)
        return salt + ctAndTag
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        if (data.size < SALT_LEN + TAG_LEN) throw CipherException(CipherException.Kind.MALFORMED)
        val salt = data.copyOfRange(0, SALT_LEN)
        val ctAndTag = data.copyOfRange(SALT_LEN, data.size)
        val km = derive(password, salt)
        val key = km.copyOfRange(0, 32)
        val nonce = km.copyOfRange(32, 44)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        key.fill(0)
        val body = try {
            cipher.doFinal(ctAndTag)
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

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 44 * 8)
        return factory.generateSecret(spec).encoded
    }
}
