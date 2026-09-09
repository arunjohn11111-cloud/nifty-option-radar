#!/data/data/com.termux/files/usr/bin/bash
set -e
cd ~/nifty-build/NiftyOptionRadar

echo "Writing chart-grid files..."

mkdir -p "$(dirname "app/src/main/java/com/niftyradar/app/ui/ChartGrid.kt")"
cat > "app/src/main/java/com/niftyradar/app/ui/ChartGrid.kt" << 'CHARTGRID_EOF'
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

/**
 * PHASE 7 SCREEN: PROJECT_SPEC.md section 20 step 8 — the same idea as
 * Phase 6, just for all 22 locked contracts instead of only the ATM CE one.
 * Reuses [LiveTickChart] unchanged.
 *
 * TV support: charts render via [ChartGrid] instead of one long vertical
 * list — a single column on a phone-width screen (unchanged), several
 * columns side by side on a wide TV screen.
 */
@Composable
fun Phase7Screen(viewModel: Phase7ViewModel, onBack: () -> Unit, onContinueToPhase8: () -> Unit) {
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
                        Text("Refresh all charts")
                    }
                }
                ChartGrid(
                    items = state.contracts,
                    label = { it.label },
                    instrumentKey = { it.instrumentKey },
                    ticksByInstrument = ticksByInstrument
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

/**
 * PHASE 9 SCREEN: PROJECT_SPEC.md section 20 step 10 — the final combined
 * radar view. NIFTY 50 spot (Phase 8) plus all 22 locked option contracts
 * (Phase 7), all 23 charts on one screen. Reuses [LiveTickChart] unchanged.
 *
 * TV support: charts render via [ChartGrid] instead of one long vertical
 * list — a single column on a phone-width screen (unchanged), several
 * columns side by side on a wide TV screen, so the whole radar is visible
 * with much less scrolling.
 */
@Composable
fun Phase9Screen(viewModel: Phase9ViewModel, onBack: () -> Unit, onContinueToPhase10: () -> Unit) {
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
                ChartGrid(
                    items = state.items,
                    label = { it.label },
                    instrumentKey = { it.instrumentKey },
                    ticksByInstrument = ticksByInstrument
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
 * columns side by side on a wide TV screen.
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
            ChartGrid(
                items = items,
                label = { it.label },
                instrumentKey = { it.instrumentKey },
                ticksByInstrument = ticksByInstrument
            )
        }
    }
}
PHASE10SCREEN_EOF
echo "Wrote app/src/main/java/com/niftyradar/app/ui/Phase10Screen.kt"

git add app/src/main/java/com/niftyradar/app/ui/ChartGrid.kt app/src/main/java/com/niftyradar/app/ui/Phase7Screen.kt app/src/main/java/com/niftyradar/app/ui/Phase9Screen.kt app/src/main/java/com/niftyradar/app/ui/Phase10Screen.kt
git commit -m "Responsive chart grid: Phase 7/9/10 show charts in a grid instead of one long list — single column on phone (unchanged), multiple columns side by side on a wide TV screen"
git push
echo "Done. Now go to GitHub Actions and check the build."
