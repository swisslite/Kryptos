package com.kryptos.android

import android.app.Application
import com.kryptos.android.keyboard.EmojiData
import com.kryptos.android.keyboard.SuggestionEngine
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.store.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class KryptosApp : Application() {

    companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    override fun onCreate() {
        super.onCreate()
        SecureStore.init(this)
        runCatching { AppLanguage.applyProcessLocale() }
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
        purgeLegacyPlaintext()
    }

    private fun purgeLegacyPlaintext() {
        Thread {
            runCatching { AppSettingsStore.purgeLegacyRecords() }
            runCatching { SuggestionEngine.migrateLegacyPlaintext() }
            runCatching { EmojiData.migrateLegacy() }
            runCatching { SecureStore.purgeObsolete() }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
