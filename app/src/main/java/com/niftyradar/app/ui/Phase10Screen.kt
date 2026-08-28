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
 */
@Composable
fun Phase10Screen(viewModel: Phase10ViewModel, onBack: () -> Unit) {
    val lockedDates by viewModel.lockedDates.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val items by viewModel.items.collectAsState()
    val ticksByInstrument by viewModel.ticksByInstrument.collectAsState()

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
            for (item in items) {
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
