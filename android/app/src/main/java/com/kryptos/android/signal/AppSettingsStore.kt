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

    fun settingsAvailable(): Boolean = SecureStore.settingsReadable()

    private const val OBSOLETE_HANDLED_CLIP = "kb.clip.handled"

    init {
        runCatching {
            val old = SecureStore.legacyPrefs()
            if (old.contains(OBSOLETE_HANDLED_CLIP)) old.edit().remove(OBSOLETE_HANDLED_CLIP).apply()
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
            "german" -> StegoLanguage.GERMAN
            "chinese" -> StegoLanguage.CHINESE
            "persian" -> StegoLanguage.PERSIAN
            "portuguese" -> StegoLanguage.PORTUGUESE
            else -> StegoLanguage.forSystem()
        }
    }

    fun resolvedStegoMode(): StegoMode = if (chatStegoEnabled) chatStegoMode else StegoMode.WORDS

    var keyboardHaptics: Boolean
        get() = prefs.getBoolean("kb.haptics", true)
        set(v) { prefs.edit().putBoolean("kb.haptics", v).apply() }

    enum class Vibration(val key: String, val durationMs: Long, val amplitude: Int, val scale: Float) {
        LIGHT("light", 14L, 110, 0.65f),
        MEDIUM("medium", 18L, 165, 0.6f),
        STRONG("strong", 22L, 235, 1f);

        companion object {
            fun resolve(raw: String?): Vibration = entries.firstOrNull { it.key == raw } ?: LIGHT
        }
    }

    var keyboardVibration: Vibration
        get() = Vibration.resolve(prefs.getString("kb.vibration", null))
        set(v) { prefs.edit().putString("kb.vibration", v.key).apply() }

    var keyboardSounds: Boolean
        get() = prefs.getBoolean("kb.sounds", true)
        set(v) { prefs.edit().putBoolean("kb.sounds", v).apply() }

    var keyboardCompose: Boolean
        get() = prefs.getBoolean("kb.compose", false)
        set(v) { prefs.edit().putBoolean("kb.compose", v).apply() }

    var keyboardComposeAuto: Boolean
        get() = prefs.getBoolean("kb.composeauto", false)
        set(v) { prefs.edit().putBoolean("kb.composeauto", v).apply() }

    private const val COMPOSE_APPS = "kb.composeapps"

    @Volatile private var composeAppsCache: Set<String>? = null

    fun composeAutoApps(): Set<String> {
        composeAppsCache?.let { return it }
        val stored = runCatching { SecureStore.read(COMPOSE_APPS)?.toString(Charsets.UTF_8) }.getOrNull()
        val apps = stored?.lineSequence()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        composeAppsCache = apps
        return apps
    }

    fun setComposeAutoApps(apps: Set<String>) {
        val cleaned = apps.filter { it.isNotBlank() }.sorted()
        runCatching {
            if (cleaned.isEmpty()) SecureStore.delete(COMPOSE_APPS)
            else SecureStore.write(COMPOSE_APPS, cleaned.joinToString("\n").toByteArray(Charsets.UTF_8))
        }
        composeAppsCache = cleaned.toSet()
    }

    var keyboardComposeToggle: Boolean
        get() = prefs.getBoolean("kb.composetoggle", true)
        set(v) { prefs.edit().putBoolean("kb.composetoggle", v).apply() }

    var keyboardShield: Boolean
        get() = prefs.getBoolean("kb.shield", true)
        set(v) { prefs.edit().putBoolean("kb.shield", v).apply() }

    enum class FieldSize(val key: String, val heightDp: Float) {
        SMALL("small", 50f),
        MEDIUM("medium", 86f),
        LARGE("large", 122f);

        companion object {
            fun resolve(raw: String?): FieldSize = entries.firstOrNull { it.key == raw } ?: SMALL
        }
    }

    var keyboardFieldSize: FieldSize
        get() = FieldSize.resolve(prefs.getString("kb.fieldsize", null))
        set(v) { prefs.edit().putString("kb.fieldsize", v.key).apply() }

    enum class KeySize(val key: String, val labelScale: Float) {
        SMALL("small", 0.85f),
        MEDIUM("medium", 1f),
        LARGE("large", 1.15f);

        companion object {
            fun resolve(raw: String?): KeySize = entries.firstOrNull { it.key == raw } ?: MEDIUM
        }
    }

    var keyboardKeySize: KeySize
        get() = KeySize.resolve(prefs.getString("kb.keysize", null))
        set(v) { prefs.edit().putString("kb.keysize", v.key).apply() }

    var keyboardKeyPreview: Boolean
        get() = prefs.getBoolean("kb.keypreview", true)
        set(v) { prefs.edit().putBoolean("kb.keypreview", v).apply() }

    var keyboardAutoDecrypt: Boolean
        get() = prefs.getBoolean("kb.autodecrypt", true)
        set(v) { prefs.edit().putBoolean("kb.autodecrypt", v).apply() }

    var keyboardSendAfterEncrypt: Boolean
        get() = prefs.getBoolean("kb.sendafter", false)
        set(v) { prefs.edit().putBoolean("kb.sendafter", v).apply() }

    @Volatile var keyboardHandledClip: String? = null

    var keyboardSuggestions: Boolean
        get() = prefs.getBoolean("kb.suggestions", true)
        set(v) { prefs.edit().putBoolean("kb.suggestions", v).apply() }

    var keyboardAutocorrect: Boolean
        get() = prefs.getBoolean("kb.autocorrect", true)
        set(v) { prefs.edit().putBoolean("kb.autocorrect", v).apply() }

    var keyboardAutoCaps: Boolean
        get() = prefs.getBoolean("kb.autocaps", true)
        set(v) { prefs.edit().putBoolean("kb.autocaps", v).apply() }

    var keyboardEmoji: Boolean
        get() = prefs.getBoolean("kb.emoji", true)
        set(v) { prefs.edit().putBoolean("kb.emoji", v).apply() }

    var keyboardPunctKey: Boolean
        get() = prefs.getBoolean("kb.punct", true)
        set(v) { prefs.edit().putBoolean("kb.punct", v).apply() }

    var keyboardPunctDouble: Boolean
        get() = prefs.getBoolean("kb.punctdouble", false)
        set(v) { prefs.edit().putBoolean("kb.punctdouble", v).apply() }

    enum class VoiceEngine(val key: String) {
        SYSTEM("system");

        companion object {
            fun resolve(raw: String?): VoiceEngine = entries.firstOrNull { it.key == raw } ?: SYSTEM
        }
    }

    var backupChats: Boolean
        get() = prefs.getBoolean("backup.chats", false)
        set(v) { prefs.edit().putBoolean("backup.chats", v).apply() }

    var keyboardVoice: Boolean
        get() = prefs.getBoolean("kb.voice", false)
        set(v) { prefs.edit().putBoolean("kb.voice", v).apply() }

    var keyboardVoiceEngine: VoiceEngine
        get() = VoiceEngine.resolve(prefs.getString("kb.voice.engine", null))
        set(v) { prefs.edit().putString("kb.voice.engine", v.key).apply() }

    val systemKeyboardLang: String
        get() {
            val tag = java.util.Locale.getDefault().language
            return if (tag == "ru" || tag == "de" || tag == "zh" || tag == "fa" || tag == "pt") tag else "en"
        }

    private val nonLatinLanguages = setOf("ru", "zh", "fa")

    fun keyboardLangEnabled(code: String): Boolean {
        val sys = systemKeyboardLang
        val byDefault = code == sys || (code == "en" && sys in nonLatinLanguages)
        return prefs.getBoolean("kb.lang.$code", byDefault)
    }

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

    fun clearKeyboardContact(profileId: String, fingerprint: String) {
        if (profileId.isEmpty() || keyboardContact(profileId) != fingerprint) return
        clearKeyboardContact(profileId)
    }

    var privacyShield: Boolean
        get() = prefs.getBoolean("privacy.shield", true)
        set(v) { prefs.edit().putBoolean("privacy.shield", v).apply() }

    var clipboardAutoDecrypt: Boolean
        get() = prefs.getBoolean("privacy.clipauto", true)
        set(v) { prefs.edit().putBoolean("privacy.clipauto", v).apply() }

    var clipboardClearSeconds: Int
        get() = prefs.getInt("privacy.clipclear", 60)
        set(v) { prefs.edit().putInt("privacy.clipclear", v).apply() }

    var appLock: Boolean
        get() = prefs.getBoolean("privacy.applock", false)
        set(v) { prefs.edit().putBoolean("privacy.applock", v).apply() }

    var appLockCodeOnly: Boolean
        get() = prefs.getBoolean("privacy.applock.codeonly", false)
        set(v) { prefs.edit().putBoolean("privacy.applock.codeonly", v).apply() }

    var autoLockGraceSeconds: Int
        get() = prefs.getInt("privacy.lockgrace", 0)
        set(v) { prefs.edit().putInt("privacy.lockgrace", v).apply() }

    var secureKeyboard: Boolean
        get() = prefs.getBoolean("privacy.securekb", false)
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

    private const val CODE_FAILURES = "privacy.codefails"

    @Volatile private var codeFailuresFloor = 0

    var codeFailures: Int
        get() = maxOf(runCatching { prefs.getInt(CODE_FAILURES, 0) }.getOrDefault(0), codeFailuresFloor)
        set(v) {
            val next = v.coerceAtLeast(0)
            codeFailuresFloor = if (next == 0) 0 else maxOf(codeFailuresFloor, next)
            runCatching { prefs.edit().putInt(CODE_FAILURES, next).commit() }
        }

    enum class CodeResult { OK, TOO_SHORT, DUPLICATE, FAILED }

    private const val CODES_BLOB = "codes"
    private const val LEGACY_DURESS_BLOB = "duress"
    private const val LEGACY_APPCODE_BLOB = "appcode"
    private const val DURESS_OBSOLETE_HASH = "privacy.duresspin.argon2.hash"
    private const val DURESS_OBSOLETE_SALT = "privacy.duresspin.argon2.salt"
    private const val DURESS_LEGACY_KEYS = "privacy.duresspin.hash;privacy.duresspin.salt;privacy.duresspin.iter;privacy.duresspin"

    private val codesLock = Any()

    @Volatile private var duressPresent: Boolean? = null
    @Volatile private var appCodePresent: Boolean? = null

    fun invalidateCaches() {
        duressPresent = null
        appCodePresent = null
        composeAppsCache = null
        keyboardHandledClip = null
        loadUiState()
    }

    private class LegacyRecord(val record: ByteArray?)

    private fun legacyRecord(name: String): LegacyRecord? {
        val present = runCatching { SecureStore.exists(name) }.getOrNull() ?: return null
        if (!present) return LegacyRecord(null)
        val blob = runCatching { SecureStore.read(name) }.getOrNull() ?: return null
        return LegacyRecord(blob.takeIf { it.size == CodeSlots.RECORD })
    }

    private fun loadCodes(): CodeSlots.Records? = synchronized(codesLock) {
        val stored = try {
            SecureStore.readStrict(CODES_BLOB)
        } catch (e: Exception) {
            return null
        }
        if (stored != null) {
            return try {
                CodeSlots.decode(stored) ?: CodeSlots.Records(null, null)
            } finally {
                stored.fill(0)
            }
        }
        val panic = legacyRecord(LEGACY_DURESS_BLOB) ?: return null
        val app = legacyRecord(LEGACY_APPCODE_BLOB) ?: return null
        val records = CodeSlots.Records(panic.record, app.record)
        if (storeCodes(records)) {
            runCatching { SecureStore.delete(LEGACY_DURESS_BLOB) }
            runCatching { SecureStore.delete(LEGACY_APPCODE_BLOB) }
        }
        records
    }

    private fun storeCodes(records: CodeSlots.Records): Boolean {
        val encoded = CodeSlots.encode(records)
        return try {
            SecureStore.write(CODES_BLOB, encoded)
            true
        } catch (e: Exception) {
            false
        } finally {
            encoded.fill(0)
        }
    }

    private fun remember(records: CodeSlots.Records) {
        duressPresent = records.panic != null
        appCodePresent = records.app != null
    }

    private fun codeHash(salt: ByteArray, code: String): ByteArray =
        Argon2id.derive(code, salt, CodeSlots.HASH_LENGTH)

    private fun matches(record: ByteArray?, code: String): Boolean {
        val source = record ?: randomBytes(CodeSlots.RECORD)
        val salt = source.copyOfRange(0, Argon2id.MIN_SALT_LENGTH)
        val expected = source.copyOfRange(Argon2id.MIN_SALT_LENGTH, source.size)
        val digest = codeHash(salt, code)
        val equal = MessageDigest.isEqual(digest, expected)
        digest.fill(0)
        expected.fill(0)
        return record != null && equal
    }

    private fun newRecord(code: String): ByteArray {
        val salt = randomBytes(Argon2id.MIN_SALT_LENGTH)
        val digest = codeHash(salt, code)
        return (salt + digest).also { digest.fill(0) }
    }

    data class CodeCheck(val panic: Boolean, val app: Boolean)

    fun verifyCodes(code: String): CodeCheck {
        migrateDuressPin()
        if (code.length < CODE_MIN_LENGTH) return CodeCheck(panic = false, app = false)
        val records = loadCodes()
        return try {
            CodeCheck(panic = matches(records?.panic, code), app = matches(records?.app, code))
        } finally {
            records?.wipe()
        }
    }

    val hasPanicPassword: Boolean
        get() {
            migrateDuressPin()
            duressPresent?.let { return it }
            val records = loadCodes() ?: return false
            remember(records)
            records.wipe()
            return duressPresent == true
        }

    val hasAppCode: Boolean
        get() {
            appCodePresent?.let { return it }
            val records = loadCodes() ?: return false
            remember(records)
            records.wipe()
            return appCodePresent == true
        }

    fun setPanicPassword(code: String): CodeResult {
        migrateDuressPin()
        if (code.length < CODE_MIN_LENGTH) return CodeResult.TOO_SHORT
        return setCode(code, panic = true)
    }

    fun setAppCode(code: String): CodeResult {
        if (code.length < CODE_MIN_LENGTH) return CodeResult.TOO_SHORT
        return setCode(code, panic = false)
    }

    private fun setCode(code: String, panic: Boolean): CodeResult = synchronized(codesLock) {
        val records = loadCodes() ?: return CodeResult.FAILED
        try {
            if (matches(if (panic) records.app else records.panic, code)) return CodeResult.DUPLICATE
            val record = newRecord(code)
            val next = if (panic) CodeSlots.Records(record, records.app) else CodeSlots.Records(records.panic, record)
            val stored = storeCodes(next)
            record.fill(0)
            if (!stored) {
                duressPresent = null
                appCodePresent = null
                return CodeResult.FAILED
            }
            remember(next)
            CodeResult.OK
        } finally {
            records.wipe()
        }
    }

    fun clearPanicPassword() {
        migrateDuressPin()
        clearCode(panic = true)
    }

    fun clearAppCode() = clearCode(panic = false)

    private fun clearCode(panic: Boolean): Unit = synchronized(codesLock) {
        val records = loadCodes()
        if (records == null) {
            duressPresent = null
            appCodePresent = null
            return
        }
        val next = if (panic) CodeSlots.Records(null, records.app) else CodeSlots.Records(records.panic, null)
        if (storeCodes(next)) remember(next) else {
            duressPresent = null
            appCodePresent = null
        }
        records.wipe()
    }

    fun resealCodes(): Unit = synchronized(codesLock) {
        migrateDuressPin()
        val records = loadCodes() ?: return
        storeCodes(records)
        records.wipe()
    }

    private fun migrateDuressPin(): Unit = synchronized(codesLock) {
        val prefs = SecureStore.legacyPrefs()
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
                h.size == CodeSlots.HASH_LENGTH
            ) {
                val records = loadCodes() ?: return
                val stored = storeCodes(CodeSlots.Records(sBytes + h, records.app))
                records.wipe()
                if (!stored) return
                duressPresent = null
            }
        }
        val editor = prefs.edit()
        legacy.forEach { editor.remove(it) }
        editor.remove(DURESS_OBSOLETE_HASH).remove(DURESS_OBSOLETE_SALT)
        editor.commit()
    }
}

internal object CodeSlots {
    const val HASH_LENGTH = 32
    const val RECORD = Argon2id.MIN_SALT_LENGTH + HASH_LENGTH
    private const val SLOT = 1 + RECORD
    const val SIZE = 2 * SLOT

    class Records(val panic: ByteArray?, val app: ByteArray?) {
        fun wipe() {
            panic?.fill(0)
            app?.fill(0)
        }
    }

    fun encode(records: Records): ByteArray {
        val out = ByteArray(SIZE)
        put(out, 0, records.panic)
        put(out, SLOT, records.app)
        return out
    }

    fun decode(bytes: ByteArray): Records? {
        if (bytes.size != SIZE) return null
        return Records(slot(bytes, 0), slot(bytes, SLOT))
    }

    private fun put(out: ByteArray, at: Int, record: ByteArray?) {
        if (record != null && record.size == RECORD) {
            out[at] = 1
            record.copyInto(out, at + 1)
        } else {
            randomBytes(RECORD).copyInto(out, at + 1)
        }
    }

    private fun slot(bytes: ByteArray, at: Int): ByteArray? =
        if (bytes[at].toInt() == 1) bytes.copyOfRange(at + 1, at + SLOT) else null
}
