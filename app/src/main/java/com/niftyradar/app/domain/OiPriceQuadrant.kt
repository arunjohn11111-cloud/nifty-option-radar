package com.niftyradar.app.domain

import com.niftyradar.app.storage.LiveTickEntity

/**
 * Classic OI+Price quadrant, applied per-contract, per PROJECT_SPEC.md's 6-indicator design:
 * for any single instrument, comparing its own price change and its own OI change over the
 * same window tells you whether fresh positions are being built or unwound in it —
 *
 *  price UP   + OI UP   = Long Buildup    (fresh buyers)
 *  price UP   + OI DOWN = Short Covering  (shorts exiting)
 *  price DOWN + OI UP   = Short Buildup   (fresh sellers)
 *  price DOWN + OI DOWN = Long Unwinding  (longs exiting)
 *
 * Long Buildup/Short Covering both mean "bullish for this instrument's own price"; Short
 * Buildup/Long Unwinding both mean "bearish for this instrument's own price". This app has
 * no futures/index OI to read (NIFTY 50 spot carries none — see
 * [com.niftyradar.app.feed.LiveQuote]'s doc comment), so [IndicatorEngine] runs this on the
 * ATM CE and ATM PE instead and combines the two — PE's own-price sentiment gets inverted
 * there into a NIFTY-direction vote, since PE price rises when NIFTY FALLS.
 */
enum class QuadrantLabel { LONG_BUILDUP, SHORT_COVERING, SHORT_BUILDUP, LONG_UNWINDING, FLAT }

data class QuadrantResult(
    val label: QuadrantLabel,
    val priceChangePercent: Double,
    val oiChangePercent: Double
) {
    /** true = bullish for this instrument's OWN price, false = bearish, null = FLAT/no read. */
    val bullishForOwnPrice: Boolean? = when (label) {
        QuadrantLabel.LONG_BUILDUP, QuadrantLabel.SHORT_COVERING -> true
        QuadrantLabel.SHORT_BUILDUP, QuadrantLabel.LONG_UNWINDING -> false
        QuadrantLabel.FLAT -> null
    }
}

object OiPriceQuadrant {
    /** Below this, a price or OI move is treated as noise rather than a real directional move. */
    private const val NOISE_THRESHOLD_PERCENT = 0.05

    fun classify(ticks: List<LiveTickEntity>, windowMs: Long): QuadrantResult? {
        val endpoints = TickWindow.endpoints(ticks, windowMs) ?: return null
        val oldOi = endpoints.oldest.openInterest
        val newOi = endpoints.newest.openInterest
        if (oldOi == null || newOi == null || oldOi == 0.0 || endpoints.oldest.ltp == 0.0) return null

        val priceChangePercent = (endpoints.newest.ltp - endpoints.oldest.ltp) / endpoints.oldest.ltp * 100.0
        val oiChangePercent = (newOi - oldOi) / oldOi * 100.0

        val priceUp = priceChangePercent > NOISE_THRESHOLD_PERCENT
        val priceDown = priceChangePercent < -NOISE_THRESHOLD_PERCENT
        val oiUp = oiChangePercent > NOISE_THRESHOLD_PERCENT
        val oiDown = oiChangePercent < -NOISE_THRESHOLD_PERCENT

        val label = when {
            priceUp && oiUp -> QuadrantLabel.LONG_BUILDUP
            priceUp && oiDown -> QuadrantLabel.SHORT_COVERING
            priceDown && oiUp -> QuadrantLabel.SHORT_BUILDUP
            priceDown && oiDown -> QuadrantLabel.LONG_UNWINDING
            else -> QuadrantLabel.FLAT
        }
        return QuadrantResult(label, priceChangePercent, oiChangePercent)
    }
}
