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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niftyradar.app.storage.LiveTickEntity
import kotlin.math.abs

private val PRICE_COLOR = Color(0xFF6750A4)
private val OI_COLOR = Color(0xFFE8871E)
private val BUY_COLOR = Color(0xFF2E7D32)
private val SELL_COLOR = Color(0xFFC62828)
private val GREEKS_COLOR = Color(0xFF00695C)

/** How wide a time window one buy/sell bar pair represents — matches the screens' 5s
 *  auto-refresh cadence, so "one bar" reads as "one refresh's worth of order flow". */
private const val FLOW_BUCKET_MS = 5_000L
private val FLOW_BAR_HEIGHT = 64.dp

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
 * Phase 6: the simplest possible live line chart — LTP over the sequence of
 * ticks stored today for one instrument (read back from Room via
 * [com.niftyradar.app.storage.LiveTickStore.ticksFor]). No axes, no zoom/pan —
 * that's deliberately deferred. This exists purely to prove "stored ticks ->
 * a line that moves" before Phase 7 repeats the same idea for all 22
 * contracts.
 *
 * The price and OI lines are positioned by ACTUAL elapsed time
 * ([LiveTickEntity.receivedAtMillis]), not by tick index — this matters once the buy/sell
 * pressure strip below exists, since that strip buckets ticks into 5-second windows (fewer
 * points than the raw tick series); sharing one real time axis is what keeps "this OI move"
 * and "the buy/sell bar right below it" pointing at the same moment, not just the same list
 * position.
 *
 * OI overlay: [LiveTickEntity.openInterest] is only non-null for option
 * contracts (NIFTY 50 spot is an index, not a derivative, so it never carries
 * OI) — when at least 2 ticks in [ticks] have it, a second line is drawn for
 * it, normalized against its OWN min/max (not the price's), same as the
 * price line. This is a "shape" overlay, not a shared-unit axis: there are
 * no numeric axis labels on either line, only the High/Low text below, so
 * a reader is never invited to compare a price rupee value against an OI
 * contract count on the same scale — just to see whether the two are moving
 * together or apart.
 *
 * [displayMode] lets a caller show only price, only OI, or both — driven by
 * a toggle on the screen (Phase 7/9/10), not per-chart. Requesting OI-only
 * on an instrument that has none (NIFTY 50 spot) shows a short explanatory
 * message instead of an empty chart.
 *
 * [chartHeight] defaults to the original fixed 160dp every phone screen already used — TV's
 * [ChartGrid] passes a smaller, screen-fitted height for its grid cards and a larger one for its
 * single-chart detail view, but no existing call site needs to change.
 *
 * Buy/sell pressure strip: NOT a third line on the same chart (that read as confusing clutter
 * against price/OI) and NOT one bar per raw tick (there can be many ticks a second — a labeled
 * bar for every single one would be unreadable). Instead, ticks with
 * [LiveTickEntity.totalBuyQuantity]/[LiveTickEntity.totalSellQuantity] (option contracts only,
 * same NIFTY-50-spot exception as OI) are bucketed into [FLOW_BUCKET_MS] windows — the most
 * recent tick in each window stands for that window — and each window gets TWO adjacent bars
 * (green = buy quantity, red = sell quantity), each labeled with its own abbreviated number, so
 * both actual quantities stay visible rather than collapsing to one net-direction bar. Bar
 * height is scaled against the largest quantity seen anywhere in the visible strip. Because
 * these bars sit on the same real-time x-axis as the price/OI lines above, a buy/sell bar
 * lines up under whatever point on the OI line happened at the same moment.
 *
 * Greeks readout: a single numeric row (Delta/Theta/Gamma/Vega/Rho), not a chart — these are
 * server-computed by Upstox on every tick (same NIFTY-50-spot exception as OI: null for the
 * index), and for a first pass the point is just to prove real values are flowing end-to-end
 * from the feed through Room to the screen before anything (the Gamma Exposure condition,
 * Target/SL sizing) is built on top of them. Shows the most recent tick that actually carries
 * Greeks, so it keeps displaying the last known values between ticks rather than flickering to
 * blank.
 */
@Composable
fun LiveTickChart(
    ticks: List<LiveTickEntity>,
    modifier: Modifier = Modifier,
    displayMode: ChartDisplayMode = ChartDisplayMode.Both,
    chartHeight: Dp = 160.dp
) {
    if (ticks.size < 2) {
        Box(modifier = modifier.height(chartHeight), contentAlignment = Alignment.Center) {
            Text("Not enough ticks yet to draw a chart (need at least 2).")
        }
        return
    }

    val minLtp = ticks.minOf { it.ltp }
    val maxLtp = ticks.maxOf { it.ltp }
    val priceRange = (maxLtp - minLtp).takeIf { it > 0.0 } ?: 1.0

    val minTime = ticks.minOf { it.receivedAtMillis }
    val maxTime = ticks.maxOf { it.receivedAtMillis }
    val timeRange = (maxTime - minTime).takeIf { it > 0L } ?: 1L
    fun timeToX(timeMillis: Long, width: Float): Float =
        width * (timeMillis - minTime).toFloat() / timeRange.toFloat()

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

    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showPrice) {
                Text("High: %.2f".format(maxLtp), style = MaterialTheme.typography.bodySmall, color = PRICE_COLOR)
            }
            if (showOi) {
                Text("OI High: %.0f".format(maxOi), style = MaterialTheme.typography.bodySmall, color = OI_COLOR)
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            if (showPrice) {
                val pricePath = Path()
                ticks.forEachIndexed { index, tick ->
                    val x = timeToX(tick.receivedAtMillis, size.width)
                    val normalized = (tick.ltp - minLtp) / priceRange
                    // Canvas y=0 is the TOP, so a higher LTP must map to a SMALLER y.
                    val y = (size.height * (1.0 - normalized)).toFloat()
                    if (index == 0) pricePath.moveTo(x, y) else pricePath.lineTo(x, y)
                }
                drawPath(
                    path = pricePath,
                    color = PRICE_COLOR,
                    style = Stroke(width = 4f, cap = StrokeCap.Round)
                )
            }

            if (showOi) {
                val oiPath = Path()
                oiPoints.forEachIndexed { pointIndex, (timeMillis, oi) ->
                    val x = timeToX(timeMillis, size.width)
                    val normalized = (oi - minOi) / oiRange
                    val y = (size.height * (1.0 - normalized)).toFloat()
                    if (pointIndex == 0) oiPath.moveTo(x, y) else oiPath.lineTo(x, y)
                }
                drawPath(
                    path = oiPath,
                    color = OI_COLOR,
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showPrice) {
                Text("Low: %.2f".format(minLtp), style = MaterialTheme.typography.bodySmall, color = PRICE_COLOR)
            }
            if (showOi) {
                Text("OI Low: %.0f".format(minOi), style = MaterialTheme.typography.bodySmall, color = OI_COLOR)
            }
        }

        if (latestGreeks != null) {
            Text(
                "Δ %.3f  Θ %.2f  Γ %.4f  V %.2f  ρ %.2f".format(
                    latestGreeks.delta, latestGreeks.theta, latestGreeks.gamma,
                    latestGreeks.vega, latestGreeks.rho
                ),
                style = MaterialTheme.typography.labelSmall,
                color = GREEKS_COLOR
            )
        }

        if (hasFlow) {
            val textMeasurer = rememberTextMeasurer()
            Text(
                "Buy vs sell qty, ~every ${FLOW_BUCKET_MS / 1000}s (green = buy, red = sell)",
                style = MaterialTheme.typography.labelSmall
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FLOW_BAR_HEIGHT)
            ) {
                val labelReserve = 16.dp.toPx()
                val maxBarPx = (size.height - labelReserve).coerceAtLeast(4f)
                val barWidth = 5.dp.toPx()
                val gap = 2.dp.toPx()

                for (tick in flowBuckets) {
                    val centerX = timeToX(tick.receivedAtMillis, size.width)
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
