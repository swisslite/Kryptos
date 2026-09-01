package com.kryptos.android

import com.kryptos.android.core.ExpiringCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExpiringCacheTests {

    private class Clock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun hitIsReturnedBeforeTtl() {
        val clock = Clock()
        val cache = ExpiringCache<String>(4, 100L, clock = clock)
        cache.remember("a", "plain")
        clock.now = 99L
        assertEquals("plain", cache.lookup("a")?.value)
    }

    @Test
    fun expiredHitIsDroppedFromMemoryOnLookup() {
        val clock = Clock()
        val cache = ExpiringCache<String>(4, 100L, clock = clock)
        cache.remember("a", "plain")
        assertEquals(1, cache.size())
        clock.now = 100L
        assertNull(cache.lookup("a"))
        assertEquals(0, cache.size())
    }

    @Test
    fun missUsesItsOwnTtl() {
        val clock = Clock()
        val cache = ExpiringCache<String>(4, 100L, 10L, clock)
        cache.remember("a", null)
        val slot = cache.lookup("a")
        assertNotNull(slot)
        assertNull(slot!!.value)
        clock.now = 10L
        assertNull(cache.lookup("a"))
    }

    @Test
    fun expiredEntriesAreSweptOnWrite() {
        val clock = Clock()
        val cache = ExpiringCache<String>(8, 100L, clock = clock)
        cache.remember("a", "one")
        cache.remember("b", "two")
        clock.now = 100L
        cache.remember("c", "three")
        assertEquals(1, cache.size())
        assertNull(cache.lookup("a"))
        assertEquals("three", cache.lookup("c")?.value)
    }

    @Test
    fun oldestEntryIsEvictedAtTheLimit() {
        val clock = Clock()
        val cache = ExpiringCache<String>(2, 1_000L, clock = clock)
        cache.remember("a", "one")
        cache.remember("b", "two")
        cache.lookup("a")
        cache.remember("c", "three")
        assertEquals(2, cache.size())
        assertNull(cache.lookup("b"))
        assertEquals("one", cache.lookup("a")?.value)
        assertEquals("three", cache.lookup("c")?.value)
    }

    @Test
    fun clearDropsEverything() {
        val cache = ExpiringCache<String>(4, 1_000L)
        cache.remember("a", "one")
        cache.remember("b", null)
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.lookup("a"))
        assertNull(cache.lookup("b"))
    }
}
