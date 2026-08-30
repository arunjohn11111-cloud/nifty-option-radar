package com.niftyradar.app.domain

import com.niftyradar.app.model.Candle

/**
 * Exponential Moving Average over a candle series' closing prices — standard definition:
 * seed the first value with a plain average of the first [period] closes, then smooth every
 * candle after that with multiplier = 2/(period+1). [candles] must be sorted oldest-first
 * (guaranteed by [com.niftyradar.app.network.UpstoxApiClient]'s candle parsing).
 *
 * Returns the FULL series of EMA values (one per candle from the [period]-th candle onward),
 * not just the latest — the Trend indicator in [IndicatorEngine] needs the two most recent
 * EMA9/EMA21 values to tell "still above" from "just crossed", not only where they are right
 * now. Every returned series ends on the same final candle regardless of [period], so
 * comparing `series9.last()` against `series21.last()` is always a same-moment comparison.
 */
object ExponentialMovingAverage {
    fun series(candles: List<Candle>, period: Int): List<Double> {
        if (candles.size < period) return emptyList()
        val multiplier = 2.0 / (period + 1)
        val result = ArrayList<Double>(candles.size - period + 1)
        var ema = candles.take(period).map { it.close }.average()
        result += ema
        for (i in period until candles.size) {
            ema = (candles[i].close - ema) * multiplier + ema
            result += ema
        }
        return result
    }
}
