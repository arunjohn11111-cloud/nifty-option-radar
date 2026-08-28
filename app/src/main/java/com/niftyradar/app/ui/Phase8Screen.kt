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
 * PHASE 8 SCREEN: PROJECT_SPEC.md section 20 step 9 — same idea as Phase
 * 6/7, applied to the NIFTY 50 spot index chart instead of an option
 * contract. Reuses [LiveTickChart] unchanged.
 */
@Composable
fun Phase8Screen(viewModel: Phase8ViewModel, onBack: () -> Unit) {
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

        Text("Phase 8 — NIFTY 50 Spot Chart", style = MaterialTheme.typography.titleMedium)
        Text(
            "Same idea as Phase 6/7, applied to the NIFTY 50 spot index instead of an " +
                "option contract. Reads today's stored spot ticks back out of Room and " +
                "draws them as a simple line.",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.load() }) {
                Text("Refresh chart (${ticks.size} tick(s) loaded)")
            }
        }
        LiveTickChart(ticks = ticks, modifier = Modifier.fillMaxWidth())
    }
}
