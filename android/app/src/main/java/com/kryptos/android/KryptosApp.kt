package com.kryptos.android

import android.app.Application
import com.kryptos.android.keyboard.EmojiData
import com.kryptos.android.keyboard.SuggestionEngine
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.store.SecureStore
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class KryptosApp : Application() {
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
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
