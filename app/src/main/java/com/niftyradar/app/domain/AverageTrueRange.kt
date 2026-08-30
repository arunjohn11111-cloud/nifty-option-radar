package com.niftyradar.app.domain

import com.niftyradar.app.model.Candle
import kotlin.math.abs
import kotlin.math.max

/**
 * Average True Range over daily candles — per PROJECT_SPEC.md's 6-indicator
 * design, ATR is NOT a directional signal (it never votes bullish/bearish);
 * it only sizes the expected point-move used for Target/SL suggestion.
 *
 * Uses Wilder's original smoothing (the standard definition every charting
 * platform uses, not a simplified stand-in): the first ATR value is a plain
 * average of the first [period] true ranges, and every value after that is a
 * rolling smooth of the previous ATR with the new true range.
 *
 * [candles] must be sorted oldest-first (guaranteed by
 * [com.niftyradar.app.network.UpstoxApiClient]'s candle parsing) and needs at
 * least [period] + 1 candles to produce a result — returns null rather than a
 * misleading partial number if there isn't enough history yet.
 */
object AverageTrueRange {
    fun wilder(candles: List<Candle>, period: Int = 14): Double? {
        if (candles.size < period + 1) return null

        val trueRanges = ArrayList<Double>(candles.size - 1)
        for (i in 1 until candles.size) {
            val current = candles[i]
            val previousClose = candles[i - 1].close
            val trueRange = max(
                current.high - current.low,
                max(abs(current.high - previousClose), abs(current.low - previousClose))
            )
            trueRanges += trueRange
        }

        if (trueRanges.size < period) return null

        var atr = trueRanges.take(period).average()
        for (i in period until trueRanges.size) {
            atr = ((atr * (period - 1)) + trueRanges[i]) / period
        }
        return atr
    }
}
