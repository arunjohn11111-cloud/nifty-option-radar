package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niftyradar.app.storage.LiveTickEntity

/**
 * TV support: lays a list of (label, instrument key) chart entries out as a
 * responsive grid of small chart cards instead of one long vertical list —
 * used by Phase 7, Phase 9, and Phase 10's screens in place of their old
 * "for (item in items) { HorizontalDivider(); Text(...); LiveTickChart(...) }"
 * loop.
 *
 * On a narrow phone screen [maxWidth] only fits one 220dp-plus column, so
 * this renders as a single column, same as before this change. On a wide
 * TV screen it fits several, so more of the radar is visible at once
 * without scrolling — which is the whole point of a "radar" screen on a
 * big screen.
 *
 * Deliberately NOT LazyVerticalGrid: that composable measures itself with
 * an unbounded height and crashes when nested inside an already-scrolling
 * Column (which is how every phase screen here is built). Chunking [items]
 * into plain Rows avoids that entirely, at the cost of the grid not being
 * lazy — fine at 23 items.
 */
@Composable
fun <T> ChartGrid(
    items: List<T>,
    label: (T) -> String,
    instrumentKey: (T) -> String,
    ticksByInstrument: Map<String, List<LiveTickEntity>>
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnCount = (maxWidth / 220.dp).toInt().coerceIn(1, 4)
        val rows = items.chunked(columnCount)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (entry in row) {
                        Card(modifier = Modifier.weight(1f)) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(label(entry), style = MaterialTheme.typography.titleSmall)
                                LiveTickChart(
                                    ticks = ticksByInstrument[instrumentKey(entry)] ?: emptyList(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    // Pad a short last row with empty weighted spacers so
                    // earlier full rows' columns still line up underneath it.
                    if (row.size < columnCount) {
                        repeat(columnCount - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
