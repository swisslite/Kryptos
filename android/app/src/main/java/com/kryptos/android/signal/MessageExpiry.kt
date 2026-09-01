package com.kryptos.android.signal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kryptos.android.KryptosApp
import com.kryptos.android.store.SecureStore
import kotlinx.coroutines.launch

object MessageExpiry {
    private const val REQUEST = 1
    private const val MIN_DELAY_MS = 5_000L
    private const val RETRY_DELAY_MS = 60L * 60 * 1000

    @Volatile private var scheduledAt: Long? = null

    fun nextDueAt(messages: Map<String, List<ChatMessage>>, autoDelete: Map<String, Double>): Long? {
        if (autoDelete.isEmpty()) return null
        var soonest = Long.MAX_VALUE
        for ((fingerprint, seconds) in autoDelete) {
            if (seconds <= 0) continue
            val maxAgeMs = (seconds * 1000).toLong()
            for (message in messages[fingerprint] ?: continue) {
                val due = message.date + maxAgeMs
                if (due < soonest) soonest = due
            }
        }
        return if (soonest == Long.MAX_VALUE) null else soonest
    }

    fun forgetSchedule() {
        scheduledAt = null
    }

    fun scheduleRetry() {
        schedule(System.currentTimeMillis() + RETRY_DELAY_MS)
    }

    fun schedule(at: Long?) {
        if (at != null && at == scheduledAt) return
        val context = runCatching { SecureStore.appContext() }.getOrNull() ?: return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ExpiryReceiver::class.java)
        if (at == null) {
            val existing = PendingIntent.getBroadcast(
                context, REQUEST, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            existing?.let {
                runCatching { manager.cancel(it) }
                it.cancel()
            }
            scheduledAt = null
            return
        }
        val pending = PendingIntent.getBroadcast(
            context, REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fireAt = maxOf(at, System.currentTimeMillis() + MIN_DELAY_MS)
        val armed = runCatching { manager.set(AlarmManager.RTC, fireAt, pending) }.isSuccess
        scheduledAt = if (armed) at else null
    }
}

class ExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val result = goAsync()
        KryptosApp.scope.launch {
            try {
                MessageExpiry.forgetSchedule()
                val ready = runCatching { SignalService.ensureInitialized(); true }.getOrDefault(false)
                if (ready) SignalService.purgeExpiredMessages() else MessageExpiry.scheduleRetry()
            } finally {
                result.finish()
            }
        }
    }
}
