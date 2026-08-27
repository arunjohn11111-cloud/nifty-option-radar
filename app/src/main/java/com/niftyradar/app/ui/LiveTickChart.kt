package com.niftyradar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/**
 * Phase 6: the simplest possible live line chart — LTP over the sequence of
 * ticks stored today for one instrument (read back from Room via
 * [com.niftyradar.app.storage.LiveTickStore.ticksFor]). No axes, no zoom/pan,
 * no time-based x-spacing (ticks are just spaced evenly by index) — that's
 * all deliberately deferred. This exists purely to prove "stored ticks ->
 * a line that moves" before Phase 7 repeats the same idea for all 22
 * contracts.
 */
@Composable
fun LiveTickChart(ticks: List<LiveTickEntity>, modifier: Modifier = Modifier) {
    if (ticks.size < 2) {
        Box(modifier = modifier.height(160.dp), contentAlignment = Alignment.Center) {
            Text("Not enough ticks yet to draw a chart (need at least 2).")
        }
        return
    }

    val minLtp = ticks.minOf { it.ltp }
    val maxLtp = ticks.maxOf { it.ltp }
    val range = (maxLtp - minLtp).takeIf { it > 0.0 } ?: 1.0

    Column(modifier = modifier) {
        Text("High: %.2f".format(maxLtp), style = MaterialTheme.typography.bodySmall)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        ) {
            val stepX = size.width / (ticks.size - 1)
            val path = Path()
            ticks.forEachIndexed { index, tick ->
                val x = index * stepX
                val normalized = (tick.ltp - minLtp) / range
                // Canvas y=0 is the TOP, so a higher LTP must map to a SMALLER y.
                val y = (size.height * (1.0 - normalized)).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = Color(0xFF6750A4),
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
        }
        Text("Low: %.2f".format(minLtp), style = MaterialTheme.typography.bodySmall)
    }
}
