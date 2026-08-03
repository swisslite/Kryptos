package com.kryptos.android.signal

import android.util.Base64
import com.kryptos.android.core.Argon2id
import com.kryptos.android.core.StegoLanguage
import com.kryptos.android.core.StegoMode
import com.kryptos.android.core.randomBytes
import com.kryptos.android.store.SecureStore
import java.security.MessageDigest

object AppSettingsStore {
    private val prefs get() = SecureStore.prefs()

    private const val OBSOLETE_HANDLED_CLIP = "kb.clip.handled"

    init {
        runCatching {
            if (prefs.contains(OBSOLETE_HANDLED_CLIP)) prefs.edit().remove(OBSOLETE_HANDLED_CLIP).apply()
        }
    }

    var chatStegoEnabled: Boolean
        get() = prefs.getBoolean("stego.enabled", false)
        set(v) { prefs.edit().putBoolean("stego.enabled", v).apply() }

    var chatStegoLanguage: String
        get() = prefs.getString("stego.lang", "auto") ?: "auto"
        set(v) { prefs.edit().putString("stego.lang", v).apply() }

    var chatStegoMode: StegoMode
        get() = StegoMode.resolve(prefs.getString("stego.mode", null), prefs.getBoolean("stego.smart", false))
        set(v) {
            prefs.edit()
                .putString("stego.mode", v.key)
                .putBoolean("stego.smart", v == StegoMode.SMART)
                .apply()
        }

    fun resolvedStegoLanguage(): StegoLanguage? {
        if (!chatStegoEnabled) return null
        return when (chatStegoLanguage) {
            "english" -> StegoLanguage.ENGLISH
            "russian" -> StegoLanguage.RUSSIAN
            else -> StegoLanguage.forSystem()
        }
    }

    fun resolvedStegoMode(): StegoMode = if (chatStegoEnabled) chatStegoMode else StegoMode.WORDS

    var keyboardHaptics: Boolean
        get() = prefs.getBoolean("kb.haptics", true)
        set(v) { prefs.edit().putBoolean("kb.haptics", v).apply() }

    var keyboardSounds: Boolean
        get() = prefs.getBoolean("kb.sounds", true)
        set(v) { prefs.edit().putBoolean("kb.sounds", v).apply() }

    var keyboardCompose: Boolean
        get() = prefs.getBoolean("kb.compose", false)
        set(v) { prefs.edit().putBoolean("kb.compose", v).apply() }

    var keyboardComposeToggle: Boolean
        get() = prefs.getBoolean("kb.composetoggle", true)
        set(v) { prefs.edit().putBoolean("kb.composetoggle", v).apply() }

    var keyboardAutoDecrypt: Boolean
        get() = prefs.getBoolean("kb.autodecrypt", true)
        set(v) { prefs.edit().putBoolean("kb.autodecrypt", v).apply() }

    @Volatile var keyboardHandledClip: String? = null

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

    enum class AppTab(val key: String) {
        CHATS("chats"), PGP("pgp"), QUICK("quick"), STEGO("stego"), SETTINGS("settings");

        val canHide: Boolean get() = this != SETTINGS

        companion object {
            fun of(key: String): AppTab? = entries.firstOrNull { it.key == key }
        }
    }

    data class UiState(
        val theme: String = "auto",
        val language: String = "auto",
        val hiddenTabs: Set<AppTab> = emptySet(),
    ) {
        val visibleTabs: List<AppTab>
            get() = AppTab.entries.filter { !it.canHide || it !in hiddenTabs }
    }

    private const val UI_THEME = "ui.theme"
    private const val UI_LANG = "ui.lang"
    private const val UI_TABS_HIDDEN = "ui.tabs.hidden"

    private fun readUiState(): UiState = runCatching {
        UiState(
            theme = prefs.getString(UI_THEME, "auto") ?: "auto",
            language = prefs.getString(UI_LANG, "auto") ?: "auto",
            hiddenTabs = (prefs.getString(UI_TABS_HIDDEN, "") ?: "")
                .split(',').mapNotNull { AppTab.of(it.trim()) }
                .filter { it.canHide }
                .toSet(),
        )
    }.getOrDefault(UiState())

    val ui = kotlinx.coroutines.flow.MutableStateFlow(UiState())

    fun loadUiState() { ui.value = readUiState() }

    var uiTheme: String
        get() = ui.value.theme
        set(v) {
            prefs.edit().putString(UI_THEME, v).apply()
            ui.value = ui.value.copy(theme = v)
        }

    var uiLanguage: String
        get() = ui.value.language
        set(v) {
            prefs.edit().putString(UI_LANG, v).apply()
            ui.value = ui.value.copy(language = v)
        }

    fun storedLanguage(): String = runCatching { prefs.getString(UI_LANG, "auto") ?: "auto" }.getOrDefault("auto")

    fun setTabHidden(tab: AppTab, hidden: Boolean) {
        if (!tab.canHide) return
        val next = if (hidden) ui.value.hiddenTabs + tab else ui.value.hiddenTabs - tab
        if (next.size >= AppTab.entries.count { it.canHide }) return
        prefs.edit().putString(UI_TABS_HIDDEN, next.joinToString(",") { it.key }).apply()
        ui.value = ui.value.copy(hiddenTabs = next)
    }

    const val CODE_MIN_LENGTH = 4

    enum class CodeResult { OK, TOO_SHORT, DUPLICATE, FAILED }

    private const val DURESS_BLOB = "duress"
    private const val APPCODE_BLOB = "appcode"
    private const val DURESS_OBSOLETE_HASH = "privacy.duresspin.argon2.hash"
    private const val DURESS_OBSOLETE_SALT = "privacy.duresspin.argon2.salt"
    private const val DURESS_LEGACY_KEYS = "privacy.duresspin.hash;privacy.duresspin.salt;privacy.duresspin.iter;privacy.duresspin"
    private const val DURESS_HASH_LENGTH = 32
    private const val BLOB_LENGTH = Argon2id.MIN_SALT_LENGTH + DURESS_HASH_LENGTH

    @Volatile private var duressPresent: Boolean? = null
    @Volatile private var appCodePresent: Boolean? = null

    fun invalidateCaches() {
        duressPresent = null
        appCodePresent = null
        keyboardHandledClip = null
        loadUiState()
    }

    private fun codeHash(salt: ByteArray, code: String): ByteArray =
        Argon2id.derive(code.toByteArray(Charsets.UTF_8), salt, DURESS_HASH_LENGTH)

    private fun codeStored(name: String): Boolean =
        runCatching { SecureStore.read(name)?.size == BLOB_LENGTH }.getOrDefault(false)

    private fun writeCode(name: String, code: String): Boolean {
        val salt = randomBytes(Argon2id.MIN_SALT_LENGTH)
        val digest = codeHash(salt, code)
        val stored = runCatching { SecureStore.write(name, salt + digest); true }.getOrDefault(false)
        digest.fill(0)
        return stored
    }

    private val DUMMY_BLOB = ByteArray(BLOB_LENGTH)

    private fun verifyCode(name: String, code: String): Boolean {
        val blob = runCatching { SecureStore.read(name) }.getOrNull()
        val present = blob != null && blob.size == BLOB_LENGTH
        val source = if (present) blob!! else DUMMY_BLOB
        val salt = source.copyOfRange(0, Argon2id.MIN_SALT_LENGTH)
        val expected = source.copyOfRange(Argon2id.MIN_SALT_LENGTH, source.size)
        val digest = codeHash(salt, code)
        val equal = MessageDigest.isEqual(digest, expected)
        digest.fill(0)
        expected.fill(0)
        blob?.fill(0)
        return present && equal
    }

    data class CodeCheck(val panic: Boolean, val app: Boolean)

    fun verifyCodes(code: String): CodeCheck {
        migrateDuressPin()
        if (code.length < CODE_MIN_LENGTH) return CodeCheck(panic = false, app = false)
        val panicMatch = verifyCode(DURESS_BLOB, code)
        val appMatch = verifyCode(APPCODE_BLOB, code)
        return CodeCheck(panic = panicMatch, app = appMatch)
    }

    val hasPanicPassword: Boolean
        get() {
            migrateDuressPin()
            duressPresent?.let { return it }
            return codeStored(DURESS_BLOB).also { duressPresent = it }
        }

    val hasAppCode: Boolean
        get() {
            appCodePresent?.let { return it }
            return codeStored(APPCODE_BLOB).also { appCodePresent = it }
        }

    fun setPanicPassword(code: String): CodeResult {
        migrateDuressPin()
        if (code.length < CODE_MIN_LENGTH) return CodeResult.TOO_SHORT
        if (verifyCode(APPCODE_BLOB, code)) return CodeResult.DUPLICATE
        val stored = writeCode(DURESS_BLOB, code)
        duressPresent = stored
        return if (stored) CodeResult.OK else CodeResult.FAILED
    }

    fun setAppCode(code: String): CodeResult {
        if (code.length < CODE_MIN_LENGTH) return CodeResult.TOO_SHORT
        if (verifyCode(DURESS_BLOB, code)) return CodeResult.DUPLICATE
        val stored = writeCode(APPCODE_BLOB, code)
        appCodePresent = stored
        return if (stored) CodeResult.OK else CodeResult.FAILED
    }

    fun clearPanicPassword() {
        migrateDuressPin()
        runCatching { SecureStore.delete(DURESS_BLOB) }
        duressPresent = false
    }

    fun clearAppCode() {
        runCatching { SecureStore.delete(APPCODE_BLOB) }
        appCodePresent = false
    }

    fun purgeLegacyRecords() = migrateDuressPin()

    @Synchronized private fun migrateDuressPin() {
        val legacy = DURESS_LEGACY_KEYS.split(";")
        val stale = legacy.any { prefs.contains(it) } ||
            prefs.contains(DURESS_OBSOLETE_HASH) || prefs.contains(DURESS_OBSOLETE_SALT)
        if (!stale) return
        val hash = prefs.getString(DURESS_OBSOLETE_HASH, null)
        val salt = prefs.getString(DURESS_OBSOLETE_SALT, null)
        if (hash != null && salt != null) {
            val h = runCatching { Base64.decode(hash, Base64.NO_WRAP) }.getOrNull()
            val sBytes = runCatching { Base64.decode(salt, Base64.NO_WRAP) }.getOrNull()
            if (h != null && sBytes != null && sBytes.size == Argon2id.MIN_SALT_LENGTH &&
                h.size == DURESS_HASH_LENGTH
            ) {
                runCatching { SecureStore.write(DURESS_BLOB, sBytes + h) }
                duressPresent = null
            }
        }
        val editor = prefs.edit()
        legacy.forEach { editor.remove(it) }
        editor.remove(DURESS_OBSOLETE_HASH).remove(DURESS_OBSOLETE_SALT)
        editor.commit()
    }
}
