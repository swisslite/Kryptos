package com.kryptos.android.core

class ExpiringCache<V>(
    private val limit: Int,
    private val hitTtlMs: Long,
    private val missTtlMs: Long = hitTtlMs,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    class Slot<V>(val value: V?)

    private class Entry<V>(val value: V?, val at: Long)

    private val map = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>): Boolean =
            size > limit
    }

    @Synchronized
    fun lookup(key: String): Slot<V>? {
        val entry = map[key] ?: return null
        if (expired(entry, clock())) {
            map.remove(key)
            return null
        }
        return Slot(entry.value)
    }

    @Synchronized
    fun remember(key: String, value: V?) {
        val now = clock()
        map.entries.removeAll { expired(it.value, now) }
        map[key] = Entry(value, now)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size

    private fun expired(entry: Entry<V>, now: Long): Boolean {
        val ttl = if (entry.value != null) hitTtlMs else missTtlMs
        return now - entry.at >= ttl
    }
}
