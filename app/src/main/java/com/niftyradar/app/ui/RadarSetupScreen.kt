package com.niftyradar.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niftyradar.app.model.RadarSession

/**
 * How many upcoming expiries to offer as one-tap choices. Weekly + a couple of monthlies is
 * all a day-trader ever reaches for; the rest stay reachable by typing.
 */
private const val MAX_EXPIRY_CHOICES = 6

/**
 * PHASE 2/3 SCREEN ONLY: build (or re-load) today's locked radar. No live
 * ticks, no charts here — this screen exists purely to prove the option
 * chain fetch + ATM/strike selection + 22-contract lock works before Phase 4
 * (WebSocket) gets built on top of it.
 */
@Composable
fun RadarSetupScreen(
    viewModel: RadarSetupViewModel,
    onBackToAuth: () -> Unit,
    onContinueToPhase4: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val expiries by viewModel.expiries.collectAsState()
    // Starts empty on purpose — it is filled from the expiry list Upstox actually returns
    // (see the LaunchedEffect below), never from a date hard-coded into the app. The old
    // hard-coded "2026-09-01" default had already gone stale by the time anyone noticed.
    var expiryDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.loadExistingSessionIfAny()
        viewModel.loadAvailableExpiries()
    }

    // Preselect the nearest listed expiry as soon as the real list arrives, unless the user
    // has already picked or typed something.
    LaunchedEffect(expiries) {
        val state = expiries
        if (state is ExpiriesUiState.Ready && expiryDate.isBlank()) {
            expiryDate = state.expiries.first()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBackToAuth) { Text("← Back") }
        }

        Text("Phase 2/3 — Build Today's Radar", style = MaterialTheme.typography.titleMedium)
        Text(
            "Fetches NIFTY 50 spot + the option chain for the expiry below, then locks " +
                "5 strikes below ATM + ATM + 5 above (22 CE/PE contracts) for the rest of " +
                "today's session. Once locked, this never silently rebuilds itself.",
            style = MaterialTheme.typography.bodyMedium
        )

        // Both of these stay usable even when today's radar is already locked. The text field
        // used to be disabled once locked, which was a mistake: "Force rebuild" below exists
        // precisely to rebuild with DIFFERENT parameters (next week's expiry, say), and you
        // cannot do that if you are locked out of choosing the expiry. Nothing is put at risk
        // by leaving them on — the lock is enforced where it actually matters, in
        // RadarSetupViewModel.buildTodaysRadar(), which refuses to rebuild without an
        // explicit force.
        ExpiryPicker(
            state = expiries,
            selected = expiryDate,
            onSelect = { expiryDate = it }
        )

        OutlinedTextField(
            value = expiryDate,
            onValueChange = { expiryDate = it },
            label = { Text("Expiry date (yyyy-MM-dd)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.buildTodaysRadar(expiryDate) },
                enabled = expiryDate.isNotBlank() &&
                    uiState !is RadarSetupUiState.LoadingSpot &&
                    uiState !is RadarSetupUiState.LoadingContracts
            ) {
                Text(if (viewModel.hasLockedSessionToday()) "Load Today's Radar" else "Lock Today's Radar")
            }
        }

        if (viewModel.hasLockedSessionToday()) {
            Text(
                "A radar is already locked for today, so \"Load\" above just reopens it and " +
                    "ignores the expiry chosen above. To switch to a different expiry — next " +
                    "week's, say — pick it above and rebuild below. Rebuilding discards the " +
                    "original lock and the day's recorded history restarts from the new " +
                    "strikes, so it is not something to do mid-session.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(
                onClick = { viewModel.buildTodaysRadar(expiryDate, force = true) },
                enabled = expiryDate.isNotBlank()
            ) {
                Text(
                    if (expiryDate.isBlank()) "Rebuild today's radar (pick an expiry first)"
                    else "Rebuild today's radar for $expiryDate"
                )
            }
        }

        HorizontalDivider()

        RadarStatusView(uiState)

        if (uiState is RadarSetupUiState.Locked) {
            Button(onClick = onContinueToPhase4, modifier = Modifier.fillMaxWidth()) {
                Text("Continue to Phase 4 — Live Market Data Feed →")
            }
        }
    }
}

/**
 * One-tap buttons for the expiries Upstox actually lists, nearest first and preselected.
 * The text field underneath stays editable on purpose: it is both the fallback when this
 * fetch fails and the way to reach an expiry beyond the first [MAX_EXPIRY_CHOICES].
 */
@Composable
private fun ExpiryPicker(
    state: ExpiriesUiState,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Expiry (from Upstox)", style = MaterialTheme.typography.titleSmall)
        when (state) {
            is ExpiriesUiState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Fetching listed expiries...", style = MaterialTheme.typography.bodySmall)
                }
            }
            is ExpiriesUiState.Failed -> {
                Text(
                    "Could not load expiries: ${state.message} — type one below instead.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is ExpiriesUiState.Ready -> {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (expiry in state.expiries.take(MAX_EXPIRY_CHOICES)) {
                        if (expiry == selected) {
                            Button(onClick = { onSelect(expiry) }) {
                                Text(expiry, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            OutlinedButton(onClick = { onSelect(expiry) }) {
                                Text(expiry, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarStatusView(uiState: RadarSetupUiState) {
    when (uiState) {
        is RadarSetupUiState.Idle -> {
            Text("No radar built yet today.", style = MaterialTheme.typography.bodyMedium)
        }

        is RadarSetupUiState.LoadingSpot -> {
            LoadingRow("Fetching NIFTY 50 spot (GET /v3/market-quote/ltp) ...")
        }

        is RadarSetupUiState.LoadingContracts -> {
            LoadingRow("Fetching option chain (GET /v2/option/contract) ...")
        }

        is RadarSetupUiState.Failed -> {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("❌ FAILED", style = MaterialTheme.typography.titleMedium)
                    Text(uiState.message)
                }
            }
        }

        is RadarSetupUiState.Locked -> {
            RadarLockedCard(uiState.session, uiState.reused)
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
private fun RadarLockedCard(session: RadarSession, reused: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (reused) "🔒 RADAR ALREADY LOCKED (loaded, not rebuilt)" else "🔒 RADAR LOCKED",
                    style = MaterialTheme.typography.titleMedium
                )
                Text("Session date: ${session.sessionDate}")
                Text("Expiry: ${session.expiry}")
                Text("Spot at lock: ${session.spotAtLock}")
                Text("ATM strike: ${session.atmStrike}")
                Text("Radar range: ${session.strikes.minOrNull()} – ${session.strikes.maxOrNull()}")
                Text("Strikes locked: ${session.strikes.size}, contracts resolved: ${session.contracts.size} / ${session.strikes.size * 2}")
            }
        }

        if (session.warnings.isNotEmpty()) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚠️ Warnings", style = MaterialTheme.typography.titleSmall)
                    session.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Text("Locked contracts:", style = MaterialTheme.typography.titleSmall)
        Column {
            for (strike in session.strikes) {
                val ce = session.contracts[RadarSession.contractKey(strike, "CE")]
                val pe = session.contracts[RadarSession.contractKey(strike, "PE")]
                val marker = if (strike == session.atmStrike) " (ATM)" else ""
                Text(
                    "$strike$marker  —  CE: ${ce?.instrumentKey ?: "MISSING"}   PE: ${pe?.instrumentKey ?: "MISSING"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
