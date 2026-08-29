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
