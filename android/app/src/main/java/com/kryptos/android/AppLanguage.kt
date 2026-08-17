package com.kryptos.android

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.kryptos.android.signal.AppSettingsStore
import java.util.Locale

object AppLanguage {
    val supported = listOf("en", "ru", "de", "zh")

    fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

    fun selected(): Locale {
        val code = runCatching { AppSettingsStore.storedLanguage() }.getOrDefault("auto")
        return if (code in supported) Locale.forLanguageTag(code) else systemLocale()
    }

    fun applyProcessLocale() {
        Locale.setDefault(selected())
    }

    fun wrap(base: Context): Context {
        val locale = selected()
        Locale.setDefault(locale)
        if (locale == base.resources.configuration.locales[0]) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
