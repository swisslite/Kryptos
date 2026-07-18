package com.kryptos.android.signal

import android.util.Base64
import com.kryptos.android.core.StegoLanguage
import com.kryptos.android.core.randomBytes
import com.kryptos.android.store.SecureStore
import java.security.MessageDigest

object AppSettingsStore {
    private val prefs get() = SecureStore.prefs()

    var chatStegoEnabled: Boolean
        get() = prefs.getBoolean("stego.enabled", false)
        set(v) { prefs.edit().putBoolean("stego.enabled", v).apply() }

    var chatStegoLanguage: String
        get() = prefs.getString("stego.lang", "auto") ?: "auto"
        set(v) { prefs.edit().putString("stego.lang", v).apply() }

    var chatStegoSmart: Boolean
        get() = prefs.getBoolean("stego.smart", false)
        set(v) { prefs.edit().putBoolean("stego.smart", v).apply() }

    fun resolvedStegoLanguage(): StegoLanguage? {
        if (!chatStegoEnabled) return null
        return when (chatStegoLanguage) {
            "english" -> StegoLanguage.ENGLISH
            "russian" -> StegoLanguage.RUSSIAN
            else -> StegoLanguage.forSystem()
        }
    }

    fun resolvedStegoSmart(): Boolean = chatStegoEnabled && chatStegoSmart

    var keyboardHaptics: Boolean
        get() = prefs.getBoolean("kb.haptics", true)
        set(v) { prefs.edit().putBoolean("kb.haptics", v).apply() }

    var keyboardSounds: Boolean
        get() = prefs.getBoolean("kb.sounds", true)
        set(v) { prefs.edit().putBoolean("kb.sounds", v).apply() }

    var keyboardCompose: Boolean
        get() = prefs.getBoolean("kb.compose", false)
        set(v) { prefs.edit().putBoolean("kb.compose", v).apply() }

    var keyboardAutoDecrypt: Boolean
        get() = prefs.getBoolean("kb.autodecrypt", true)
        set(v) { prefs.edit().putBoolean("kb.autodecrypt", v).apply() }

    var keyboardHandledClip: String?
        get() = prefs.getString("kb.clip.handled", null)
        set(v) { prefs.edit().putString("kb.clip.handled", v).apply() }

    var keyboardSuggestions: Boolean
        get() = prefs.getBoolean("kb.suggestions", true)
        set(v) { prefs.edit().putBoolean("kb.suggestions", v).apply() }

    var keyboardAutocorrect: Boolean
        get() = prefs.getBoolean("kb.autocorrect", true)
        set(v) { prefs.edit().putBoolean("kb.autocorrect", v).apply() }

    var keyboardEmoji: Boolean
        get() = prefs.getBoolean("kb.emoji", true)
        set(v) { prefs.edit().putBoolean("kb.emoji", v).apply() }

    val systemRussian: Boolean
        get() = java.util.Locale.getDefault().language.lowercase().startsWith("ru")

    fun keyboardLangEnabled(code: String): Boolean =
        prefs.getBoolean("kb.lang.$code", code == "en" || (code == "ru" && systemRussian))

    fun setKeyboardLang(code: String, enabled: Boolean) {
        prefs.edit().putBoolean("kb.lang.$code", enabled).apply()
    }

    var keyboardLastLang: String?
        get() = prefs.getString("kb.lang.last", null)
        set(v) { prefs.edit().putString("kb.lang.last", v).apply() }

    fun keyboardContact(profileId: String): String? =
        if (profileId.isEmpty()) null
        else runCatching { SecureStore.read("kb.contact.$profileId")?.toString(Charsets.UTF_8) }.getOrNull()

    fun setKeyboardContact(profileId: String, fingerprint: String) {
        if (profileId.isEmpty()) return
        runCatching { SecureStore.write("kb.contact.$profileId", fingerprint.toByteArray(Charsets.UTF_8)) }
    }

    fun clearKeyboardContact(profileId: String) {
        if (profileId.isEmpty()) return
        runCatching { SecureStore.delete("kb.contact.$profileId") }
    }

    var privacyShield: Boolean
        get() = prefs.getBoolean("privacy.shield", true)
        set(v) { prefs.edit().putBoolean("privacy.shield", v).apply() }

    var clipboardAutoDecrypt: Boolean
        get() = prefs.getBoolean("privacy.clipauto", true)
        set(v) { prefs.edit().putBoolean("privacy.clipauto", v).apply() }

    var clipboardClearSeconds: Int
        get() = prefs.getInt("privacy.clipclear", 30)
        set(v) { prefs.edit().putInt("privacy.clipclear", v).apply() }

    var appLock: Boolean
        get() = prefs.getBoolean("privacy.applock", false)
        set(v) { prefs.edit().putBoolean("privacy.applock", v).apply() }

    var autoLockGraceSeconds: Int
        get() = prefs.getInt("privacy.lockgrace", 0)
        set(v) { prefs.edit().putInt("privacy.lockgrace", v).apply() }

    var secureKeyboard: Boolean
        get() = prefs.getBoolean("privacy.securekb", true)
        set(v) { prefs.edit().putBoolean("privacy.securekb", v).apply() }

    var clearClipboardOnDecrypt: Boolean
        get() = prefs.getBoolean("privacy.clipondecrypt", false)
        set(v) { prefs.edit().putBoolean("privacy.clipondecrypt", v).apply() }

    var integrityWarnings: Boolean
        get() = prefs.getBoolean("privacy.integrity", true)
        set(v) { prefs.edit().putBoolean("privacy.integrity", v).apply() }

    var screenDecrypt: Boolean
        get() = prefs.getBoolean("privacy.screendecrypt", false)
        set(v) { prefs.edit().putBoolean("privacy.screendecrypt", v).apply() }

    var screenDecryptSecure: Boolean
        get() = prefs.getBoolean("privacy.screendecrypt.secure", false)
        set(v) { prefs.edit().putBoolean("privacy.screendecrypt.secure", v).apply() }

    var lengthPadding: Boolean
        get() = prefs.getBoolean("privacy.lengthpad", false)
        set(v) { prefs.edit().putBoolean("privacy.lengthpad", v).apply() }

    private const val DURESS_HASH = "privacy.duresspin.hash"
    private const val DURESS_SALT = "privacy.duresspin.salt"
    private const val DURESS_ITER = "privacy.duresspin.iter"
    private const val DURESS_LEGACY = "privacy.duresspin"
    private const val DURESS_ITERATIONS = 210_000

    val hasDuressPin: Boolean
        get() {
            migrateLegacyDuressPin()
            return prefs.getString(DURESS_HASH, null) != null
        }

    private val duressExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "kryptos-duress").apply { isDaemon = true }
    }

    fun setDuressPinAsync(pin: String) {
        duressExecutor.execute { setDuressPin(pin) }
    }

    fun setDuressPin(pin: String) {
        if (pin.isEmpty()) {
            prefs.edit().remove(DURESS_HASH).remove(DURESS_SALT).remove(DURESS_ITER).remove(DURESS_LEGACY).apply()
            return
        }
        val salt = randomBytes(16)
        prefs.edit()
            .putString(DURESS_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(DURESS_HASH, Base64.encodeToString(duressPbkdf2(salt, pin), Base64.NO_WRAP))
            .putInt(DURESS_ITER, DURESS_ITERATIONS)
            .remove(DURESS_LEGACY)
            .apply()
    }

    fun checkDuressPin(pin: String): Boolean {
        migrateLegacyDuressPin()
        val salt = prefs.getString(DURESS_SALT, null) ?: return false
        val stored = prefs.getString(DURESS_HASH, null) ?: return false
        val expected = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return false
        val saltBytes = runCatching { Base64.decode(salt, Base64.NO_WRAP) }.getOrNull() ?: return false
        val digest = if (prefs.contains(DURESS_ITER)) {
            duressPbkdf2(saltBytes, pin, prefs.getInt(DURESS_ITER, DURESS_ITERATIONS))
        } else {
            duressLegacySha(saltBytes, pin)
        }
        return MessageDigest.isEqual(digest, expected)
    }

    private fun duressPbkdf2(salt: ByteArray, pin: String, iterations: Int = DURESS_ITERATIONS): ByteArray =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iterations.coerceAtLeast(1), 256))
            .encoded

    private fun duressLegacySha(salt: ByteArray, pin: String): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(salt) }.digest(pin.toByteArray(Charsets.UTF_8))

    private fun migrateLegacyDuressPin() {
        val legacy = prefs.getString(DURESS_LEGACY, null) ?: return
        if (legacy.isNotEmpty()) setDuressPin(legacy) else prefs.edit().remove(DURESS_LEGACY).apply()
    }
}
