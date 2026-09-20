package com.kryptos.android.security

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.kryptos.android.core.CachePurge
import com.kryptos.android.keyboard.KryptosImeService
import com.kryptos.android.keyboard.TypingMemory
import com.kryptos.android.keyboard.kryptosKeyboardSelected
import com.kryptos.android.pgp.PgpService
import com.kryptos.android.screen.ScreenDecryptService
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.signal.SignalService
import java.io.File

object DataWipe {
    private const val KEYBOARD_SWITCH_WAIT_MS = 3_000L
    private const val KEYBOARD_SWITCH_POLL_MS = 50L

    fun wipe(context: Context) {
        val app = context.applicationContext
        CachePurge.purgeAll()
        TypingMemory.forgetAll()
        clearClipboard(app)
        ScreenDecryptService.turnOff()
        SignalService.eraseAndReinit {
            PgpService.eraseAllStorage()
            sweep(app)
            CachePurge.purgeAll()
            TypingMemory.forgetAll()
        }
        runCatching { PgpService.ensureInitialized() }
        runCatching { AppSettingsStore.resealCodes() }
        releaseKeyboard(app)
        revokeRuntimePermissions(app)
    }

    fun restoreKeyboard(context: Context) {
        val pm = context.packageManager
        val keyboard = ComponentName(context, KryptosImeService::class.java)
        if (pm.getComponentEnabledSetting(keyboard) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return
        pm.setComponentEnabledSetting(keyboard, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
    }

    private fun releaseKeyboard(context: Context) {
        if (!kryptosKeyboardSelected(context)) return
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, KryptosImeService::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            val deadline = SystemClock.elapsedRealtime() + KEYBOARD_SWITCH_WAIT_MS
            while (kryptosKeyboardSelected(context) && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(KEYBOARD_SWITCH_POLL_MS)
            }
        }
        runCatching { restoreKeyboard(context) }
    }

    private fun revokeRuntimePermissions(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            context.revokeSelfPermissionsOnKill(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun clearClipboard(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cm.clearPrimaryClip()
        }
    }

    private fun sweep(context: Context) {
        val data = runCatching { File(context.applicationInfo.dataDir) }.getOrNull()
        val roots = buildList {
            add(context.filesDir)
            add(context.cacheDir)
            add(context.noBackupFilesDir)
            context.externalCacheDir?.let { add(it) }
            context.getExternalFilesDir(null)?.let { add(it) }
            data?.let { add(File(it, "shared_prefs")); add(File(it, "databases")) }
        }
        for (root in roots) {
            runCatching { root.listFiles()?.forEach { erase(it) } }
        }
    }

    private fun erase(target: File) {
        if (target.isDirectory) {
            target.listFiles()?.forEach { erase(it) }
        }
        target.delete()
    }
}
