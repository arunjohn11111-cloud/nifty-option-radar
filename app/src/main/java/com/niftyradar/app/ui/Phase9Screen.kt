package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.niftyradar.app.domain.DashboardResult
import com.niftyradar.app.domain.IndicatorSignal
import com.niftyradar.app.domain.SignalDirection
import kotlinx.coroutines.delay

private val BULLISH_COLOR = Color(0xFF2E7D32)
private val BEARISH_COLOR = Color(0xFFC62828)
private val NEUTRAL_COLOR = Color(0xFF757575)

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
    val dailyLevels by viewModel.dailyLevels.collectAsState()
    val dashboard by viewModel.dashboard.collectAsState()
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
                DailyLevelsCard(dailyLevels)
                DashboardCard(dashboard)
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

/**
 * Step 2's dashboard card: every indicator gets a colored arrow AND a short text reason
 * together (the user was explicit that both are wanted, not either/or), plus an aggregate
 * "X of N bullish/bearish/neutral" line. Currently 5 of the eventual 6 indicators — see
 * [com.niftyradar.app.domain.IndicatorEngine]'s doc comment (only ATR is left out, since it
 * never votes). The "great indication" notification (5-6/6 agreeing) is intentionally NOT
 * built here — it needs its own Android notification-channel + vibration + overlay-flash
 * wiring, which is its own increment, not part of proving the indicator math itself.
 */
@Composable
private fun DashboardCard(dashboard: DashboardResult?) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("6-Indicator Dashboard (5 of 6 so far)", style = MaterialTheme.typography.titleSmall)
            if (dashboard == null) {
                Text(
                    "Waiting for pivot levels + live ticks...",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                for (signal in dashboard.signals) {
                    IndicatorRow(signal)
                }
                HorizontalDivider()
                Text(
                    "${dashboard.bullishCount} of ${dashboard.total} Bullish, " +
                        "${dashboard.bearishCount} of ${dashboard.total} Bearish, " +
                        "${dashboard.neutralCount} of ${dashboard.total} Neutral",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun IndicatorRow(signal: IndicatorSignal) {
    val (arrow, color) = when (signal.direction) {
        SignalDirection.BULLISH -> "⬆️" to BULLISH_COLOR
        SignalDirection.BEARISH -> "⬇️" to BEARISH_COLOR
        SignalDirection.NEUTRAL -> "➡️" to NEUTRAL_COLOR
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, color = color, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Text(signal.name, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        Text(
            signal.reason,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 28.dp)
        )
    }
}

/**
 * Step 1b's "prove it visually" checkpoint (same idea as the per-contract Greeks readout
 * row from Step 1a): NIFTY 50 spot's previous-day classic pivot levels and daily-candle
 * ATR(14), fetched once via [Phase9ViewModel.dailyLevels] — see that ViewModel's doc comment.
 * Just a numeric readout for now; the 6-indicator dashboard (Step 2) is what actually turns
 * these into a Pivot Points arrow/vote and an ATR-sized Target/SL suggestion.
 */
@Composable
private fun DailyLevelsCard(state: DailyLevelsUiState) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pivot Points & ATR (NIFTY 50, previous day)", style = MaterialTheme.typography.titleSmall)
            when (state) {
                is DailyLevelsUiState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fetching historical candles...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is DailyLevelsUiState.Failed -> {
                    Text("Could not load: ${state.message}", style = MaterialTheme.typography.bodySmall)
                }
                is DailyLevelsUiState.Ready -> {
                    val p = state.pivots
                    Text(
                        "Pivot: %.2f   R1: %.2f   S1: %.2f".format(p.pivot, p.r1, p.s1),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "R2: %.2f   S2: %.2f".format(p.r2, p.s2),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (state.atr14 != null) "ATR(14): %.2f pts".format(state.atr14)
                        else "ATR(14): not enough daily candles yet",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
