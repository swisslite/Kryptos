package com.kryptos.android.store

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@SuppressLint("StaticFieldLeak")
object SecureStore {
    private const val KEY_ALIAS = "kryptos.master"
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

    @Volatile private var cachedKey: SecretKey? = null

    private fun masterKey(): SecretKey {
        cachedKey?.let { return it }
        return resolveMasterKey().also { cachedKey = it }
    }

    private fun hasStoredData(): Boolean =
        dir().listFiles()?.any { it.isFile && !it.name.endsWith(".tmp") } == true

    private fun resolveMasterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (hasStoredData()) {
            throw IllegalStateException("Keystore master key is gone while encrypted data is present")
        }
        val deviceSecure = runCatching {
            (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
        }.getOrDefault(false)
        var last: Throwable? = null
        for (unlockedOnly in listOf(true, false)) {
            if (unlockedOnly && !deviceSecure) continue
            for (strongBox in listOf(true, false)) {
                try {
                    val key = generateMasterKey(strongBox, unlockedOnly)
                    selfTest(key)
                    return key
                } catch (t: Throwable) {
                    last = t
                    destroyMasterKey()
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
        dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, enc.iv))
        dec.doFinal(probe)
    }

    private fun generateMasterKey(strongBox: Boolean, unlockedOnly: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            if (unlockedOnly) spec.setUnlockedDeviceRequired(true)
            if (strongBox) spec.setIsStrongBoxBacked(true)
        } else if (strongBox) {
            throw IllegalStateException("StrongBox requires API 28")
        }
        generator.init(spec.build())
        return generator.generateKey()
    }

    @Synchronized
    fun destroyMasterKey() {
        cachedKey = null
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun <T> withMasterKey(body: (SecretKey) -> T): T =
        try {
            body(masterKey())
        } catch (first: Exception) {
            if (cachedKey == null) throw first
            cachedKey = null
            body(masterKey())
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
        return try {
            val blob = f.readBytes()
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            withMasterKey { key ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                cipher.doFinal(ct)
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun write(name: String, data: ByteArray) {
        val target = file(name)
        val blob = withMasterKey { key ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.iv + cipher.doFinal(data)
        }
        val tmp = File(dir(), "$name.tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(blob)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw java.io.IOException("SecureStore: cannot commit '$name'")
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

    fun prefs(): SharedPreferences = context.getSharedPreferences("kryptos.settings", Context.MODE_PRIVATE)
}
