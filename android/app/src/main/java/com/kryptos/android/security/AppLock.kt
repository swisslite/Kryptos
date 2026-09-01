package com.kryptos.android.security

import android.os.SystemClock
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.store.SecureStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

object AppLock {
    val locked = MutableStateFlow(false)
    val shielded = MutableStateFlow(false)

    @Volatile private var sessionValidated = false
    @Volatile private var sessionRestored = false

    private var backgroundedAt = 0L
    private var authInFlight = false

    private const val OWN_SCREEN_LAUNCH_MS = 10L * 1000
    private const val OWN_SCREEN_AWAY_MS = 5L * 60 * 1000

    @Volatile private var ownScreenAt = 0L
    @Volatile private var leftForOwnScreen = false
    private var leftAt = 0L

    fun onOwnScreen() {
        ownScreenAt = SystemClock.elapsedRealtime()
    }

    fun leftForOwnScreen(launchedAt: Long, leftAt: Long): Boolean =
        launchedAt != 0L && (leftAt - launchedAt) in 0 until OWN_SCREEN_LAUNCH_MS

    fun returningFromOwnScreen(leftForOwnScreen: Boolean, leftAt: Long, now: Long): Boolean =
        leftForOwnScreen && (now - leftAt) in 0 until OWN_SCREEN_AWAY_MS

    private fun authenticators(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    @Volatile private var lockAvailable: Boolean? = null

    fun refreshLockAvailability() {
        lockAvailable = null
    }

    fun canUseLock(context: android.content.Context): Boolean {
        lockAvailable?.let { return it }
        val bm = BiometricManager.from(context)
        val value = if (bm.canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS) {
            true
        } else {
            val fallback = BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            bm.canAuthenticate(fallback) == BiometricManager.BIOMETRIC_SUCCESS
        }
        lockAvailable = value
        return value
    }

    fun lockUsable(context: android.content.Context): Boolean =
        if (AppSettingsStore.appLockCodeOnly) AppSettingsStore.hasAppCode else canUseLock(context)

    data class LockState(val enabled: Boolean, val codeOnly: Boolean)

    fun resolveLockState(state: LockState, canSystem: Boolean, appCodeSet: Boolean): LockState {
        val codeOnly = when {
            state.codeOnly && !appCodeSet -> false
            !state.codeOnly && appCodeSet && !canSystem -> true
            else -> state.codeOnly
        }
        val usable = if (codeOnly) appCodeSet else canSystem
        return LockState(state.enabled && usable, codeOnly)
    }

    fun lockState(): LockState = LockState(AppSettingsStore.appLock, AppSettingsStore.appLockCodeOnly)

    private fun applyLockState(next: LockState): LockState {
        if (AppSettingsStore.appLockCodeOnly != next.codeOnly) {
            AppSettingsStore.appLockCodeOnly = next.codeOnly
        }
        if (AppSettingsStore.appLock != next.enabled) {
            AppSettingsStore.appLock = next.enabled
            if (next.enabled) {
                if (sessionValidated) LockSession.open()
            } else {
                onLockDisabled()
            }
        }
        return next
    }

    fun syncLockState(context: android.content.Context, appCodeSet: Boolean): LockState =
        applyLockState(resolveLockState(lockState(), canUseLock(context), appCodeSet))

    fun setLockEnabled(context: android.content.Context, enabled: Boolean, appCodeSet: Boolean): LockState =
        applyLockState(
            resolveLockState(lockState().copy(enabled = enabled), canUseLock(context), appCodeSet),
        )

    fun setLockCodeOnly(context: android.content.Context, codeOnly: Boolean, appCodeSet: Boolean): LockState =
        applyLockState(
            resolveLockState(lockState().copy(codeOnly = codeOnly), canUseLock(context), appCodeSet),
        )

    @Volatile var hasLaunched = false
        private set

    fun onLaunch(context: android.content.Context) {
        hasLaunched = true
        refreshLockAvailability()
        val armed = AppSettingsStore.appLock && lockUsable(context)
        locked.value = armed
        if (armed) LockSession.close() else sessionValidated = true
    }

    fun isCryptoSessionLocked(context: android.content.Context): Boolean {
        if (!AppSettingsStore.appLock) return false
        restoreSession()
        if (sessionValidated && !locked.value) return false
        return lockUsable(context)
    }

    fun onLockDisabled() {
        LockSession.close()
    }

    private fun restoreSession() {
        if (sessionValidated || sessionRestored) return
        sessionRestored = true
        if (LockSession.isOpen()) sessionValidated = true
    }

    private fun openSession() {
        sessionValidated = true
        sessionRestored = true
        if (AppSettingsStore.appLock) LockSession.open()
    }

    fun onBackground() {
        backgroundedAt = System.currentTimeMillis()
        leftAt = SystemClock.elapsedRealtime()
        leftForOwnScreen = leftForOwnScreen(ownScreenAt, leftAt)
        ownScreenAt = 0L
        shielded.value = true
    }

    fun onForeground(context: android.content.Context) {
        shielded.value = false
        refreshLockAvailability()
        val ownScreen = returningFromOwnScreen(leftForOwnScreen, leftAt, SystemClock.elapsedRealtime())
        leftForOwnScreen = false
        if (!AppSettingsStore.appLock || !lockUsable(context)) return
        if (authInFlight || locked.value) return
        if (ownScreen) {
            backgroundedAt = 0L
            return
        }
        val grace = AppSettingsStore.autoLockGraceSeconds * 1000L
        if (backgroundedAt != 0L && System.currentTimeMillis() - backgroundedAt >= grace) {
            locked.value = true
            LockSession.close()
        }
    }

    fun prompt(activity: FragmentActivity) {
        if (AppSettingsStore.appLockCodeOnly) return
        if (authInFlight || !locked.value) return
        authInFlight = true
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authInFlight = false
                backgroundedAt = 0L
                openSession()
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

    private const val FREE_ATTEMPTS = 4
    private const val MAX_THROTTLE_MS = 30_000L
    private const val MAX_THROTTLE_STEPS = 5

    fun throttleFor(failures: Int): Long {
        val over = failures - FREE_ATTEMPTS
        if (over <= 0) return 0L
        return minOf(MAX_THROTTLE_MS, 1_000L shl minOf(over - 1, MAX_THROTTLE_STEPS))
    }

    fun remainingThrottle(total: Long, since: Long, now: Long): Long {
        if (total <= 0L) return 0L
        if (since <= 0L) return total
        return (total - (now - since)).coerceIn(0L, total)
    }

    @Volatile private var lastAttemptAt = 0L

    private fun codeThrottleMillis(): Long =
        remainingThrottle(throttleFor(AppSettingsStore.codeFailures), lastAttemptAt, SystemClock.elapsedRealtime())

    suspend fun submitCode(context: android.content.Context, code: String): CodeOutcome {
        if (code.length < AppSettingsStore.CODE_MIN_LENGTH) return CodeOutcome.REJECTED
        val wait = codeThrottleMillis()
        if (wait > 0L) delay(wait)
        lastAttemptAt = SystemClock.elapsedRealtime()
        val check = AppSettingsStore.verifyCodes(code)
        if (check.panic) {
            AppSettingsStore.codeFailures = 0
            DataWipe.wipe(context)
            sessionValidated = true
            backgroundedAt = 0L
            return CodeOutcome.WIPED
        }
        if (check.app) {
            AppSettingsStore.codeFailures = 0
            openSession()
            backgroundedAt = 0L
            return CodeOutcome.UNLOCKED
        }
        val failures = AppSettingsStore.codeFailures
        if (failures < Int.MAX_VALUE) AppSettingsStore.codeFailures = failures + 1
        return CodeOutcome.REJECTED
    }
}

fun <I> ActivityResultLauncher<I>.launchFromApp(input: I) {
    launch(input)
    AppLock.onOwnScreen()
}

private object LockSession {
    private const val NAME = "lock.session"

    fun open() {
        val mark = bootMark() ?: return
        runCatching { SecureStore.write(NAME, mark) }
    }

    fun close() {
        runCatching { SecureStore.delete(NAME) }
    }

    fun isOpen(): Boolean {
        val mark = bootMark() ?: return false
        val stored = runCatching { SecureStore.read(NAME) }.getOrNull() ?: return false
        return stored.contentEquals(mark)
    }

    private fun bootMark(): ByteArray? {
        val boot = runCatching {
            Settings.Global.getInt(SecureStore.appContext().contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull() ?: return null
        return byteArrayOf(
            (boot ushr 24).toByte(), (boot ushr 16).toByte(), (boot ushr 8).toByte(), boot.toByte(),
        )
    }
}
