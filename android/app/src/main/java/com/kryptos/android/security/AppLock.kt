package com.kryptos.android.security

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kryptos.android.R
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.store.SecureStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

object AppLock {
    val locked = MutableStateFlow(false)
    val shielded = MutableStateFlow(false)

    private var authInFlight = false

    private const val OWN_SCREEN_LAUNCH_MS = 10L * 1000
    private const val OWN_SCREEN_AWAY_MS = 5L * 60 * 1000
    internal const val SESSION_AFTER_LEAVING_MS = 5L * 60 * 1000

    private val session = CryptoSession(SESSION_AFTER_LEAVING_MS)

    @Volatile private var ownScreenAt = 0L
    @Volatile private var leftForOwnScreen = false

    fun onOwnScreen() {
        ownScreenAt = SystemClock.elapsedRealtime()
    }

    fun leftForOwnScreen(launchedAt: Long, leftAt: Long): Boolean =
        launchedAt != 0L && (leftAt - launchedAt) in 0 until OWN_SCREEN_LAUNCH_MS

    fun returningFromOwnScreen(leftForOwnScreen: Boolean, leftAt: Long, now: Long): Boolean =
        leftForOwnScreen && (now - leftAt) in 0 until OWN_SCREEN_AWAY_MS

    fun lockDue(backgroundedAt: Long, now: Long, graceMs: Long): Boolean =
        backgroundedAt != 0L && now - backgroundedAt >= graceMs

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
            if (!next.enabled) LockSession.close()
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
        val unknown = !AppSettingsStore.settingsAvailable()
        val armed = (unknown || AppSettingsStore.appLock) && lockUsable(context)
        locked.value = armed
        if (armed) closeSession()
    }

    private fun deviceLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    fun isCryptoSessionLocked(context: android.content.Context): Boolean {
        if (!AppSettingsStore.settingsAvailable()) return true
        if (deviceLocked(context)) return true
        if (!AppSettingsStore.appLock) return false
        if (!locked.value && sessionOpen()) return false
        return lockUsable(context)
    }

    fun cryptoSessionEndsIn(): Long? {
        if (!AppSettingsStore.settingsAvailable() || !AppSettingsStore.appLock || locked.value) return null
        return synchronized(session) {
            restoreSession()
            session.endsIn(SystemClock.elapsedRealtime())
        }
    }

    private fun sessionOpen(): Boolean = synchronized(session) {
        restoreSession()
        session.isOpen(SystemClock.elapsedRealtime())
    }

    private fun restoreSession() {
        if (!session.restored) session.restore(LockSession.until())
    }

    private fun closeSession() {
        synchronized(session) {
            session.forget()
            LockSession.close()
        }
    }

    private fun unlockSession() {
        synchronized(session) {
            session.unlock()?.let { if (AppSettingsStore.appLock) LockSession.open(it) }
            locked.value = false
        }
    }

    private const val RETURN_PROBE = "com.kryptos.android.action.RETURN_PROBE"
    private const val RETURN_PROBE_TIMEOUT_MS = 3_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var departed = false
    private var watchingDeparture = false
    private var probing: Context? = null

    private val departure = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RETURN_PROBE) settleReturn() else departed = true
        }
    }

    private val probeTimeout = Runnable {
        departed = true
        settleReturn()
    }

    @Suppress("DEPRECATION")
    private fun watchDeparture(context: Context) {
        if (watchingDeparture) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(RETURN_PROBE)
        }
        watchingDeparture = runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext, departure, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
    }

    private fun stopWatchingDeparture(context: Context) {
        main.removeCallbacks(probeTimeout)
        probing = null
        if (!watchingDeparture) return
        watchingDeparture = false
        runCatching { context.applicationContext.unregisterReceiver(departure) }
    }

    fun onBackground(context: Context) {
        val now = SystemClock.elapsedRealtime()
        main.removeCallbacks(probeTimeout)
        probing = null
        departed = false
        leftForOwnScreen = leftForOwnScreen(ownScreenAt, now)
        ownScreenAt = 0L
        if (leftForOwnScreen) watchDeparture(context) else stopWatchingDeparture(context)
        shielded.value = true
        synchronized(session) {
            session.leave(now)
            if (AppSettingsStore.appLock && !locked.value) LockSession.open(now + SESSION_AFTER_LEAVING_MS)
        }
    }

    fun onForeground(context: Context) {
        val leftAt = synchronized(session) {
            session.resume()
            session.leftAt
        }
        refreshLockAvailability()
        val ownScreen = !departed && returningFromOwnScreen(leftForOwnScreen, leftAt, SystemClock.elapsedRealtime())
        leftForOwnScreen = false
        if (ownScreen && watchingDeparture) {
            val app = context.applicationContext
            probing = app
            main.postDelayed(probeTimeout, RETURN_PROBE_TIMEOUT_MS)
            val sent = runCatching { app.sendBroadcast(Intent(RETURN_PROBE).setPackage(app.packageName)) }.isSuccess
            if (sent) return
            departed = true
            settleReturn()
            return
        }
        stopWatchingDeparture(context)
        settle(context, ownScreen)
    }

    private fun settleReturn() {
        val context = probing ?: return
        val ownScreen = !departed
        stopWatchingDeparture(context)
        settle(context, ownScreen)
    }

    private fun settle(context: Context, ownScreen: Boolean) {
        shielded.value = false
        val now = SystemClock.elapsedRealtime()
        val armed = AppSettingsStore.appLock && lockUsable(context)
        synchronized(session) {
            if (authInFlight || locked.value) return
            if (armed && !ownScreen && session.lockDue(now, AppSettingsStore.autoLockGraceSeconds * 1000L)) {
                locked.value = true
                closeSession()
                return
            }
            session.stay()
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
                synchronized(session) { session.stay() }
                unlockSession()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authInFlight = false
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Kryptos")
            .setSubtitle(activity.getString(R.string.lock_unlock))
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

    private const val FREE_ATTEMPTS = 5
    private const val FIRST_LOCKOUT_MS = 60_000L
    private const val MAX_LOCKOUT_MS = 5L * 60 * 1000
    private const val MAX_LOCKOUT_STEPS = 4

    fun throttleFor(failures: Int): Long {
        val over = failures - FREE_ATTEMPTS
        if (over <= 0) return 0L
        return minOf(MAX_LOCKOUT_MS, FIRST_LOCKOUT_MS shl minOf(over - 1, MAX_LOCKOUT_STEPS))
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
            unlockSession()
            return CodeOutcome.WIPED
        }
        if (check.app) {
            AppSettingsStore.codeFailures = 0
            unlockSession()
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

    fun open(until: Long) {
        val boot = bootCount() ?: return
        runCatching { SecureStore.write(NAME, LockSessionRecord.encode(boot, until)) }
    }

    fun close() {
        runCatching { SecureStore.delete(NAME) }
    }

    fun until(): Long? {
        val boot = bootCount() ?: return null
        val stored = runCatching { SecureStore.read(NAME) }.getOrNull() ?: return null
        return LockSessionRecord.parse(stored, boot, SystemClock.elapsedRealtime())
    }

    private fun bootCount(): Int? = runCatching {
        Settings.Global.getInt(SecureStore.appContext().contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()
}

internal object LockSessionRecord {
    private const val SIZE = 12

    fun encode(boot: Int, until: Long): ByteArray =
        java.nio.ByteBuffer.allocate(SIZE).putInt(boot).putLong(until).array()

    fun parse(raw: ByteArray, boot: Int, now: Long): Long? {
        if (raw.size != SIZE) return null
        val buffer = java.nio.ByteBuffer.wrap(raw)
        if (buffer.int != boot) return null
        val until = buffer.long
        return until.takeIf { it > now && it - now <= AppLock.SESSION_AFTER_LEAVING_MS }
    }
}

internal class CryptoSession(private val windowMs: Long) {
    var leftAt = 0L
        private set
    var restored = false
        private set
    private var resumed = false
    private var departedAt = 0L
    private var restoredUntil = 0L

    fun leave(now: Long) {
        resumed = false
        leftAt = now
        departedAt = now
    }

    fun resume() {
        resumed = true
    }

    fun lockDue(now: Long, graceMs: Long): Boolean = AppLock.lockDue(departedAt, now, graceMs)

    fun stay() {
        departedAt = 0L
    }

    fun unlock(): Long? {
        if (resumed) {
            departedAt = 0L
            return null
        }
        return if (leftAt == 0L) null else leftAt + windowMs
    }

    fun restore(until: Long?) {
        restored = true
        if (until != null) restoredUntil = until
    }

    fun forget() {
        restored = true
        restoredUntil = 0L
    }

    fun isOpen(now: Long): Boolean = resumed || now < end()

    fun endsIn(now: Long): Long? = if (resumed) null else (end() - now).takeIf { it > 0L }

    private fun end(): Long = maxOf(if (leftAt == 0L) 0L else leftAt + windowMs, restoredUntil)
}
