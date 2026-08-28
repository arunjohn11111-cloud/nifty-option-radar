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
 * PHASE 9 SCREEN: PROJECT_SPEC.md section 20 step 10 — the final combined
 * radar view. NIFTY 50 spot (Phase 8) plus all 22 locked option contracts
 * (Phase 7), all 23 charts on one screen. Reuses [LiveTickChart] unchanged.
 */
@Composable
fun Phase9Screen(viewModel: Phase9ViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val ticksByInstrument by viewModel.ticksByInstrument.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.load()
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
                        Text("Refresh all charts")
                    }
                }
                for (item in state.items) {
                    HorizontalDivider()
                    Text(item.label, style = MaterialTheme.typography.titleSmall)
                    LiveTickChart(
                        ticks = ticksByInstrument[item.instrumentKey] ?: emptyList(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
