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
import com.niftyradar.app.domain.DashboardResult

/**
 * "Great indication" alert, per the user's explicit request: a notification (sound + a short
 * vibration pattern) when the 6-indicator dashboard's directional signals agree strongly.
 * There are only ever 5 VOTING signals (ATR never votes — see
 * [com.niftyradar.app.domain.IndicatorSignal]'s doc comment), so "strong" here means
 * [STRONG_CONSENSUS_THRESHOLD] or more of those 5 agreeing — the user's original "5-6/6"
 * phrasing was from before that distinction was settled.
 *
 * Fires once per NEW strong-consensus event, not on every 5s dashboard refresh while it
 * stays strong — [lastNotifiedState] tracks what was last alerted so a steady 4-of-5 bullish
 * reading doesn't spam a notification every 5 seconds, but a flip from strong bullish
 * straight to strong bearish (or dropping out of strong and later re-entering) does count as
 * a fresh event and re-fires.
 */
class DashboardNotifier(private val context: Context) {

    private enum class AlertState { NONE, STRONG_BULLISH, STRONG_BEARISH }
    private var lastNotifiedState = AlertState.NONE

    fun onDashboardUpdated(dashboard: DashboardResult) {
        val currentState = when {
            dashboard.bullishCount >= STRONG_CONSENSUS_THRESHOLD -> AlertState.STRONG_BULLISH
            dashboard.bearishCount >= STRONG_CONSENSUS_THRESHOLD -> AlertState.STRONG_BEARISH
            else -> AlertState.NONE
        }
        if (currentState == lastNotifiedState) return
        lastNotifiedState = currentState
        if (currentState == AlertState.NONE) return

        val title = if (currentState == AlertState.STRONG_BULLISH) {
            "Strong BULLISH indication (${dashboard.bullishCount} of ${dashboard.total})"
        } else {
            "Strong BEARISH indication (${dashboard.bearishCount} of ${dashboard.total})"
        }
        val text = dashboard.signals.joinToString(", ") { it.name }

        postNotification(title, text)
        vibrate()
    }

    /**
     * POST_NOTIFICATIONS is requested once at app startup (see MainActivity, originally added
     * for the Quick Trade overlay's own persistent notification) — if the user denied it, this
     * silently skips posting rather than crashing; [vibrate] below still fires either way, so a
     * denial only loses the visual/sound half of the alert, not all of it.
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
            "6-Indicator Dashboard Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Strong bullish/bearish consensus across the 6-indicator dashboard."
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
        private const val CHANNEL_ID = "dashboard_alerts"
        private const val NOTIFICATION_ID = 9001

        /** Out of 5 voting signals — 4 or 5 agreeing counts as a "great indication". */
        private const val STRONG_CONSENSUS_THRESHOLD = 4

        private val VIBRATION_PATTERN = longArrayOf(0, 300, 150, 300)
    }
}
