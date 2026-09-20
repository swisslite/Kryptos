package com.kryptos.android.store

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@SuppressLint("StaticFieldLeak")
object SecureStore {
    private const val LEGACY_ALIAS = "kryptos.master"
    private const val UNLOCKED_ALIAS = "kryptos.master.u"
    private const val PLAIN_ALIAS = "kryptos.master.p"

    private val ALIASES = listOf(UNLOCKED_ALIAS, PLAIN_ALIAS, LEGACY_ALIAS)

    private const val TMP_SUFFIX = ".tmp"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private lateinit var context: Context

    fun init(appContext: Context) {
        context = appContext.applicationContext
    }

    fun appContext(): Context = context

    @Volatile private var storeDir: File? = null

    private fun dir(): File {
        storeDir?.let { if (it.isDirectory) return it }
        val resolved = File(context.filesDir, "kryptos")
        if (!resolved.isDirectory) resolved.mkdirs()
        storeDir = resolved
        return resolved
    }

    private fun file(name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' }) {
            "bad store key"
        }
        return File(dir(), name)
    }

    private class Master(val alias: String, val key: SecretKey)

    @Volatile private var cachedMaster: Master? = null

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun aliasKey(ks: KeyStore, alias: String): SecretKey? =
        runCatching { ks.getKey(alias, null) as? SecretKey }.getOrNull()

    private fun master(): Master {
        cachedMaster?.let { return it }
        return resolveMaster().also { cachedMaster = it }
    }

    private fun hasStoredData(): Boolean =
        dir().listFiles()?.any { it.isFile && !it.name.endsWith(TMP_SUFFIX) } == true

    private fun deviceSecure(): Boolean = runCatching {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
    }.getOrDefault(false)

    private fun unlockedPolicySupported(): Boolean = Build.VERSION.SDK_INT >= 28

    private fun resolveMaster(): Master {
        val ks = keystore()
        for (alias in ALIASES) aliasKey(ks, alias)?.let { return Master(alias, it) }
        if (hasStoredData()) {
            throw IllegalStateException("Keystore master key is gone while encrypted data is present")
        }
        return createMaster(deviceSecure() && unlockedPolicySupported())
    }

    private fun createMaster(preferUnlocked: Boolean): Master {
        var last: Throwable? = null
        for (unlockedOnly in listOf(true, false)) {
            if (unlockedOnly && !preferUnlocked) continue
            val alias = if (unlockedOnly) UNLOCKED_ALIAS else PLAIN_ALIAS
            for (strongBox in listOf(true, false)) {
                try {
                    val key = generateMasterKey(alias, strongBox, unlockedOnly)
                    selfTest(key)
                    return Master(alias, key)
                } catch (t: Throwable) {
                    last = t
                    deleteAlias(alias)
                }
            }
        }
        throw IllegalStateException("Keystore unavailable", last)
    }

    private fun selfTest(key: SecretKey) {
        val enc = Cipher.getInstance("AES/GCM/NoPadding")
        enc.init(Cipher.ENCRYPT_MODE, key)
        val probe = enc.doFinal(ByteArray(16))
        val dec = Cipher.getInstance("AES/GCM/NoPadding")
        dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, enc.iv))
        dec.doFinal(probe)
    }

    private fun generateMasterKey(alias: String, strongBox: Boolean, unlockedOnly: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= 28) {
            if (unlockedOnly) spec.setUnlockedDeviceRequired(true)
            if (strongBox) spec.setIsStrongBoxBacked(true)
        } else {
            if (strongBox) throw IllegalStateException("StrongBox requires API 28")
            if (unlockedOnly) throw IllegalStateException("Unlocked-device policy requires API 28")
        }
        generator.init(spec.build())
        return generator.generateKey()
    }

    private fun deleteAlias(alias: String) {
        runCatching { keystore().deleteEntry(alias) }
    }

    @Synchronized
    fun destroyMasterKey() {
        cachedMaster = null
        for (alias in ALIASES) deleteAlias(alias)
    }

    @Synchronized
    fun upgradeKeyPolicyIfNeeded(): Boolean {
        if (!unlockedPolicySupported() || !deviceSecure()) return false
        val ks = runCatching { keystore() }.getOrNull() ?: return false
        val stale = ALIASES.filter { it != UNLOCKED_ALIAS && aliasKey(ks, it) != null }
        if (stale.isEmpty()) return false

        val target = aliasKey(ks, UNLOCKED_ALIAS) ?: run {
            var made: SecretKey? = null
            for (strongBox in listOf(true, false)) {
                try {
                    val key = generateMasterKey(UNLOCKED_ALIAS, strongBox, true)
                    selfTest(key)
                    made = key
                    break
                } catch (t: Throwable) {
                    deleteAlias(UNLOCKED_ALIAS)
                }
            }
            made ?: return false
        }
        cachedMaster = Master(UNLOCKED_ALIAS, target)

        var complete = true
        for (f in dir().listFiles().orEmpty()) {
            if (!f.isFile) continue
            if (f.name.endsWith(TMP_SUFFIX)) {
                f.delete()
                continue
            }
            val plain = decryptFile(f)
            if (plain == null) {
                complete = false
                continue
            }
            val rewritten = runCatching { writeSealed(f.name, seal(target, plain)) }.isSuccess
            plain.fill(0)
            if (!rewritten) complete = false
        }
        if (!complete) return false
        for (alias in stale) deleteAlias(alias)
        return true
    }

    @Synchronized
    fun read(name: String): ByteArray? = decryptFile(file(name))

    @Synchronized
    fun readStrict(name: String): ByteArray? {
        val f = file(name)
        if (!f.exists()) return null
        return decryptFile(f)
            ?: throw IllegalStateException("SecureStore: '$name' exists but cannot be decrypted (device locked or Keystore unavailable)")
    }

    private fun decryptFile(f: File): ByteArray? {
        if (!f.exists()) return null
        val blob = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (blob.size <= IV_LENGTH) return null
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ct = blob.copyOfRange(IV_LENGTH, blob.size)
        val active = runCatching { master() }.getOrNull()
        if (active != null) open(active.key, iv, ct)?.let { return it }
        val ks = runCatching { keystore() }.getOrNull() ?: return null
        for (alias in ALIASES) {
            if (alias == active?.alias) continue
            val key = aliasKey(ks, alias) ?: continue
            open(key, iv, ct)?.let { return it }
        }
        return null
    }

    private fun open(key: SecretKey, iv: ByteArray, ct: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(ct)
    }.getOrNull()

    private fun seal(key: SecretKey, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(data)
    }

    @Synchronized
    fun write(name: String, data: ByteArray) {
        val target = file(name)
        val blob = try {
            seal(master().key, data)
        } catch (first: Exception) {
            if (cachedMaster == null) throw first
            cachedMaster = null
            seal(master().key, data)
        }
        writeSealed(target.name, blob)
    }

    private fun writeSealed(name: String, blob: ByteArray) {
        val target = file(name)
        val tmp = File(dir(), "$name$TMP_SUFFIX")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(blob)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("SecureStore: cannot commit '$name'")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    @Synchronized
    fun exists(name: String): Boolean = file(name).exists()

    @Synchronized
    fun delete(name: String) {
        file(name).delete()
    }

    private val OBSOLETE_KEYS = listOf("engine.check")

    @Synchronized
    fun purgeObsolete() {
        for (name in OBSOLETE_KEYS) file(name).delete()
    }

    @Synchronized
    fun deleteAll() {
        dir().listFiles()?.forEach { it.delete() }
        destroyMasterKey()
    }

    private const val SETTINGS_KEY = "settings"

    private val LEGACY_SETTING_KEYS = setOf(
        "kb.learned.words",
        "kb.learned.bigrams",
        "kb.emoji.recents",
        "kb.clip.handled",
        "privacy.duresspin",
        "privacy.duresspin.hash",
        "privacy.duresspin.salt",
        "privacy.duresspin.iter",
        "privacy.duresspin.argon2.hash",
        "privacy.duresspin.argon2.salt",
    )

    private val securePrefs: SecurePrefs by lazy {
        SecurePrefs(SETTINGS_KEY, ::legacyPrefs, LEGACY_SETTING_KEYS)
    }

    fun prefs(): SharedPreferences = securePrefs

    fun settingsReadable(): Boolean = runCatching { securePrefs.isReady() }.getOrDefault(false)

    fun legacyPrefs(): SharedPreferences =
        context.getSharedPreferences("kryptos.settings", Context.MODE_PRIVATE)

    fun retireLegacyPrefs() {
        if (!exists(SETTINGS_KEY)) return
        runCatching {
            val old = legacyPrefs()
            if (old.all.isNotEmpty()) old.edit().clear().commit()
        }
    }

    fun eraseSettings() {
        runCatching { prefs().edit().clear().commit() }
        runCatching { legacyPrefs().edit().clear().commit() }
    }
}
