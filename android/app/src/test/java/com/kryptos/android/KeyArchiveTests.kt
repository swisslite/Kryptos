package com.kryptos.android

import com.kryptos.android.core.ArchiveException
import com.kryptos.android.core.ArchivedContact
import com.kryptos.android.core.ArchivedPgpIdentity
import com.kryptos.android.core.ArchivedPgpRecipient
import com.kryptos.android.core.ArchivedProfile
import com.kryptos.android.core.ArchivedRetired
import com.kryptos.android.core.KeyArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class KeyArchiveTests {

    private val iosVector =
        "DetjJqDoRFkAuRYPn8Ws7QHPVbZpHYPRvOGcrHMLG7p81HRMyrAkE6WkgB2vuRImAL4b_98sITbaVeWh82Rh8nm0I8W_" +
            "Cfg5IIE4LxoHaXIkqVXfi9t-Lov5KYz3u_Hu6CktiQidtuxfe_RhDlL215fuL1ZKQxe1yRRzg6TfcBHU568BNUac" +
            "2mZogYJtQAZzd1U0ThjUHP1-6iz8OI3lxYYDxIWqbjinrE0eVOY1nXtg8v_uiUvf1ggAPqIgYoRIIqtr6jVvNRtb" +
            "pwPCtWDuAqrPPCZgRB0F8KDfiQz_JgrllRnBk_VxQVre_79XPV4I9OhjbMcPdbZAwLUnXJ-MxPKyIoRCTcZpYFG4" +
            "fWLjR8iWf_jcPpyzl4g1e9gu6gz8vJp45bfD78MZqi6irV7zdqCUP3fERwLf6e80l7W_73BKgYHtKpUNX2KyPNnL" +
            "0BESTKcMhjb_Njm6MyE8xgc8FCmMMP4L9nhawuXNWmjB_sSbEwqEqYewa0H3gNTa6ephRlFudSJ1nsAatGWWamNn" +
            "JakD7qQhHY4txvQi2xDuJtr3cQgoq3oQ5zlhyuvaAmAMhnDAIX7Wt4BBaxJNLocKccC8N74Wat5kqj9jnkuNZyZa" +
            "qUCe1a1h_ELCJtEDHPSzCK_YoUtpBB3mep0Cpv3M4ci9W6YNsM0M_MbEuHkQ"

    private fun b64(vararg bytes: Int) =
        Base64.getEncoder().encodeToString(bytes.map { it.toByte() }.toByteArray())

    @Test
    fun opensIosKeyArchive() {
        val archive = KeyArchive.open(iosVector, "backup-test-pass")
        assertEquals(KeyArchive.MAGIC, archive.kryptos)
        assertEquals(KeyArchive.VERSION, archive.v)
        assertEquals(1234567890123L, archive.created)

        assertEquals(1, archive.profiles.size)
        val p = archive.profiles[0]
        assertEquals("11111111-2222-3333-4444-555555555555", p.id)
        assertEquals("Vector", p.name)
        assertEquals(b64(0x01, 0x02, 0x03), p.identity)
        assertEquals(4242L, p.registrationId)
        assertEquals(7L, p.signedPreKeyId)
        assertEquals(b64(0xAA), p.signedPreKeyPub)
        assertEquals(b64(0xBB), p.signedPreKeySig)
        assertEquals(8L, p.kyberPreKeyId)
        assertEquals(b64(0xCC), p.kyberPreKeyPub)
        assertEquals(b64(0xDD), p.kyberPreKeySig)
        assertEquals(1700000000000L, p.prekeyCreatedAt)
        assertEquals(9L, p.nextSignedPreKeyId)
        assertEquals(10L, p.nextKyberPreKeyId)
        assertEquals(11L, p.nextOneTimePreKeyId)
        assertEquals(listOf(1L, 2L, 3L), p.oneTimePreKeyIds)
        assertEquals(listOf(ArchivedRetired(5L, 6L, 1600000000000L)), p.retired)
        assertEquals(mapOf("ffee" to 30.0), p.autoDelete)
        assertEquals(listOf(ArchivedContact("ffee", "Alice")), p.contacts)
        assertEquals(mapOf("1" to b64(0x11)), p.preKeys)
        assertEquals(mapOf("7" to b64(0x22)), p.signedPreKeys)
        assertEquals(mapOf("8" to b64(0x33)), p.kyberPreKeys)
        assertEquals(mapOf("ffee|1" to b64(0x44)), p.sessions)
        assertEquals(mapOf("ffee|1" to b64(0x55)), p.identities)

        assertEquals(1, archive.pgpIdentities.size)
        val g = archive.pgpIdentities[0]
        assertEquals("66666666-7777-8888-9999-aaaaaaaaaaaa", g.id)
        assertEquals("PGP", g.name)
        assertEquals("p@p", g.email)
        assertEquals("FFFF", g.fingerprint)
        assertEquals("Curve25519", g.algo)
        assertEquals(1500000000000L, g.created)
        assertEquals("PUB", g.publicKey)
        assertEquals("SEC", g.secret)

        assertEquals(listOf(ArchivedPgpRecipient("Bob", "BOBPUB", "EEEE")), archive.pgpRecipients)
        assertEquals(1, archive.contactCount)
    }

    @Test
    fun wrongPasswordIsRejected() {
        try {
            KeyArchive.open(iosVector, "backup-test-pasS")
            fail("expected rejection")
        } catch (e: ArchiveException) {
            assertEquals(ArchiveException.Kind.UNREADABLE, e.kind)
        }
    }

    @Test
    fun garbageIsRejected() {
        try {
            KeyArchive.open("not-an-archive-at-all-but-long-enough-to-look-like-base64url", "backup-test-pass")
            fail("expected rejection")
        } catch (e: ArchiveException) {
            assertEquals(ArchiveException.Kind.UNREADABLE, e.kind)
        }
    }

    @Test
    fun roundTripsOnAndroid() {
        val archive = KeyArchive(
            created = 42,
            profiles = listOf(
                ArchivedProfile(
                    id = "id-1", name = "Профиль", identity = b64(0x09),
                    registrationId = 1, signedPreKeyId = 2, signedPreKeyPub = b64(0x01),
                    signedPreKeySig = b64(0x02), kyberPreKeyId = 3, kyberPreKeyPub = b64(0x03),
                    kyberPreKeySig = b64(0x04), prekeyCreatedAt = null,
                    nextSignedPreKeyId = 4, nextKyberPreKeyId = 5, nextOneTimePreKeyId = 6,
                    contacts = listOf(ArchivedContact("aabb", "Ирина")),
                    sessions = mapOf("aabb|1" to b64(0x07)),
                )
            ),
            pgpIdentities = listOf(
                ArchivedPgpIdentity("pid", "n", "e", "f", "a", 7, "pub", "sec")
            ),
            pgpRecipients = listOf(ArchivedPgpRecipient("r", "rp", "rf")),
        )
        val token = KeyArchive.seal(archive, "another-strong-pass")
        assertTrue(token.length > 64)
        assertEquals(archive, KeyArchive.open(token, "another-strong-pass"))
    }

    @Test
    fun shortPasswordIsRefused() {
        try {
            KeyArchive.seal(KeyArchive(pgpRecipients = listOf(ArchivedPgpRecipient("r", "p", "f"))), "short")
            fail("expected refusal")
        } catch (e: ArchiveException) {
            assertEquals(ArchiveException.Kind.PASSWORD_TOO_SHORT, e.kind)
        }
    }

    @Test
    fun emptyArchiveIsRefused() {
        try {
            KeyArchive.seal(KeyArchive(), "long-enough-password")
            fail("expected refusal")
        } catch (e: ArchiveException) {
            assertEquals(ArchiveException.Kind.NOTHING_TO_EXPORT, e.kind)
        }
    }
}
