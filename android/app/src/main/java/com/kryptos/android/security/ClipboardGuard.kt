package com.kryptos.android.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import com.kryptos.android.signal.AppSettingsStore

object ClipboardGuard {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var lastCopied: String? = null

    fun copy(context: Context, text: String, toast: String? = null) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Kryptos", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        } else {
            @Suppress("DEPRECATION")
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        toast?.let { handler.post { Toast.makeText(context.applicationContext, it, Toast.LENGTH_SHORT).show() } }
        scheduleClear(context, text)
    }

    private fun scheduleClear(context: Context, text: String) {
        pending?.let { handler.removeCallbacks(it) }
        val seconds = AppSettingsStore.clipboardClearSeconds
        if (seconds <= 0) { lastCopied = null; return }
        lastCopied = text
        val appContext = context.applicationContext
        val task = Runnable { if (lastCopied == text) clearIfOurs(appContext, text) }
        pending = task
        handler.postDelayed(task, seconds * 1000L)
    }

    fun clearIfOurs(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (current == text) wipe(cm)
        lastCopied = null
    }

    private fun wipe(cm: ClipboardManager) {
        cm.setPrimaryClip(ClipData.newPlainText("", ""))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { cm.clearPrimaryClip() }
        }
    }
}
