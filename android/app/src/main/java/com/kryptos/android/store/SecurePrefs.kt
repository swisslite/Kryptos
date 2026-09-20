package com.kryptos.android.store

import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal class SecurePrefs(
    private val storeKey: String,
    private val legacy: () -> SharedPreferences,
    private val legacySkip: Set<String>,
) : SharedPreferences {

    private companion object {
        const val RETRY_AFTER_FAILURE_MS = 1_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val entries = MapSerializer(String.serializer(), String.serializer())
    private val strings = ListSerializer(String.serializer())

    private val values = HashMap<String, String>()
    private var loaded = false
    private var lastFailureAt = 0L

    @Synchronized
    fun isReady(): Boolean = ready()

    private fun ready(): Boolean {
        if (loaded) return true
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastFailureAt != 0L && now - lastFailureAt < RETRY_AFTER_FAILURE_MS) return false
        val raw = try {
            SecureStore.readStrict(storeKey)
        } catch (e: Exception) {
            lastFailureAt = now
            return false
        }
        if (raw == null) {
            importLegacy()
            loaded = true
            lastFailureAt = 0L
            if (values.isNotEmpty()) runCatching { persist() }
            return true
        }
        val parsed = try {
            json.decodeFromString(entries, String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        } finally {
            raw.fill(0)
        }
        values.clear()
        parsed?.let { values.putAll(it) }
        loaded = true
        lastFailureAt = 0L
        return true
    }

    private fun importLegacy() {
        val stored = runCatching { legacy().all }.getOrNull() ?: return
        for ((key, value) in stored) {
            if (key in legacySkip || value == null) continue
            values[key] = when (value) {
                is Set<*> -> json.encodeToString(strings, value.map { it.toString() })
                else -> value.toString()
            }
        }
    }

    private fun persist() {
        val body = json.encodeToString(entries, values).toByteArray(Charsets.UTF_8)
        try {
            SecureStore.write(storeKey, body)
        } finally {
            body.fill(0)
        }
    }

    private fun raw(key: String?): String? {
        if (key == null || !ready()) return null
        return values[key]
    }

    @Synchronized
    override fun getAll(): MutableMap<String, *> =
        if (ready()) HashMap<String, Any?>(values) else HashMap<String, Any?>()

    @Synchronized
    override fun getString(key: String?, defValue: String?): String? = raw(key) ?: defValue

    @Synchronized
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val stored = raw(key) ?: return defValues
        val list = runCatching { json.decodeFromString(strings, stored) }.getOrNull() ?: return defValues
        return LinkedHashSet(list)
    }

    @Synchronized
    override fun getInt(key: String?, defValue: Int): Int = raw(key)?.toIntOrNull() ?: defValue

    @Synchronized
    override fun getLong(key: String?, defValue: Long): Long = raw(key)?.toLongOrNull() ?: defValue

    @Synchronized
    override fun getFloat(key: String?, defValue: Float): Float = raw(key)?.toFloatOrNull() ?: defValue

    @Synchronized
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        raw(key)?.toBooleanStrictOrNull() ?: defValue

    @Synchronized
    override fun contains(key: String?): Boolean = raw(key) != null

    override fun edit(): SharedPreferences.Editor = Changes()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    @Synchronized
    private fun commit(clearFirst: Boolean, changes: Map<String, String?>): Boolean {
        if (clearFirst) {
            values.clear()
            loaded = true
        } else if (!ready()) {
            return false
        }
        for ((key, value) in changes) {
            if (value == null) values.remove(key) else values[key] = value
        }
        return runCatching { persist() }.isSuccess
    }

    private inner class Changes : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, String?>()
        private var clearFirst = false

        private fun set(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) changes[key] = value
            return this
        }

        override fun putString(key: String?, value: String?) = set(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            set(key, values?.let { json.encodeToString(strings, it.toList()) })

        override fun putInt(key: String?, value: Int) = set(key, value.toString())

        override fun putLong(key: String?, value: Long) = set(key, value.toString())

        override fun putFloat(key: String?, value: Float) = set(key, value.toString())

        override fun putBoolean(key: String?, value: Boolean) = set(key, value.toString())

        override fun remove(key: String?) = set(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearFirst = true
            changes.clear()
            return this
        }

        override fun commit(): Boolean = this@SecurePrefs.commit(clearFirst, changes)

        override fun apply() {
            this@SecurePrefs.commit(clearFirst, changes)
        }
    }
}
