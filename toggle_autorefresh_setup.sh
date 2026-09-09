#!/data/data/com.termux/files/usr/bin/bash
set -e
cd ~/nifty-build/NiftyOptionRadar

echo "Writing Both/Price/OI toggle + auto-refresh files..."

mkdir -p "$(dirname "app/src/main/java/com/niftyradar/app/ui/LiveTickChart.kt")"
cat > "app/src/main/java/com/niftyradar/app/ui/LiveTickChart.kt" << 'LIVETICKCHART_EOF'
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
LIVETICKCHART_EOF
echo "Wrote app/src/main/java/com/niftyradar/app/ui/LiveTickChart.kt"

mkdir -p "$(dirname "app/src/main/java/com/niftyradar/app/ui/ChartGrid.kt")"
cat > "app/src/main/java/com/niftyradar/app/ui/ChartGrid.kt" << 'CHARTGRID_EOF'
package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 *
 * [displayMode] is forwarded to every [LiveTickChart] unchanged — one
 * screen-level toggle switches the whole grid between price/OI/both at
 * once, rather than each card having its own.
 */
@Composable
fun <T> ChartGrid(
    items: List<T>,
    label: (T) -> String,
    instrumentKey: (T) -> String,
    ticksByInstrument: Map<String, List<LiveTickEntity>>,
    displayMode: ChartDisplayMode = ChartDisplayMode.Both
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
                                    modifier = Modifier.fillMaxWidth(),
                                    displayMode = displayMode
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

/**
 * The "Both / Price / OI" row shown above a [ChartGrid] on Phase 7, 9, and
 * 10 — one shared toggle switches every chart on the screen together.
 * [current] and [onSelect] are hoisted so each screen keeps its own
 * `remember { mutableStateOf(...) }` state (this composable holds none).
 */
@Composable
fun ChartDisplayModeToggle(current: ChartDisplayMode, onSelect: (ChartDisplayMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = listOf(
            ChartDisplayMode.Both to "Both",
            ChartDisplayMode.PriceOnly to "Price",
            ChartDisplayMode.OiOnly to "OI"
        )
        for ((mode, label) in options) {
            if (mode == current) {
                Button(onClick = { onSelect(mode) }) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(mode) }) { Text(label) }
            }
        }
    }
}
CHARTGRID_EOF
echo "Wrote app/src/main/java/com/niftyradar/app/ui/ChartGrid.kt"

mkdir -p "$(dirname "app/src/main/java/com/niftyradar/app/ui/Phase7Screen.kt")"
cat > "app/src/main/java/com/niftyradar/app/ui/Phase7Screen.kt" << 'PHASE7SCREEN_EOF'
package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How often the auto-refresh loop below re-reads stored ticks while this screen is open. */
private const val AUTO_REFRESH_INTERVAL_MS = 5_000L

/**
 * PHASE 7 SCREEN: PROJECT_SPEC.md section 20 step 8 — the same idea as
 * Phase 6, just for all 22 locked contracts instead of only the ATM CE one.
 * Reuses [LiveTickChart] unchanged.
 *
 * TV support: charts render via [ChartGrid] instead of one long vertical
 * list — a single column on a phone-width screen (unchanged), several
 * columns side by side on a wide TV screen.
 *
 * Also adds a [ChartDisplayModeToggle] (Both/Price/OI, applied to every
 * chart at once) and an auto-refresh loop — every [AUTO_REFRESH_INTERVAL_MS]
 * this screen re-reads whatever's newest in storage on its own.
 */
@Composable
fun Phase7Screen(viewModel: Phase7ViewModel, onBack: () -> Unit, onContinueToPhase8: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val ticksByInstrument by viewModel.ticksByInstrument.collectAsState()
    var displayMode by remember { mutableStateOf(ChartDisplayMode.Both) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(AUTO_REFRESH_INTERVAL_MS)
            viewModel.refreshAll()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }

        Text("Phase 7 — All 22 Option Charts", style = MaterialTheme.typography.titleMedium)
        Text(
            "Same idea as Phase 6, repeated for every locked contract (11 strikes × CE/PE) " +
                "instead of just the ATM call. No new chart code — this reuses the exact same " +
                "chart, just 22 times.",
            style = MaterialTheme.typography.bodyMedium
        )

        when (val state = uiState) {
            is Phase7UiState.NoRadarLocked -> {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No radar locked for today yet.", style = MaterialTheme.typography.titleMedium)
                        Text("Go back, lock today's radar, connect Phase 4's feed, and let a few ticks arrive first.")
                    }
                }
            }
            is Phase7UiState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.refreshAll() }) {
                        Text("Refresh now")
                    }
                }
                Text(
                    "Auto-refreshing every ${AUTO_REFRESH_INTERVAL_MS / 1000}s while this screen is open.",
                    style = MaterialTheme.typography.bodySmall
                )
                ChartDisplayModeToggle(current = displayMode, onSelect = { displayMode = it })
                ChartGrid(
                    items = state.contracts,
                    label = { it.label },
                    instrumentKey = { it.instrumentKey },
                    ticksByInstrument = ticksByInstrument,
                    displayMode = displayMode
                )
                HorizontalDivider()
                Button(onClick = onContinueToPhase8, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue to Phase 8 — NIFTY Spot Chart →")
                }
            }
        }
    }
}
PHASE7SCREEN_EOF
echo "Wrote app/src/main/java/com/niftyradar/app/ui/Phase7Screen.kt"

mkdir -p "$(dirname "app/src/main/java/com/niftyradar/app/ui/Phase9Screen.kt")"
cat > "app/src/main/java/com/niftyradar/app/ui/Phase9Screen.kt" << 'PHASE9SCREEN_EOF'
package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How often the auto-refresh loop below re-reads stored ticks while this screen is open. */
private const val AUTO_REFRESH_INTERVAL_MS = 5_000L

/**
 * PHASE 9 SCREEN: PROJECT_SPEC.md section 20 step 10 — the final combined
 * radar view. NIFTY 50 spot (Phase 8) plus all 22 locked option contracts
 * (Phase 7), all 23 charts on one screen. Reuses [LiveTickChart] unchanged.
 *
 * TV support: charts render via [ChartGrid] instead of one long vertical
 * list — a single column on a phone-width screen (unchanged), several
 * columns side by side on a wide TV screen, so the whole radar is visible
 * with much less scrolling.
 *
 * Also adds a [ChartDisplayModeToggle] (Both/Price/OI, applied to every
 * chart at once) and an auto-refresh loop — every [AUTO_REFRESH_INTERVAL_MS]
 * this screen re-reads whatever's newest in storage on its own, so "Refresh
 * all charts" becomes an optional manual nudge rather than the only way to
 * see new ticks.
 */
@Composable
fun Phase9Screen(viewModel: Phase9ViewModel, onBack: () -> Unit, onContinueToPhase10: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val ticksByInstrument by viewModel.ticksByInstrument.collectAsState()
    var displayMode by remember { mutableStateOf(ChartDisplayMode.Both) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(AUTO_REFRESH_INTERVAL_MS)
            viewModel.refreshAll()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }

        Text("Phase 9 — Full Radar View", style = MaterialTheme.typography.titleMedium)
        Text(
            "All 23 charts together — NIFTY 50 spot plus all 22 locked option contracts. " +
                "This is the final radar screen the spec describes; still reuses the exact " +
                "same chart component as every phase before it.",
            style = MaterialTheme.typography.bodyMedium
        )

        when (val state = uiState) {
            is Phase9UiState.NoRadarLocked -> {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No radar locked for today yet.", style = MaterialTheme.typography.titleMedium)
                        Text("Go back, lock today's radar, connect Phase 4's feed, and let a few ticks arrive first.")
                    }
                }
            }
            is Phase9UiState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.refreshAll() }) {
                        Text("Refresh now")
                    }
                }
                Text(
                    "Auto-refreshing every ${AUTO_REFRESH_INTERVAL_MS / 1000}s while this screen is open.",
                    style = MaterialTheme.typography.bodySmall
                )
                ChartDisplayModeToggle(current = displayMode, onSelect = { displayMode = it })
                ChartGrid(
                    items = state.items,
                    label = { it.label },
                    instrumentKey = { it.instrumentKey },
                    ticksByInstrument = ticksByInstrument,
                    displayMode = displayMode
                )
            }
        }

        HorizontalDivider()
        Button(onClick = onContinueToPhase10, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to Phase 10 — Session History →")
        }
    }
}
PHASE9SCREEN_EOF
echo "Wrote app/src/main/java/com/niftyradar/app/ui/Phase9Screen.kt"

mkdir -p "$(dirname "app/src/main/java/com/niftyradar/app/ui/Phase10Screen.kt")"
cat > "app/src/main/java/com/niftyradar/app/ui/Phase10Screen.kt" << 'PHASE10SCREEN_EOF'
package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * PHASE 10 SCREEN: PROJECT_SPEC.md section 20 steps 10-11 — daily session
 * locking (every day is now kept, not just today) and historical review
 * (pick a past locked day and see its 23 charts, same as Phase 9 for today).
 * This is the last of the 11 spec steps.
 *
 * TV support: charts render via [ChartGrid] instead of one long vertical
 * list — a single column on a phone-width screen (unchanged), several
 * columns side by side on a wide TV screen. Also adds a
 * [ChartDisplayModeToggle] (Both/Price/OI) — no auto-refresh here, unlike
 * Phase 7/9, since a locked past date's ticks never change.
 */
@Composable
fun Phase10Screen(viewModel: Phase10ViewModel, onBack: () -> Unit) {
    val lockedDates by viewModel.lockedDates.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val items by viewModel.items.collectAsState()
    val ticksByInstrument by viewModel.ticksByInstrument.collectAsState()
    var displayMode by remember { mutableStateOf(ChartDisplayMode.Both) }

    LaunchedEffect(Unit) {
        viewModel.loadDates()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }

        Text("Phase 10 — Session History", style = MaterialTheme.typography.titleMedium)
        Text(
            "Every day's locked radar is now kept, not just today's. Pick a past date " +
                "below to reload its 23 charts exactly the way Phase 9 shows today's.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (selectedDate == null) {
            if (lockedDates.isEmpty()) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No locked sessions found yet.", style = MaterialTheme.typography.titleMedium)
                        Text("Lock a radar (Phase 2/3) on any day and it will show up here afterwards.")
                    }
                }
            } else {
                Text("Locked dates:", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (date in lockedDates) {
                        OutlinedButton(
                            onClick = { viewModel.selectDate(date) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(date)
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.clearSelection() }) { Text("← Choose a different date") }
            }
            Text("Session: $selectedDate", style = MaterialTheme.typography.titleSmall)
            ChartDisplayModeToggle(current = displayMode, onSelect = { displayMode = it })
            ChartGrid(
                items = items,
                label = { it.label },
                instrumentKey = { it.instrumentKey },
                ticksByInstrument = ticksByInstrument,
                displayMode = displayMode
            )
        }
    }
}
PHASE10SCREEN_EOF
echo "Wrote app/src/main/java/com/niftyradar/app/ui/Phase10Screen.kt"

git add app/src/main/java/com/niftyradar/app/ui/LiveTickChart.kt app/src/main/java/com/niftyradar/app/ui/ChartGrid.kt app/src/main/java/com/niftyradar/app/ui/Phase7Screen.kt app/src/main/java/com/niftyradar/app/ui/Phase9Screen.kt app/src/main/java/com/niftyradar/app/ui/Phase10Screen.kt
git commit -m "Add Both/Price/OI display toggle to every chart screen, and auto-refresh (every 5s) on Phase 7 and Phase 9"
git push
echo "Done. Now go to GitHub Actions and check the build."
