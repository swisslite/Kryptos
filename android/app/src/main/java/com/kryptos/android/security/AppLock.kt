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

    private fun authenticators(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    fun canUseLock(context: android.content.Context): Boolean {
        val bm = BiometricManager.from(context)
        if (bm.canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS) return true
        val fallback = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return bm.canAuthenticate(fallback) == BiometricManager.BIOMETRIC_SUCCESS
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
            .setAllowedAuthenticators(promptAuthenticators(activity))
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { authInFlight = false }
    }

    private fun promptAuthenticators(context: android.content.Context): Int {
        val bm = BiometricManager.from(context)
        val strong = authenticators()
        if (bm.canAuthenticate(strong) == BiometricManager.BIOMETRIC_SUCCESS) return strong
        return BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    enum class CodeOutcome { REJECTED, UNLOCKED, WIPED }

    @Volatile private var codeFailures = 0

    fun codeThrottleMillis(): Long {
        val over = codeFailures - 4
        if (over <= 0) return 0L
        return minOf(30_000L, 1_000L shl minOf(over - 1, 5))
    }

    fun submitCode(context: android.content.Context, code: String): CodeOutcome {
        if (code.length < AppSettingsStore.CODE_MIN_LENGTH) return CodeOutcome.REJECTED
        val check = AppSettingsStore.verifyCodes(code)
        if (check.panic) {
            codeFailures = 0
            DataWipe.wipe(context)
            sessionValidated = true
            backgroundedAt = 0L
            return CodeOutcome.WIPED
        }
        if (check.app) {
            codeFailures = 0
            sessionValidated = true
            backgroundedAt = 0L
            return CodeOutcome.UNLOCKED
        }
        if (codeFailures < Int.MAX_VALUE) codeFailures++
        return CodeOutcome.REJECTED
    }
}
