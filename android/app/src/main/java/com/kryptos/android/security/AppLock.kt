package com.kryptos.android.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kryptos.android.signal.AppSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow

object AppLock {
    val locked = MutableStateFlow(false)
    val shielded = MutableStateFlow(false)

    @Volatile private var sessionValidated = false

    private var backgroundedAt = 0L
    private var authInFlight = false

    fun canUseLock(context: android.content.Context): Boolean {
        val bm = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    @Volatile var hasLaunched = false
        private set

    fun onLaunch(context: android.content.Context) {
        hasLaunched = true
        val armed = AppSettingsStore.appLock && canUseLock(context)
        locked.value = armed
        if (!armed) sessionValidated = true
    }

    fun isCryptoSessionLocked(context: android.content.Context): Boolean {
        if (!AppSettingsStore.appLock) return false
        if (sessionValidated && !locked.value) return false
        return canUseLock(context)
    }

    fun onBackground() {
        backgroundedAt = System.currentTimeMillis()
        shielded.value = true
    }

    fun onForeground(context: android.content.Context) {
        shielded.value = false
        if (!AppSettingsStore.appLock || !canUseLock(context)) return
        if (authInFlight || locked.value) return
        val grace = AppSettingsStore.autoLockGraceSeconds * 1000L
        if (backgroundedAt != 0L && System.currentTimeMillis() - backgroundedAt >= grace) {
            locked.value = true
        }
    }

    fun prompt(activity: FragmentActivity) {
        if (authInFlight || !locked.value) return
        authInFlight = true
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authInFlight = false
                backgroundedAt = 0L
                sessionValidated = true
                locked.value = false
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authInFlight = false
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Kryptos")
            .setSubtitle("Unlock Kryptos")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { authInFlight = false }
    }

    fun tryDuress(pin: String, onWiped: () -> Unit): Boolean {
        if (pin.isEmpty() || !AppSettingsStore.checkDuressPin(pin)) return false
        com.kryptos.android.signal.SignalService.eraseEverything()
        com.kryptos.android.pgp.PgpService.eraseAllStorage()
        sessionValidated = true
        onWiped()
        return true
    }
}
