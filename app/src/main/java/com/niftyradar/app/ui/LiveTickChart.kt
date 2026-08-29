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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.niftyradar.app.storage.LiveTickEntity

private val PRICE_COLOR = Color(0xFF6750A4)
private val OI_COLOR = Color(0xFFE8871E)

/** Which line(s) [LiveTickChart] draws — set from a screen-level toggle so the
 *  whole radar switches together instead of per-chart. */
enum class ChartDisplayMode { Both, PriceOnly, OiOnly }

/**
 * Phase 6: the simplest possible live line chart — LTP over the sequence of
 * ticks stored today for one instrument (read back from Room via
 * [com.niftyradar.app.storage.LiveTickStore.ticksFor]). No axes, no zoom/pan,
 * no time-based x-spacing (ticks are just spaced evenly by index) — that's
 * all deliberately deferred. This exists purely to prove "stored ticks ->
 * a line that moves" before Phase 7 repeats the same idea for all 22
 * contracts.
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
 */
@Composable
fun LiveTickChart(
    ticks: List<LiveTickEntity>,
    modifier: Modifier = Modifier,
    displayMode: ChartDisplayMode = ChartDisplayMode.Both
) {
    if (ticks.size < 2) {
        Box(modifier = modifier.height(160.dp), contentAlignment = Alignment.Center) {
            Text("Not enough ticks yet to draw a chart (need at least 2).")
        }
        return
    }

    val minLtp = ticks.minOf { it.ltp }
    val maxLtp = ticks.maxOf { it.ltp }
    val priceRange = (maxLtp - minLtp).takeIf { it > 0.0 } ?: 1.0

    // (index in `ticks`, OI value) for every tick that actually has one —
    // skips spot ticks entirely, and tolerates any occasional missing OI
    // tick without breaking x-alignment with the price line.
    val oiPoints = ticks.mapIndexedNotNull { index, tick -> tick.openInterest?.let { index to it } }
    val hasOi = oiPoints.size >= 2
    val minOi = if (hasOi) oiPoints.minOf { it.second } else 0.0
    val maxOi = if (hasOi) oiPoints.maxOf { it.second } else 0.0
    val oiRange = (maxOi - minOi).takeIf { it > 0.0 } ?: 1.0

    if (displayMode == ChartDisplayMode.OiOnly && !hasOi) {
        Box(modifier = modifier.height(160.dp), contentAlignment = Alignment.Center) {
            Text("No OI for this instrument (e.g. NIFTY 50 spot has none).")
        }
        return
    }

    val showPrice = displayMode != ChartDisplayMode.OiOnly
    val showOi = hasOi && displayMode != ChartDisplayMode.PriceOnly

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
                .height(160.dp)
        ) {
            val stepX = size.width / (ticks.size - 1)

            if (showPrice) {
                val pricePath = Path()
                ticks.forEachIndexed { index, tick ->
                    val x = index * stepX
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
                oiPoints.forEachIndexed { pointIndex, (tickIndex, oi) ->
                    val x = tickIndex * stepX
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
    }
}
