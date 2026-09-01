package com.kryptos.android

import com.kryptos.android.signal.CachedDecrypt
import com.kryptos.android.signal.ChatMessage
import com.kryptos.android.signal.MessageExpiry
import com.kryptos.android.signal.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageExpiryTests {

    private fun meta(): Meta = Meta(
        registrationId = 1,
        signedPreKeyId = 1,
        signedPreKeyPub = ByteArray(0),
        signedPreKeySig = ByteArray(0),
        kyberPreKeyId = 2,
        kyberPreKeyPub = ByteArray(0),
        kyberPreKeySig = ByteArray(0),
    )

    private fun message(text: String, ageMs: Long): ChatMessage =
        ChatMessage(text = text, mine = false, date = System.currentTimeMillis() - ageMs)

    private fun cached(fingerprint: String, text: String, ageMs: Long): CachedDecrypt =
        CachedDecrypt(fingerprint, text, date = System.currentTimeMillis() - ageMs)

    @Test
    fun expiresEveryChatNotOnlyTheActiveOne() {
        val m = meta()
        m.autoDelete = mapOf("aa" to 30.0, "bb" to 30.0)
        m.messages = mapOf(
            "aa" to listOf(message("old-a", 60_000), message("fresh-a", 1_000)),
            "bb" to listOf(message("old-b", 60_000)),
        )

        assertTrue(m.purgeExpired())
        assertEquals(listOf("fresh-a"), m.messages["aa"]?.map { it.text })
        assertNull(m.messages["bb"])
    }

    @Test
    fun dropsTheChatKeyWhenNothingSurvives() {
        val m = meta()
        m.autoDelete = mapOf("aa" to 5.0)
        m.messages = mapOf("aa" to listOf(message("gone", 10_000)))

        assertTrue(m.purgeExpired())
        assertFalse(m.messages.containsKey("aa"))
    }

    @Test
    fun expiresCachedPlaintextOfTheSameChat() {
        val m = meta()
        m.autoDelete = mapOf("aa" to 30.0)
        m.decryptCache = mapOf(
            "k1" to cached("aa", "old", 60_000),
            "k2" to cached("aa", "fresh", 1_000),
            "k3" to cached("bb", "other", 60_000),
        )

        assertTrue(m.purgeExpired())
        assertEquals(setOf("k2", "k3"), m.decryptCache.keys)
    }

    @Test
    fun leavesChatsWithoutAutoDeleteAlone() {
        val m = meta()
        m.autoDelete = mapOf("aa" to 30.0)
        m.messages = mapOf(
            "aa" to listOf(message("old-a", 60_000)),
            "bb" to listOf(message("old-b", 60_000)),
        )
        m.decryptCache = mapOf("k1" to cached("bb", "old-b", 60_000))

        assertTrue(m.purgeExpired())
        assertNull(m.messages["aa"])
        assertEquals(listOf("old-b"), m.messages["bb"]?.map { it.text })
        assertEquals(setOf("k1"), m.decryptCache.keys)
    }

    @Test
    fun reportsNoChangeWhenNothingHasExpired() {
        val m = meta()
        m.autoDelete = mapOf("aa" to 3600.0)
        m.messages = mapOf("aa" to listOf(message("fresh", 1_000)))
        m.decryptCache = mapOf("k1" to cached("aa", "fresh", 1_000))

        assertFalse(m.purgeExpired())
        assertEquals(1, m.messages["aa"]?.size)
        assertEquals(1, m.decryptCache.size)
    }

    @Test
    fun offIntervalNeverExpires() {
        val m = meta()
        m.autoDelete = mapOf("aa" to 0.0)
        m.messages = mapOf("aa" to listOf(message("kept", 999_000)))

        assertFalse(m.purgeExpired())
        assertEquals(listOf("kept"), m.messages["aa"]?.map { it.text })
    }

    @Test
    fun deletingOneMessageRemovesItsCachedPlaintext() {
        val m = meta()
        m.messages = mapOf("aa" to listOf(message("keep", 0), message("drop", 0)))
        m.decryptCache = mapOf(
            "k1" to cached("aa", "drop", 0),
            "k2" to cached("aa", "keep", 0),
            "k3" to cached("bb", "drop", 0),
        )

        m.purgeDecrypted("aa", "drop")
        assertEquals(setOf("k2", "k3"), m.decryptCache.keys)
    }

    @Test
    fun schedulesTheSoonestExpiryAcrossAllChats() {
        val now = System.currentTimeMillis()
        val due = MessageExpiry.nextDueAt(
            messages = mapOf(
                "aa" to listOf(ChatMessage(text = "later", mine = false, date = now)),
                "bb" to listOf(ChatMessage(text = "sooner", mine = false, date = now - 20_000)),
            ),
            autoDelete = mapOf("aa" to 60.0, "bb" to 30.0),
        )
        assertEquals(now + 10_000, due)
    }

    @Test
    fun schedulesNothingWithoutAutoDelete() {
        val now = System.currentTimeMillis()
        assertNull(
            MessageExpiry.nextDueAt(
                messages = mapOf("aa" to listOf(ChatMessage(text = "kept", mine = false, date = now))),
                autoDelete = emptyMap(),
            ),
        )
    }

    @Test
    fun schedulesNothingForChatsThatCarryNoMessages() {
        assertNull(
            MessageExpiry.nextDueAt(messages = emptyMap(), autoDelete = mapOf("aa" to 30.0)),
        )
    }

    @Test
    fun ignoresChatsWhereAutoDeleteIsOff() {
        val now = System.currentTimeMillis()
        val due = MessageExpiry.nextDueAt(
            messages = mapOf(
                "aa" to listOf(ChatMessage(text = "kept", mine = false, date = now - 90_000)),
                "bb" to listOf(ChatMessage(text = "goes", mine = false, date = now)),
            ),
            autoDelete = mapOf("aa" to 0.0, "bb" to 30.0),
        )
        assertEquals(now + 30_000, due)
    }
}
