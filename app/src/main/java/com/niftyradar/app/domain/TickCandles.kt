package com.niftyradar.app.domain

import com.niftyradar.app.model.Candle
import com.niftyradar.app.storage.LiveTickEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Builds OHLC candles out of the ticks this app has already recorded, so a chart can show the
 * high and low WITHIN each minute instead of a line that only connects the snapshots it
 * happened to see.
 *
 * WHAT THESE CANDLES ARE, AND WHAT THEY ARE NOT. Upstox's Market Data Feed is a SNAPSHOT feed:
 * each message is the instrument's state at that instant, not a record of every trade. So the
 * open here is the first snapshot of the minute rather than the first trade, and the wick spans
 * the extremes of the snapshots that arrived — a spike that happened and reversed entirely
 * between two snapshots leaves no trace. These are therefore a faithful record of what this app
 * OBSERVED, not the exchange's official OHLC, and they can differ slightly from the candles a
 * broker's own chart draws.
 *
 * That difference is fixable and worth fixing: `MarketFullFeed.marketOHLC` carries the
 * exchange's own OHLC on every tick, for free, and this app currently discards it (the field
 * appears nowhere else in the codebase). The proto types `interval` as a bare string and does
 * not say which intervals are sent, so whether a 1-minute candle is among them has to be
 * observed from a real payload before anything is built on it. Until then, aggregating our own
 * ticks is the honest option: approximate, but with no unverified assumption underneath it.
 *
 * [Candle] is reused rather than a new type being introduced, and [Candle.timestampIso] is
 * written in exactly the format Upstox's historical-candle API returns
 * ("yyyy-MM-dd'T'HH:mm:ss+05:30"). That is not cosmetic: it means candles from this aggregator
 * and candles from the API sort together correctly and can be fed to the same
 * [ExponentialMovingAverage] and [AverageTrueRange] without conversion, and it means switching
 * the source to `marketOHLC` later changes where candles come from without changing anything
 * that consumes them.
 */
object TickCandles {

    const val ONE_MINUTE_MS = 60_000L

    /**
     * Upstox's own format, IST with the fixed +05:30 offset — see the class doc for why the
     * exact format matters.
     */
    private fun isoTimestamp(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(millis)
    }

    /**
     * Groups [ticks] into fixed [bucketMillis] windows on absolute time and reduces each to one
     * candle, oldest first.
     *
     * Bucketed on absolute epoch time, not on an offset from the first tick, so a candle always
     * covers a real clock minute (09:16:00–09:16:59) that lines up with every other
     * instrument's and with any broker chart. Bucketing from the first tick instead would give
     * each instrument its own minute boundaries depending on when it happened to start ticking.
     *
     * Volume is the CHANGE in [LiveTickEntity.volumeTradedToday] across the window — that
     * field is the running day total, so a difference is the volume actually traded in the
     * minute. Negative differences (a day rollover, or a feed reset) are floored at zero
     * rather than propagated as a negative volume.
     *
     * Open interest is taken from the last tick in the window: OI is a level, not a flow, so
     * the closing level is the meaningful one.
     */
    fun fromTicks(ticks: List<LiveTickEntity>, bucketMillis: Long = ONE_MINUTE_MS): List<Candle> {
        if (ticks.isEmpty() || bucketMillis <= 0L) return emptyList()
        return ticks
            .groupBy { it.receivedAtMillis / bucketMillis }
            .toSortedMap()
            .map { (bucket, bucketTicks) ->
                val ordered = bucketTicks.sortedBy { it.receivedAtMillis }
                val first = ordered.first()
                val last = ordered.last()
                val firstVolume = ordered.firstNotNullOfOrNull { it.volumeTradedToday }
                val lastVolume = ordered.asReversed().firstNotNullOfOrNull { it.volumeTradedToday }
                Candle(
                    timestampIso = isoTimestamp(bucket * bucketMillis),
                    open = first.ltp,
                    high = ordered.maxOf { it.ltp },
                    low = ordered.minOf { it.ltp },
                    close = last.ltp,
                    volume = if (firstVolume != null && lastVolume != null) {
                        (lastVolume - firstVolume).coerceAtLeast(0L)
                    } else {
                        0L
                    },
                    openInterest = last.openInterest ?: 0.0
                )
            }
    }

    /**
     * The epoch-millisecond start of each candle produced by [fromTicks], in the same order.
     *
     * Kept separate rather than parsing [Candle.timestampIso] back: the chart positions candles
     * on a millisecond time axis shared with the raw tick lines and the buy/sell strip, and
     * re-parsing a string it just formatted would be both wasteful and a place for the two to
     * drift out of step.
     */
    fun bucketStartsMillis(
        ticks: List<LiveTickEntity>,
        bucketMillis: Long = ONE_MINUTE_MS
    ): List<Long> {
        if (ticks.isEmpty() || bucketMillis <= 0L) return emptyList()
        return ticks
            .map { it.receivedAtMillis / bucketMillis }
            .distinct()
            .sorted()
            .map { it * bucketMillis }
    }

    /**
     * Day VWAP as the feed reports it (`atp`), taken from the most recent tick that carries
     * one — Upstox computes it server-side, so this app neither calculates nor keeps state for
     * it. Null for the index feed and before the first tick of a session.
     */
    fun latestVwap(ticks: List<LiveTickEntity>): Double? =
        ticks.asReversed().firstNotNullOfOrNull { it.averageTradedPrice }
}
