package com.kryptos.android

import com.kryptos.android.signal.Meta
import com.kryptos.android.signal.PreKeyMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreKeyReuseTests {

    private fun meta() = Meta(
        registrationId = 1,
        signedPreKeyId = 1, signedPreKeyPub = ByteArray(1), signedPreKeySig = ByteArray(1),
        kyberPreKeyId = 2, kyberPreKeyPub = ByteArray(1), kyberPreKeySig = ByteArray(1),
    )

    @Test
    fun markDependsOnBothIdentityAndPreKey() {
        val identity = byteArrayOf(1, 2, 3)
        val preKey = byteArrayOf(9, 9)
        assertEquals(PreKeyMark.of(identity, preKey), PreKeyMark.of(identity, preKey))
        assertNotEquals(PreKeyMark.of(identity, preKey), PreKeyMark.of(byteArrayOf(1, 2, 4), preKey))
        assertNotEquals(PreKeyMark.of(identity, preKey), PreKeyMark.of(identity, byteArrayOf(9, 8)))
    }

    @Test
    fun markSplitsIdentityFromPreKeyUnambiguously() {
        assertNotEquals(
            PreKeyMark.of(byteArrayOf(1, 2), byteArrayOf(3)),
            PreKeyMark.of(byteArrayOf(1), byteArrayOf(2, 3)),
        )
    }

    @Test
    fun markMatchesTheCrossPlatformVector() {
        assertEquals(
            "f12398d38a6ad6ae2b4fbd95b3c16816",
            PreKeyMark.of(byteArrayOf(1, 2, 3), byteArrayOf(9, 9)),
        )
    }

    @Test
    fun markHasTheDeclaredLength() {
        assertEquals(PreKeyMark.LENGTH, PreKeyMark.of(byteArrayOf(1), byteArrayOf(2)).length)
    }

    @Test
    fun usedPreKeyIsRememberedOnce() {
        val meta = meta()
        meta.rememberUsedPreKey("aa")
        meta.rememberUsedPreKey("aa")
        assertEquals(listOf("aa"), meta.usedPreKeys)
    }

    @Test
    fun oldestMarksAreEvictedAtTheCap() {
        val meta = meta()
        for (i in 1..514) meta.rememberUsedPreKey("mark-$i")
        assertEquals(512, meta.usedPreKeys.size)
        assertFalse("mark-1" in meta.usedPreKeys)
        assertFalse("mark-2" in meta.usedPreKeys)
        assertTrue("mark-3" in meta.usedPreKeys)
        assertTrue("mark-514" in meta.usedPreKeys)
    }
}
