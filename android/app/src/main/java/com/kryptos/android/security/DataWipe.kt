package com.kryptos.android.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.kryptos.android.core.CachePurge
import com.kryptos.android.keyboard.TypingMemory
import com.kryptos.android.pgp.PgpService
import com.kryptos.android.signal.SignalService
import java.io.File

object DataWipe {

    fun wipe(context: Context) {
        val app = context.applicationContext
        CachePurge.purgeAll()
        TypingMemory.forgetAll()
        clearClipboard(app)
        SignalService.eraseAndReinit {
            PgpService.eraseAllStorage()
            sweep(app)
            CachePurge.purgeAll()
            TypingMemory.forgetAll()
        }
        runCatching { PgpService.ensureInitialized() }
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
