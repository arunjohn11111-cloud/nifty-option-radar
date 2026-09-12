package com.niftyradar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niftyradar.app.domain.ExponentialMovingAverage
import com.niftyradar.app.domain.TickCandles
import com.niftyradar.app.storage.LiveTickEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private val PRICE_COLOR = Color(0xFF6750A4)
private val OI_COLOR = Color(0xFFE8871E)
private val BUY_COLOR = Color(0xFF2E7D32)
private val SELL_COLOR = Color(0xFFC62828)
private val GREEKS_COLOR = Color(0xFF00695C)
private val GRID_COLOR = Color(0x1A000000)
private val AXIS_LABEL_COLOR = Color(0xFF757575)

/**
 * The two moving averages drawn over candles, and the VWAP level.
 *
 * This pair was not chosen by eye — it was run through a palette validator, which is the only
 * way to know a pair is safe for colour-blind readers rather than to assume it. Blue and teal
 * clear the lightness band, the chroma floor, the normal-vision separation floor and 3:1
 * contrast against a light surface. Their separation under TRITAN simulation is low, though,
 * which is exactly why each line also carries a direct label at its right-hand end and why the
 * slow one is dashed: identity here never rests on colour alone.
 */
private val EMA_FAST_COLOR = Color(0xFF1565C0)
private val EMA_SLOW_COLOR = Color(0xFF00897B)
private val VWAP_COLOR = Color(0xFF5A6B7A)

/** Periods for the two averages drawn on the candle view. */
private const val EMA_FAST_PERIOD = 9
private const val EMA_SLOW_PERIOD = 21

/** Fraction of each minute's slot the candle body occupies, leaving a gap between candles. */
private const val CANDLE_BODY_FRACTION = 0.68f

/** How wide a time window one buy/sell bar pair represents — matches the screens' 5s
 *  auto-refresh cadence, so "one bar" reads as "one refresh's worth of order flow". */
private const val FLOW_BUCKET_MS = 5_000L
private val FLOW_BAR_HEIGHT = 64.dp

/**
 * The price axis never shows a span narrower than this fraction of the price itself.
 *
 * This is the fix for the single most misleading thing this chart used to do. It scaled the
 * y-axis to exactly the visible min/max and stretched that to the full height, so a 50-paisa
 * wiggle and a 50-rupee move drew the IDENTICAL shape. A flat, range-bound morning looked
 * every bit as dramatic as a breakout. That is the same failure as an EMA crossover with no
 * deadband: the presentation manufactures a signal out of noise.
 *
 * 0.4% of the price is a floor, not a cap — it only ever WIDENS the axis, so it can never
 * hide or shrink a move that really happened. When it kicks in, the chart says so underneath
 * rather than letting a flat line look deliberate.
 */
private const val MIN_SPAN_FRACTION = 0.004

/** Roughly how many horizontal gridlines to aim for; the real count lands on round numbers. */
private const val TARGET_GRIDLINES = 4

/**
 * How far ahead of the device clock a tick's timestamp may sit before it is treated as
 * impossible. A couple of minutes absorbs an ordinary NTP correction landing mid-session; it
 * does not absorb the twelve-hour skew that prompted this guard.
 */
private const val CLOCK_SKEW_TOLERANCE_MS = 120_000L

/** Width reserved on the right for the price labels, TradingView-style. */
private val PRICE_GUTTER = 46.dp

/** Which line(s) [LiveTickChart] draws — set from a screen-level toggle so the
 *  whole radar switches together instead of per-chart. */
enum class ChartDisplayMode { Both, PriceOnly, OiOnly }

/** "12,340,000" -> "12.3M", "8,400" -> "8.4K" — full digit counts on a handful-of-pixels-wide
 *  bar label would just be unreadable, and the exact last digit was never the point here. */
private fun formatQuantityShort(value: Double): String {
    val absValue = abs(value)
    return when {
        absValue >= 1_000_000.0 -> "%.1fM".format(value / 1_000_000.0)
        absValue >= 1_000.0 -> "%.1fK".format(value / 1_000.0)
        else -> "%.0f".format(value)
    }
}

/**
 * Rounds a raw axis step up to the nearest "round" number (1, 2, 2.5, 5 x a power of ten), so
 * gridlines land on values a person reads without effort — 23,400 / 23,450 / 23,500, never
 * 23,412.7 / 23,461.3.
 */
private fun niceStep(rawStep: Double): Double {
    if (rawStep <= 0.0 || !rawStep.isFinite()) return 1.0
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val normalized = rawStep / magnitude
    val stepped = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 2.5 -> 2.5
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return stepped * magnitude
}

/**
 * How long a silence counts as a real gap in the data rather than a slow patch.
 *
 * Adaptive on purpose: a liquid ATM contract ticks several times a second while a far strike
 * can legitimately go a minute without a trade, so one fixed threshold would either break the
 * line constantly on the quiet one or never break it on the busy one. Ten times the median
 * interval (never less than 30s) tracks whatever cadence the instrument actually has.
 *
 * Returns [Long.MAX_VALUE] when there is too little data to judge — i.e. never break.
 */
private fun gapBreakThresholdMillis(times: List<Long>): Long {
    if (times.size < 3) return Long.MAX_VALUE
    val gaps = times.zipWithNext { a, b -> b - a }.filter { it > 0L }.sorted()
    if (gaps.isEmpty()) return Long.MAX_VALUE
    return maxOf(30_000L, gaps[gaps.size / 2] * 10L)
}

private fun formatClockIst(millis: Long): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    return fmt.format(millis)
}

/**
 * A live chart of LTP over the ticks stored today for one instrument (read back from Room via
 * [com.niftyradar.app.storage.LiveTickStore.ticksFor]), with an optional OI overlay, a
 * buy/sell pressure strip and a Greeks readout.
 *
 * The price and OI lines are positioned by ACTUAL elapsed time
 * ([LiveTickEntity.receivedAtMillis]), not by tick index — this matters because the buy/sell
 * strip below buckets ticks into 5-second windows (fewer points than the raw tick series);
 * sharing one real time axis is what keeps "this OI move" and "the buy/sell bar right below
 * it" pointing at the same moment, not just the same list position.
 *
 * WHAT THIS CHART NOW TELLS YOU, AND WHAT IT USED TO GET WRONG. It began as the simplest
 * possible proof that stored ticks become a line that moves, and three things about that first
 * version were not just unfinished but actively misleading:
 *
 *  1. The y-axis stretched to the visible min/max, so every chart looked equally dramatic
 *     whatever the actual move. Fixed by [MIN_SPAN_FRACTION] plus real numbered gridlines,
 *     and by saying so when the floor is what is setting the scale.
 *  2. A feed outage was drawn as a smooth diagonal, asserting a price path that never
 *     happened. Fixed by [gapBreakThresholdMillis] — the line now breaks across a silence.
 *  3. There were no numbers on either axis, so the chart could be read for shape but never
 *     for value or time. Fixed by price labels in a right-hand gutter and IST clock labels
 *     under the plot.
 *
 * CANDLES, when [candles] is set — which the single-instrument detail view does and the 23-up
 * grid deliberately does not, since at grid size a minute candle is about a pixel wide and a
 * line is the more honest mark. They are aggregated from this app's own recorded ticks (see
 * [TickCandles] for exactly what that does and does not make them), which also gets EMA 9/21
 * over the candle closes, the day VWAP the feed already reports as `atp`, and the last price
 * tagged in the gutter.
 *
 * Those candles are OBSERVED, not official: the feed is a snapshot feed, so a spike that
 * happened and reversed between two snapshots leaves no wick. `marketOHLC` on the same feed
 * carries the exchange's own OHLC for free and is still being discarded — moving the candle
 * source there would make these match a broker's chart exactly, but the proto does not say
 * which intervals it sends, so that waits on seeing one real payload rather than on a guess.
 *
 * Still absent: zoom, pan and a crosshair. The crosshair is the one that matters next, since
 * it is what turns a picture into a measurement on a phone, where there is no hover.
 *
 * OI overlay: [LiveTickEntity.openInterest] is only non-null for option contracts (NIFTY 50
 * spot is an index, not a derivative, so it never carries OI) — when at least 2 ticks have it,
 * a second line is drawn, normalized against its OWN min/max. This is a "shape" overlay, not a
 * shared-unit axis: the numbered gutter belongs to price alone, and OI's own extremes are
 * printed as text, so a reader is never invited to compare a rupee value against a contract
 * count on one scale — only to see whether the two move together or apart.
 *
 * [displayMode] lets a caller show only price, only OI, or both — driven by a toggle on the
 * screen (Phase 7/9/10), not per-chart. Requesting OI-only on an instrument that has none
 * (NIFTY 50 spot) shows a short explanatory message instead of an empty chart.
 *
 * [chartHeight] defaults to the 160dp every phone screen already used — TV's [ChartGrid]
 * passes a smaller, screen-fitted height for its grid cards and a larger one for its
 * single-chart detail view, but no existing call site needs to change.
 *
 * Buy/sell pressure strip: NOT a third line on the same chart (that read as clutter against
 * price/OI) and NOT one bar per raw tick (there can be many ticks a second — a labeled bar for
 * every one would be unreadable). Ticks with
 * [LiveTickEntity.totalBuyQuantity]/[LiveTickEntity.totalSellQuantity] (option contracts only,
 * same spot exception as OI) are bucketed into [FLOW_BUCKET_MS] windows — the most recent tick
 * in each window stands for it, since TBQ/TSQ are point-in-time snapshots rather than
 * increments — and each window gets TWO adjacent bars (green buy, red sell) with its own
 * abbreviated number, so both quantities stay visible instead of collapsing into one net bar.
 * These bars share the price line's time axis and its right-hand gutter, so a bar sits under
 * whatever point on the line happened at the same moment.
 *
 * Greeks readout: a numeric row (Delta/Theta/Gamma/Vega/Rho), not a chart — Upstox computes
 * these server-side on every tick (null for the index). Shows the most recent tick that
 * actually carries them, so it keeps displaying the last known values between ticks rather
 * than flickering to blank.
 */
@Composable
fun LiveTickChart(
    ticks: List<LiveTickEntity>,
    modifier: Modifier = Modifier,
    displayMode: ChartDisplayMode = ChartDisplayMode.Both,
    chartHeight: Dp = 160.dp,
    candles: Boolean = false,
    compact: Boolean = false
) {
    if (ticks.size < 2) {
        Box(modifier = modifier.height(chartHeight), contentAlignment = Alignment.Center) {
            Text("Not enough ticks yet to draw a chart (need at least 2).")
        }
        return
    }

    // CLOCK-SKEW GUARD. A tick cannot have arrived later than now, and one that claims to did
    // real damage before this existed: a series ending twelve hours in the future stretched the
    // time axis across twelve hours, which compressed an entire real trading day into a few
    // pixels and left the plot looking empty. Every window in this app is measured in
    // receivedAtMillis — the EMA spans, the five-minute panic window, the flow buckets, the
    // minute candles, the parity velocity — so one bad timestamp is not a cosmetic problem.
    //
    // Dropped rather than clamped, because a clamped timestamp is a fabrication that would then
    // be indistinguishable from a real reading. The count is surfaced below, so a skew shows up
    // as a stated fact instead of a chart that merely looks wrong.
    val nowMillis = System.currentTimeMillis()
    val futureTickCount = ticks.count { it.receivedAtMillis > nowMillis + CLOCK_SKEW_TOLERANCE_MS }
    @Suppress("NAME_SHADOWING")
    val ticks = if (futureTickCount == 0) ticks
    else ticks.filter { it.receivedAtMillis <= nowMillis + CLOCK_SKEW_TOLERANCE_MS }

    if (ticks.size < 2) {
        Box(modifier = modifier.height(chartHeight), contentAlignment = Alignment.Center) {
            Text(
                "All $futureTickCount stored tick(s) are timestamped in the future — " +
                    "nothing left to draw. The device clock was wrong when these were recorded."
            )
        }
        return
    }

    // Candles are built from the ticks this app already recorded — see TickCandles for what
    // that does and does not make them. Requested only by the single-instrument detail view:
    // in the 23-up grid a minute candle would be about one pixel wide and a line is the more
    // honest thing to draw at that size.
    val minuteCandles = if (candles) TickCandles.fromTicks(ticks) else emptyList()
    val candleStarts = if (candles) TickCandles.bucketStartsMillis(ticks) else emptyList()
    val drawCandles = candles && minuteCandles.size >= 2 && minuteCandles.size == candleStarts.size

    // The scale must hold the wicks, not just the closes, or a candle would be clipped by its
    // own high.
    val dataLow = if (drawCandles) minuteCandles.minOf { it.low } else ticks.minOf { it.ltp }
    val dataHigh = if (drawCandles) minuteCandles.maxOf { it.high } else ticks.maxOf { it.ltp }
    val dataSpan = dataHigh - dataLow
    val midPrice = (dataLow + dataHigh) / 2.0

    // The floor only ever widens the axis (see MIN_SPAN_FRACTION), and the data is centred in
    // it so a quiet stretch sits as a flat line through the middle instead of being pinned to
    // an edge — which is what a quiet stretch actually looks like.
    val minSpan = abs(midPrice) * MIN_SPAN_FRACTION
    val axisSpan = maxOf(dataSpan, minSpan).takeIf { it > 0.0 } ?: 1.0
    val axisLow = midPrice - axisSpan / 2.0
    val scaleWasFloored = dataSpan < minSpan

    val minTime = ticks.minOf { it.receivedAtMillis }
    val maxTime = ticks.maxOf { it.receivedAtMillis }
    val timeRange = (maxTime - minTime).takeIf { it > 0L } ?: 1L
    fun timeToX(timeMillis: Long, plotWidth: Float): Float =
        plotWidth * (timeMillis - minTime).toFloat() / timeRange.toFloat()

    val gapThreshold = gapBreakThresholdMillis(ticks.map { it.receivedAtMillis }.sorted())

    // (timestamp, OI value) for every tick that actually has one — skips spot ticks entirely,
    // and tolerates any occasional missing OI tick without breaking the shared time axis.
    val oiPoints = ticks.mapNotNull { tick -> tick.openInterest?.let { tick.receivedAtMillis to it } }
    val hasOi = oiPoints.size >= 2
    val minOi = if (hasOi) oiPoints.minOf { it.second } else 0.0
    val maxOi = if (hasOi) oiPoints.maxOf { it.second } else 0.0
    val oiRange = (maxOi - minOi).takeIf { it > 0.0 } ?: 1.0

    if (displayMode == ChartDisplayMode.OiOnly && !hasOi) {
        Box(modifier = modifier.height(chartHeight), contentAlignment = Alignment.Center) {
            Text("No OI for this instrument (e.g. NIFTY 50 spot has none).")
        }
        return
    }

    val showPrice = displayMode != ChartDisplayMode.OiOnly
    val showOi = hasOi && displayMode != ChartDisplayMode.PriceOnly

    // ExponentialMovingAverage returns one value per candle from the period'th onward, so
    // series[k] belongs to minuteCandles[period - 1 + k]. Carrying that offset explicitly is
    // what keeps the averages under the candles they were computed from.
    val emaFast = if (drawCandles) ExponentialMovingAverage.series(minuteCandles, EMA_FAST_PERIOD) else emptyList()
    val emaSlow = if (drawCandles) ExponentialMovingAverage.series(minuteCandles, EMA_SLOW_PERIOD) else emptyList()
    val vwap = if (candles) TickCandles.latestVwap(ticks) else null
    val lastClose = if (drawCandles) minuteCandles.last().close else ticks.maxByOrNull { it.receivedAtMillis }?.ltp

    // Gridline values, on round numbers inside the (possibly floored) axis range.
    val step = niceStep(axisSpan / TARGET_GRIDLINES)
    val gridValues = buildList {
        var value = ceil(axisLow / step) * step
        while (value <= axisLow + axisSpan && size < 12) {
            add(value)
            value += step
        }
    }

    // Bucket every tick that carries buy/sell quantity into FLOW_BUCKET_MS windows, keeping
    // only the most recent tick per window — TBQ/TSQ are point-in-time snapshots, not
    // increments, so "latest in the window" is the right value to represent it, not a sum.
    val flowBuckets = ticks
        .filter { it.totalBuyQuantity != null && it.totalSellQuantity != null }
        .groupBy { (it.receivedAtMillis - minTime) / FLOW_BUCKET_MS }
        .values
        .mapNotNull { bucketTicks -> bucketTicks.maxByOrNull { it.receivedAtMillis } }
        .sortedBy { it.receivedAtMillis }
    val hasFlow = flowBuckets.isNotEmpty()
    val maxFlowQty = if (hasFlow) {
        flowBuckets.flatMap { listOf(it.totalBuyQuantity!!, it.totalSellQuantity!!) }
            .maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    } else {
        1.0
    }

    // Most recent tick that actually carries Greeks (null for NIFTY 50 spot, always) — see the
    // "Greeks readout" doc note above.
    val latestGreeks = ticks.lastOrNull { it.delta != null }

    val textMeasurer = rememberTextMeasurer()
    val axisTextStyle = TextStyle(fontSize = 8.sp, color = AXIS_LABEL_COLOR)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            val gutter = PRICE_GUTTER.toPx()
            val plotWidth = (size.width - gutter).coerceAtLeast(8f)

            fun priceToY(price: Double): Float =
                (size.height * (1.0 - (price - axisLow) / axisSpan)).toFloat()

            // Gridlines + the numbers that make this chart readable as values rather than
            // only as a shape.
            for (value in gridValues) {
                val y = priceToY(value)
                if (y.isNaN() || y < 0f || y > size.height) continue
                drawLine(
                    color = GRID_COLOR,
                    start = Offset(0f, y),
                    end = Offset(plotWidth, y),
                    strokeWidth = 1f
                )
                val label = textMeasurer.measure(
                    if (axisSpan >= 50.0) "%.0f".format(value) else "%.2f".format(value),
                    style = axisTextStyle
                )
                drawText(
                    label,
                    topLeft = Offset(
                        x = plotWidth + 4f,
                        y = (y - label.size.height / 2f).coerceIn(0f, size.height - label.size.height)
                    )
                )
            }

            if (showPrice && drawCandles) {
                // Candle geometry. The slot is one bucket of real time wide, so candles keep
                // their position on the shared axis even where a minute produced no ticks at
                // all — that minute simply has no candle, rather than the ones after it
                // sliding left to fill the hole.
                val slot = (plotWidth * TickCandles.ONE_MINUTE_MS.toFloat() / timeRange.toFloat())
                val bodyWidth = (slot * CANDLE_BODY_FRACTION).coerceIn(1.2f, 26f)

                minuteCandles.forEachIndexed { index, candle ->
                    val centerX = timeToX(candleStarts[index] + TickCandles.ONE_MINUTE_MS / 2, plotWidth)
                    val rising = candle.close >= candle.open
                    val color = if (rising) BUY_COLOR else SELL_COLOR

                    // Wick first, so the body draws over it and the join looks solid.
                    drawLine(
                        color = color,
                        start = Offset(centerX, priceToY(candle.high)),
                        end = Offset(centerX, priceToY(candle.low)),
                        strokeWidth = 1.6f
                    )

                    val openY = priceToY(candle.open)
                    val closeY = priceToY(candle.close)
                    val top = minOf(openY, closeY)
                    // A doji would otherwise be invisible: a minute that opened and closed at
                    // the same price is information, so it gets a 1px line rather than nothing.
                    val bodyHeight = (abs(closeY - openY)).coerceAtLeast(1.2f)
                    drawRect(
                        color = color,
                        topLeft = Offset(centerX - bodyWidth / 2f, top),
                        size = Size(bodyWidth, bodyHeight)
                    )
                }

                // The two averages, over the candle closes they were computed from.
                fun emaPath(series: List<Double>, period: Int): Path? {
                    if (series.size < 2) return null
                    val path = Path()
                    series.forEachIndexed { k, value ->
                        val candleIndex = period - 1 + k
                        if (candleIndex !in candleStarts.indices) return@forEachIndexed
                        val x = timeToX(candleStarts[candleIndex] + TickCandles.ONE_MINUTE_MS / 2, plotWidth)
                        val y = priceToY(value)
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    return path
                }
                emaPath(emaFast, EMA_FAST_PERIOD)?.let {
                    drawPath(it, EMA_FAST_COLOR, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
                }
                emaPath(emaSlow, EMA_SLOW_PERIOD)?.let {
                    drawPath(
                        it, EMA_SLOW_COLOR,
                        style = Stroke(
                            width = 2.4f, cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 5f))
                        )
                    )
                }
            } else if (showPrice) {
                // One path per unbroken run of ticks: a silence longer than the adaptive
                // threshold starts a new run instead of being drawn through.
                val pricePath = Path()
                var previousTime: Long? = null
                for (tick in ticks.sortedBy { it.receivedAtMillis }) {
                    val x = timeToX(tick.receivedAtMillis, plotWidth)
                    val y = priceToY(tick.ltp)
                    val previous = previousTime
                    val broke = previous != null && (tick.receivedAtMillis - previous) > gapThreshold
                    if (previous == null || broke) pricePath.moveTo(x, y) else pricePath.lineTo(x, y)
                    previousTime = tick.receivedAtMillis
                }
                drawPath(
                    path = pricePath,
                    color = PRICE_COLOR,
                    style = Stroke(width = 4f, cap = StrokeCap.Round)
                )
            }

            if (showOi) {
                val oiPath = Path()
                var previousTime: Long? = null
                for ((timeMillis, oi) in oiPoints.sortedBy { it.first }) {
                    val x = timeToX(timeMillis, plotWidth)
                    val normalized = (oi - minOi) / oiRange
                    val y = (size.height * (1.0 - normalized)).toFloat()
                    val previous = previousTime
                    val broke = previous != null && (timeMillis - previous) > gapThreshold
                    if (previous == null || broke) oiPath.moveTo(x, y) else oiPath.lineTo(x, y)
                    previousTime = timeMillis
                }
                drawPath(
                    path = oiPath,
                    color = OI_COLOR,
                    style = Stroke(
                        width = 3f,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                    )
                )
            }

            // Day VWAP as the feed reports it (atp) — Upstox computes it, this app does not.
            if (showPrice && vwap != null) {
                val y = priceToY(vwap)
                if (y >= 0f && y <= size.height) {
                    drawLine(
                        color = VWAP_COLOR,
                        start = Offset(0f, y),
                        end = Offset(plotWidth, y),
                        strokeWidth = 1.4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
                    )
                    val label = textMeasurer.measure(
                        "VWAP %.2f".format(vwap),
                        style = TextStyle(fontSize = 8.sp, color = VWAP_COLOR)
                    )
                    drawText(label, topLeft = Offset(3f, (y - label.size.height - 2f).coerceAtLeast(0f)))
                }
            }

            // Last price, tagged in the gutter the way every trading chart does it — the one
            // number a glance is usually looking for.
            if (showPrice && lastClose != null) {
                val y = priceToY(lastClose)
                if (y >= 0f && y <= size.height) {
                    val label = textMeasurer.measure(
                        "%.2f".format(lastClose),
                        style = TextStyle(fontSize = 8.5.sp, color = Color.White)
                    )
                    val padX = 3f
                    val boxW = (label.size.width + padX * 2).coerceAtMost(gutter - 4f)
                    val boxH = label.size.height + 3f
                    val boxY = (y - boxH / 2f).coerceIn(0f, size.height - boxH)
                    drawLine(
                        color = PRICE_COLOR,
                        start = Offset(0f, y), end = Offset(plotWidth, y),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f))
                    )
                    drawRect(
                        color = PRICE_COLOR,
                        topLeft = Offset(plotWidth + 3f, boxY),
                        size = Size(boxW, boxH)
                    )
                    drawText(label, topLeft = Offset(plotWidth + 3f + padX, boxY + 1.5f))
                }
            }
        }

        // Time axis. Three labels is what fits a phone-width card without overlapping, and
        // start/middle/end is enough to place any point on the line to within a glance.
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(formatClockIst(minTime), style = MaterialTheme.typography.labelSmall, color = AXIS_LABEL_COLOR)
            Text(
                formatClockIst(minTime + timeRange / 2),
                style = MaterialTheme.typography.labelSmall,
                color = AXIS_LABEL_COLOR,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(formatClockIst(maxTime), style = MaterialTheme.typography.labelSmall, color = AXIS_LABEL_COLOR)
        }

        // Stated on the chart itself, not only in a log: ticks were thrown away, and a reader
        // is entitled to know their chart is drawn on less than what is stored.
        if (futureTickCount > 0) {
            Text(
                "$futureTickCount tick(s) timestamped in the future were left out — " +
                    "the device clock was wrong when they were recorded.",
                style = MaterialTheme.typography.labelSmall,
                color = SELL_COLOR
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Suppressed when the range is a single repeated number, because the flat note
            // below already says it and "Range 133.60–133.60" says it worse.
            if (showPrice && !(compact && dataSpan <= 0.0)) {
                Text(
                    "Range %.2f–%.2f".format(dataLow, dataHigh),
                    style = MaterialTheme.typography.bodySmall,
                    color = PRICE_COLOR
                )
            }
            // The OI range is nine digits wide twice over; next to the price range at half a
            // phone's width it is the item that forces the row to wrap, and it is also the
            // least useful of the two at a glance, since the dashed line's SHAPE is what is
            // being read there, not its endpoints.
            if (showOi && !compact) {
                Text(
                    "OI %.0f–%.0f (dashed)".format(minOi, maxOi),
                    style = MaterialTheme.typography.bodySmall,
                    color = OI_COLOR
                )
            }
        }

        // Identity never rests on colour alone here — see EMA_FAST_COLOR's note. Each average
        // is named next to a swatch of its own line, with its current value, so the pair is
        // distinguishable without relying on telling blue from teal.
        if (drawCandles && (emaFast.isNotEmpty() || emaSlow.isNotEmpty())) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (emaFast.isNotEmpty()) {
                    Text("\u2014", color = EMA_FAST_COLOR, style = MaterialTheme.typography.labelSmall)
                    Text(
                        "EMA $EMA_FAST_PERIOD %.2f".format(emaFast.last()),
                        style = MaterialTheme.typography.labelSmall,
                        color = AXIS_LABEL_COLOR
                    )
                }
                if (emaSlow.isNotEmpty()) {
                    Text("\u2013 \u2013", color = EMA_SLOW_COLOR, style = MaterialTheme.typography.labelSmall)
                    Text(
                        "EMA $EMA_SLOW_PERIOD %.2f".format(emaSlow.last()),
                        style = MaterialTheme.typography.labelSmall,
                        color = AXIS_LABEL_COLOR
                    )
                }
            }
            if (!compact) {
                Text(
                    "Candles and these averages are 1-minute, aggregated from recorded ticks. " +
                        "The dashboard's Trend signal reads 15-minute candles from Upstox, so " +
                        "the two can legitimately disagree — they are different timeframes, " +
                        "not a bug.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AXIS_LABEL_COLOR
                )
            }
        }

        // Said out loud rather than left for the reader to misjudge: when the move is smaller
        // than the axis floor, the flatness of the line is the point, not a rendering quirk.
        //
        // Two wordings, because the same sentence cannot serve both sizes. Side by side at half
        // a phone's width — which is exactly where a flat chart is most likely to appear, since
        // that is the at-the-money pair — the full sentence wrapped to three lines and pushed
        // the chart it was explaining off the screen. The short form says the same thing.
        if (showPrice && scaleWasFloored) {
            Text(
                if (compact) "Flat — moved %.2f".format(dataSpan)
                else "Moved only %.2f — axis widened to %.2f so this reads as flat, because it is."
                    .format(dataSpan, axisSpan),
                style = MaterialTheme.typography.labelSmall,
                color = AXIS_LABEL_COLOR
            )
        }

        if (latestGreeks != null) {
            Text(
                if (compact) {
                    // Delta and theta are the two a glance at the money is actually reading.
                    // The full set stays one tap away, on the chart the row expands to.
                    "Δ %.2f  Θ %.1f".format(latestGreeks.delta, latestGreeks.theta)
                } else {
                    "Δ %.3f  Θ %.2f  Γ %.4f  V %.2f  ρ %.2f".format(
                        latestGreeks.delta, latestGreeks.theta, latestGreeks.gamma,
                        latestGreeks.vega, latestGreeks.rho
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = GREEKS_COLOR
            )
        }

        if (hasFlow) {
            // The legend is suppressed in compact mode rather than shortened: two charts side
            // by side would print it twice, and the caller shows it once above the pair.
            if (!compact) {
                Text(
                    "Buy vs sell qty, ~every ${FLOW_BUCKET_MS / 1000}s (green = buy, red = sell)",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FLOW_BAR_HEIGHT)
            ) {
                // Same gutter as the plot above, so a bar stays under the moment it belongs to.
                val gutter = PRICE_GUTTER.toPx()
                val plotWidth = (size.width - gutter).coerceAtLeast(8f)
                val labelReserve = 16.dp.toPx()
                val maxBarPx = (size.height - labelReserve).coerceAtLeast(4f)
                val barWidth = 5.dp.toPx()
                val gap = 2.dp.toPx()

                for (tick in flowBuckets) {
                    val centerX = timeToX(tick.receivedAtMillis, plotWidth)
                    val buyQty = tick.totalBuyQuantity!!
                    val sellQty = tick.totalSellQuantity!!
                    val buyHeight = (maxBarPx * (buyQty / maxFlowQty).toFloat()).coerceIn(0f, maxBarPx)
                    val sellHeight = (maxBarPx * (sellQty / maxFlowQty).toFloat()).coerceIn(0f, maxBarPx)

                    val buyLeft = centerX - gap / 2f - barWidth
                    val sellLeft = centerX + gap / 2f

                    drawRect(
                        color = BUY_COLOR,
                        topLeft = Offset(buyLeft, size.height - buyHeight),
                        size = Size(barWidth, buyHeight)
                    )
                    drawRect(
                        color = SELL_COLOR,
                        topLeft = Offset(sellLeft, size.height - sellHeight),
                        size = Size(barWidth, sellHeight)
                    )

                    // Bar values are dropped in compact mode. At half a phone's width the
                    // buy and sell labels of one bucket overlap each other and the next
                    // bucket's, and overlapping digits are not a smaller number — they are an
                    // unreadable one. The bars' relative heights still carry the comparison,
                    // which is all this strip is for at that size.
                    if (compact) continue

                    val buyLabel = textMeasurer.measure(
                        formatQuantityShort(buyQty),
                        style = TextStyle(fontSize = 8.sp, color = BUY_COLOR)
                    )
                    drawText(
                        buyLabel,
                        topLeft = Offset(
                            x = buyLeft - (buyLabel.size.width - barWidth) / 2f,
                            y = size.height - buyHeight - buyLabel.size.height
                        )
                    )

                    val sellLabel = textMeasurer.measure(
                        formatQuantityShort(sellQty),
                        style = TextStyle(fontSize = 8.sp, color = SELL_COLOR)
                    )
                    drawText(
                        sellLabel,
                        topLeft = Offset(
                            x = sellLeft - (sellLabel.size.width - barWidth) / 2f,
                            y = size.height - sellHeight - sellLabel.size.height
                        )
                    )
                }
            }
        }
    }
}
