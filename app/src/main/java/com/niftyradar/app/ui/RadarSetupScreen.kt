package com.niftyradar.app.ui

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
    var expiryDate by remember { mutableStateOf("2026-09-01") }

    LaunchedEffect(Unit) {
        viewModel.loadExistingSessionIfAny()
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

        OutlinedTextField(
            value = expiryDate,
            onValueChange = { expiryDate = it },
            label = { Text("Expiry date (yyyy-MM-dd)") },
            singleLine = true,
            enabled = !viewModel.hasLockedSessionToday(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.buildTodaysRadar(expiryDate) },
                enabled = uiState !is RadarSetupUiState.LoadingSpot && uiState !is RadarSetupUiState.LoadingContracts
            ) {
                Text(if (viewModel.hasLockedSessionToday()) "Load Today's Radar" else "Lock Today's Radar")
            }
        }

        if (viewModel.hasLockedSessionToday()) {
            Text(
                "A radar is already locked for today. Rebuilding is only for testing before " +
                    "you rely on this for a real session, since it discards the original lock.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { viewModel.buildTodaysRadar(expiryDate, force = true) }) {
                Text("Force rebuild today's radar (testing only)")
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
