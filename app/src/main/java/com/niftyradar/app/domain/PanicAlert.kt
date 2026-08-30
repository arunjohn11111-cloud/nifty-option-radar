package com.niftyradar.app.domain

import com.niftyradar.app.storage.LiveTickEntity

/**
 * Market-wide panic detector: NIFTY 50 spot itself making a sudden, large move in a short
 * window — independent of any specific option contract, active trade, or the 6-indicator
 * dashboard. Deliberately separate from [IndicatorEngine]: a sharp move from "global cues"/
 * overseas panic can happen before any of the 5 dashboard signals catch up (most of them read
 * option-side OI/order-flow, which lags a sudden spot move), so this watches spot price alone,
 * per the user's explicit request for a standalone market-wide caution.
 */
data class PanicAlertResult(
    val triggered: Boolean,
    val changePercent: Double,
    /** BULLISH = sudden spike up, BEARISH = sudden drop, NEUTRAL = below the panic threshold. */
    val direction: SignalDirection
)

object PanicAlert {
    /** Same 5-minute window every other "recent move" reading in this app uses. */
    private const val WINDOW_MS = 5 * 60_000L

    /**
     * A move at least this large within the window counts as a panic, not routine volatility.
     * Chosen as a starting point, not a calibrated certainty — easy to tune later once there's
     * a feel for how often it fires in practice.
     */
    private const val PANIC_THRESHOLD_PERCENT = 0.4

    fun evaluate(spotTicks: List<LiveTickEntity>): PanicAlertResult? {
        val endpoints = TickWindow.endpoints(spotTicks, WINDOW_MS) ?: return null
        if (endpoints.oldest.ltp == 0.0) return null

        val changePercent = (endpoints.newest.ltp - endpoints.oldest.ltp) / endpoints.oldest.ltp * 100.0
        val direction = when {
            changePercent >= PANIC_THRESHOLD_PERCENT -> SignalDirection.BULLISH
            changePercent <= -PANIC_THRESHOLD_PERCENT -> SignalDirection.BEARISH
            else -> SignalDirection.NEUTRAL
        }
        return PanicAlertResult(direction != SignalDirection.NEUTRAL, changePercent, direction)
    }
}
