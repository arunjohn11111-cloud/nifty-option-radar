package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * PHASE 6 SCREEN: PROJECT_SPEC.md section 20 step 7 — "one working live
 * option chart". Shows the ATM call contract's ticks stored today (Phase 5's
 * Room database) as a simple line chart. Only one contract on purpose —
 * Phase 7 expands this same pattern to all 22 locked contracts once this one
 * is proven to work.
 */
@Composable
fun Phase6Screen(viewModel: Phase6ViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val ticks by viewModel.ticks.collectAsState()

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

        Text("Phase 6 — One Live Option Chart", style = MaterialTheme.typography.titleMedium)
        Text(
            "Reads today's stored ticks for the ATM call contract back out of Room (Phase 5) " +
                "and draws them as a simple line. Proves \"stored ticks → chart\" works before " +
                "Phase 7 repeats this for all 22 contracts.",
            style = MaterialTheme.typography.bodyMedium
        )

        when (val state = uiState) {
            is Phase6UiState.NoRadarLocked -> {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No radar locked for today yet.", style = MaterialTheme.typography.titleMedium)
                        Text("Go back, lock today's radar, connect Phase 4's feed, and let a few ticks arrive first.")
                    }
                }
            }
            is Phase6UiState.Ready -> {
                Text("ATM CE — strike ${state.atmStrike}", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.refreshChart() }) {
                        Text("Refresh chart (${ticks.size} tick(s) loaded)")
                    }
                }
                LiveTickChart(ticks = ticks, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
