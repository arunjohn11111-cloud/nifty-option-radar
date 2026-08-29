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
