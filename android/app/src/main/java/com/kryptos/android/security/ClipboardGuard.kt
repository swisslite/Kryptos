package com.kryptos.android.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import com.kryptos.android.core.CachePurge
import com.kryptos.android.core.sha256Hex
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.store.SecureStore

object ClipboardGuard {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var pending: Runnable? = null
    @Volatile private var lastCopied: String? = null
    @Volatile private var dueAt = 0L

    private enum class Outcome { CLEARED, FOREIGN, BLOCKED }

    init {
        CachePurge.register { forget() }
    }

    @Synchronized
    fun forget() {
        cancelPending()
        lastCopied = null
        dueAt = 0L
        forgetRecord()
    }

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private const val RECORD = "clip.pending"

    private fun bootMark(context: Context): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()

    private fun rememberRecord(context: Context) {
        val digest = lastCopied
        val boot = bootMark(context)
        if (digest == null || dueAt == 0L || boot == null) {
            forgetRecord()
            return
        }
        runCatching {
            SecureStore.write(RECORD, ClipboardRecord.encode(boot, dueAt, digest).toByteArray(Charsets.UTF_8))
        }
    }

    private fun forgetRecord() {
        runCatching { SecureStore.delete(RECORD) }
    }

    private fun restoreRecord(context: Context) {
        if (!runCatching { SecureStore.exists(RECORD) }.getOrDefault(false)) return
        val boot = bootMark(context) ?: return
        val raw = runCatching { SecureStore.read(RECORD) }.getOrNull() ?: return
        val record = ClipboardRecord.parse(String(raw, Charsets.UTF_8), boot)
        if (record == null) {
            forgetRecord()
            return
        }
        lastCopied = record.first
        dueAt = record.second
    }

    private fun arm(context: Context, delay: Long) {
        cancelPending()
        val task = Runnable { clearWhenDue(context) }
        pending = task
        handler.postDelayed(task, delay.coerceAtLeast(0L))
    }

    const val LABEL = "Kryptos"

    fun copy(context: Context, text: String, toast: String? = null) {
        val app = context.applicationContext
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(LABEL, text)
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
        toast?.let { message -> handler.post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() } }
        scheduleClear(app, text)
    }

    @Synchronized
    fun copyPlain(context: Context, text: String, toast: String? = null) {
        val app = context.applicationContext
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        forget()
        cm.setPrimaryClip(ClipData.newPlainText(LABEL, text))
        toast?.let { message -> handler.post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() } }
    }

    @Synchronized
    private fun scheduleClear(context: Context, text: String) {
        cancelPending()
        lastCopied = digest(text)
        val seconds = AppSettingsStore.clipboardClearSeconds
        if (seconds <= 0) {
            dueAt = 0L
            forgetRecord()
            return
        }
        val delay = seconds * 1000L
        dueAt = SystemClock.elapsedRealtime() + delay
        arm(context, delay)
        rememberRecord(context)
    }

    @Synchronized
    fun flushPending(context: Context) {
        val app = context.applicationContext
        if (lastCopied == null) restoreRecord(app)
        if (lastCopied == null || dueAt == 0L) return
        if (pending == null) arm(app, dueAt - SystemClock.elapsedRealtime())
        handler.post { clearWhenDue(app) }
    }

    @Synchronized
    private fun clearWhenDue(context: Context) {
        val fingerprint = lastCopied ?: return
        if (dueAt == 0L || SystemClock.elapsedRealtime() < dueAt) return
        cancelPending()
        if (clearMatching(context, fingerprint) != Outcome.BLOCKED) {
            lastCopied = null
            dueAt = 0L
            forgetRecord()
        }
    }

    private fun digest(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    @Synchronized
    fun clearIfOurs(context: Context, text: String) {
        val fingerprint = digest(text)
        if (clearMatching(context.applicationContext, fingerprint) != Outcome.BLOCKED) {
            if (lastCopied == fingerprint) {
                cancelPending()
                lastCopied = null
                dueAt = 0L
                forgetRecord()
            }
            return
        }
        cancelPending()
        lastCopied = fingerprint
        dueAt = SystemClock.elapsedRealtime()
        rememberRecord(context.applicationContext)
    }

    @Synchronized
    fun clearIfHolds(context: Context, texts: Collection<String>) {
        val fingerprint = lastCopied ?: return
        if (texts.isEmpty()) return
        val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = readClip(cm)
        if (current == null) {
            if (readLabel(cm) == null && texts.any { digest(it) == fingerprint }) {
                cancelPending()
                dueAt = SystemClock.elapsedRealtime()
                rememberRecord(context.applicationContext)
            }
            return
        }
        if (current.isEmpty() || digest(current) != fingerprint) return
        if (texts.none { it == current }) return
        cancelPending()
        lastCopied = null
        dueAt = 0L
        forgetRecord()
        wipe(cm)
    }

    private fun readClip(cm: ClipboardManager): String? = runCatching {
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }.getOrNull()

    private fun readLabel(cm: ClipboardManager): String? = runCatching {
        cm.primaryClipDescription?.label?.toString()
    }.getOrNull()

    private fun clearMatching(context: Context, fingerprint: String): Outcome {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = readClip(cm)
        if (current != null) {
            if (digest(current) != fingerprint) return Outcome.FOREIGN
            wipe(cm)
            return Outcome.CLEARED
        }
        val label = readLabel(cm)
        if (label == null) return Outcome.BLOCKED
        if (label != LABEL) return Outcome.FOREIGN
        wipe(cm)
        return Outcome.CLEARED
    }

    private fun wipe(cm: ClipboardManager) {
        cm.setPrimaryClip(ClipData.newPlainText("", ""))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { cm.clearPrimaryClip() }
        }
    }
}

internal object ClipboardRecord {
    private const val DIGEST_CHARS = 64

    fun encode(boot: Int, dueAt: Long, digest: String): String = "$boot\n$dueAt\n$digest"

    fun parse(raw: String, boot: Int): Pair<String, Long>? {
        val parts = raw.split("\n")
        if (parts.size != 3) return null
        if (parts[0].toIntOrNull() != boot) return null
        val due = parts[1].toLongOrNull() ?: return null
        val digest = parts[2]
        if (digest.length != DIGEST_CHARS) return null
        if (digest.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return digest to due
    }
}
