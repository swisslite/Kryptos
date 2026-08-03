package com.kryptos.android

import com.kryptos.android.core.Argon2id
import com.kryptos.android.core.CipherException
import com.kryptos.android.core.PasswordCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Argon2Tests {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun check(
        memoryKiB: Int,
        iterations: Int,
        lanes: Int,
        password: String,
        salt: String,
        expected: String,
    ) {
        val out = Argon2id.hash(
            password.toByteArray(Charsets.UTF_8),
            salt.toByteArray(Charsets.UTF_8),
            memoryKiB, iterations, lanes, 32,
        )
        assertEquals("m=$memoryKiB t=$iterations p=$lanes pw=$password salt=$salt", expected, hex(out))
    }

    @Test
    fun officialArgon2idVectors() {
        check(65536, 2, 1, "password", "somesalt",
            "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7")
        check(256, 2, 1, "password", "somesalt",
            "9dfeb910e80bad0311fee20f9c0e2b12c17987b4cac90c2ef54d5b3021c68bfe")
        check(256, 2, 2, "password", "somesalt",
            "6d093c501fd5999645e0ea3bf620d7b8be7fd2db59c20d9fff9539da2bf57037")
        check(65536, 1, 1, "password", "somesalt",
            "f6a5adc1ba723dddef9b5ac1d464e180fcd9dffc9d1cbf76cca2fed795d9ca98")
        check(65536, 4, 1, "password", "somesalt",
            "9025d48e68ef7395cca9079da4c4ec3affb3c8911fe4f86d1a2520856f63172c")
        check(65536, 2, 1, "differentpassword", "somesalt",
            "0b84d652cf6b0c4beaef0dfe278ba6a80df6696281d7e0d2891b817d8c458fde")
        check(65536, 2, 1, "password", "diffsalt",
            "bdf32b05ccc42eb15d58fd19b1f856b113da1e9a5874fdcc544308565aa8141c")
    }

    @Test
    fun profileParametersArePinned() {
        assertEquals(1.toByte(), Argon2id.PROFILE_VERSION)
        assertEquals(65536, Argon2id.MEMORY_KIB)
        assertEquals(3, Argon2id.ITERATIONS)
        assertEquals(1, Argon2id.LANES)
        assertEquals(16, Argon2id.MIN_SALT_LENGTH)
    }

    @Test
    fun matchesIosProfileDerivation() {
        val salt = ByteArray(16) { it.toByte() }
        val out = Argon2id.derive("correct horse".toByteArray(Charsets.UTF_8), salt, 44)
        assertEquals(
            "f4ae3395bb837ce60b20533d083efeb43f18009e1baf05f438d8190eeae2177d2b4ab708772d978b9d130a22",
            hex(out),
        )
    }

    @Test(expected = CipherException::class)
    fun deriveRejectsShortSalt() {
        Argon2id.derive("pw".toByteArray(Charsets.UTF_8), ByteArray(15), 44)
    }

    @Test
    fun emptyPasswordDerivesDistinctKey() {
        val salt = ByteArray(16) { (it * 7).toByte() }
        val a = Argon2id.derive(ByteArray(0), salt, 32)
        val b = Argon2id.derive("x".toByteArray(Charsets.UTF_8), salt, 32)
        assertNotEquals(hex(a), hex(b))
        assertEquals(32, a.size)
    }

    @Test
    fun tokenCarriesSaltThenVersion() {
        val blob = PasswordCipher.encrypt("hi".toByteArray(Charsets.UTF_8), "pw")
        assertTrue(blob.size > PasswordCipher.SALT_LEN)
        assertEquals(Argon2id.PROFILE_VERSION, blob[PasswordCipher.SALT_LEN])
    }

    @Test
    fun rejectsUnknownProfileVersion() {
        val blob = PasswordCipher.encrypt("hi".toByteArray(Charsets.UTF_8), "pw")
        blob[PasswordCipher.SALT_LEN] = 0x7F
        try {
            PasswordCipher.decrypt(blob, "pw")
            throw AssertionError("expected rejection")
        } catch (e: CipherException) {
            assertEquals(CipherException.Kind.MALFORMED, e.kind)
        }
    }

    @Test
    fun tamperedSaltFailsAuthentication() {
        val blob = PasswordCipher.encrypt("hi".toByteArray(Charsets.UTF_8), "pw")
        blob[0] = (blob[0].toInt() xor 0xFF).toByte()
        try {
            PasswordCipher.decrypt(blob, "pw")
            throw AssertionError("expected rejection")
        } catch (e: CipherException) {
            assertEquals(CipherException.Kind.DECRYPTION_FAILED, e.kind)
        }
    }

    @Test
    fun saltIsFreshPerMessage() {
        val a = PasswordCipher.encrypt("same".toByteArray(Charsets.UTF_8), "pw")
        val b = PasswordCipher.encrypt("same".toByteArray(Charsets.UTF_8), "pw")
        assertNotEquals(hex(a.copyOfRange(0, PasswordCipher.SALT_LEN)), hex(b.copyOfRange(0, PasswordCipher.SALT_LEN)))
    }
}
