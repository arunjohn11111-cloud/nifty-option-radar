package com.niftyradar.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.niftyradar.app.domain.PanicAlertResult
import com.niftyradar.app.domain.SignalDirection
import kotlin.math.abs

/**
 * Market-wide panic alert, per the user's explicit request: NIFTY 50 spot making a sudden,
 * large move (e.g. from overseas/"global cues" panic) gets its own notification + vibration,
 * independent of any active trade or the 6-indicator dashboard (see
 * [com.niftyradar.app.domain.PanicAlert]'s doc comment for why this watches spot alone). Uses
 * a longer, more urgent vibration pattern than [DashboardNotifier]'s "great indication" alert
 * so the two are distinguishable by feel alone, and its own separate notification channel/id
 * so the two can both be showing at once without one replacing the other.
 *
 * Same once-per-new-event firing discipline as [DashboardNotifier]: [lastAlertedDirection]
 * tracks what was last alerted so a sustained panic move doesn't re-fire every 5s refresh, but
 * the move calming back to NEUTRAL and later panicking again (or flipping straight from an
 * up-panic to a down-panic) each count as fresh events.
 */
class PanicAlertNotifier(private val context: Context) {

    private var lastAlertedDirection: SignalDirection = SignalDirection.NEUTRAL

    fun onPanicEvaluated(result: PanicAlertResult) {
        val direction = if (result.triggered) result.direction else SignalDirection.NEUTRAL
        if (direction == lastAlertedDirection) return
        lastAlertedDirection = direction
        if (direction == SignalDirection.NEUTRAL) return

        val title = if (direction == SignalDirection.BULLISH) {
            "⚠️ PANIC: NIFTY spiked +%.2f%% in 5 min".format(result.changePercent)
        } else {
            "⚠️ PANIC: NIFTY dropped %.2f%% in 5 min".format(abs(result.changePercent))
        }
        postNotification(title, "Sudden market-wide move — check before acting on any open position.")
        vibrate()
    }

    /**
     * POST_NOTIFICATIONS is requested once at app startup (MainActivity) — if denied, this
     * silently skips posting rather than crashing; [vibrate] below still fires either way.
     */
    private fun postNotification(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Market Panic Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Sudden, large NIFTY 50 spot moves — independent of any specific trade."
        }
        manager.createNotificationChannel(channel)
    }

    private fun vibrate() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VIBRATION_PATTERN, -1)
        }
    }

    companion object {
        private const val CHANNEL_ID = "panic_alerts"
        private const val NOTIFICATION_ID = 9002

        // Longer/more repetitions than DashboardNotifier's pattern — a panic alert should feel
        // more urgent than a routine "great indication" notification.
        private val VIBRATION_PATTERN = longArrayOf(0, 400, 150, 400, 150, 400)
    }
}
