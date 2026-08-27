package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niftyradar.app.feed.FeedConnectionState
import com.niftyradar.app.feed.LiveQuote
import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.network.UpstoxApiClient

/**
 * PHASE 4/5 SCREEN: authorize + connect the Market Data Feed V3 WebSocket and
 * show live LTP/OI/volume ticking in for NIFTY 50 spot + the 22 locked
 * contracts (Phase 4), while every tick is also persisted to Room in the
 * background (Phase 5) — see the "Check stored ticks" button. No charts yet
 * — that's Phase 6 onward. This screen exists purely to prove the feed
 * connects and the ticks really land on disk, before anything is built on top.
 */
@Composable
fun Phase4Screen(viewModel: Phase4ViewModel, onBack: () -> Unit, onContinueToPhase6: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val quotes by viewModel.quotes.collectAsState()
    val storedTickSummary by viewModel.storedTickSummary.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadLockedSession()
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

        Text("Phase 4 — Live Market Data Feed (V3)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connects the Market Data Feed V3 WebSocket and subscribes to NIFTY 50 spot + " +
                "the 22 locked contracts in 'full' mode (LTP, OI, volume), and saves every " +
                "tick to an on-device database. No charts yet — this screen only proves live " +
                "ticks arrive and land on disk.",
            style = MaterialTheme.typography.bodyMedium
        )

        val session = viewModel.lockedSessionOrNull()

        if (uiState is Phase4UiState.NoRadarLocked) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No radar locked for today yet.", style = MaterialTheme.typography.titleMedium)
                    Text("Go back to Phase 2/3 and lock today's radar first.")
                }
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.connect() },
                enabled = uiState !is Phase4UiState.Authorizing &&
                    connectionState !is FeedConnectionState.Connected &&
                    connectionState !is FeedConnectionState.Connecting
            ) {
                Text("Connect Live Feed")
            }
            OutlinedButton(onClick = { viewModel.disconnect() }) {
                Text("Disconnect")
            }
        }

        ConnectionStatusCard(uiState, connectionState)

        HorizontalDivider()
        Text("Phase 5 — local storage (Room)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Every tick above is also written to an on-device database as it arrives, " +
                "independent of this screen. Tap below any time — even right after opening " +
                "the app, before connecting — to prove it's really on disk, not just in memory.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.refreshStoredTickSummary() }) {
                Text("Check stored ticks (today)")
            }
        }
        if (storedTickSummary != null) {
            Text(storedTickSummary!!, style = MaterialTheme.typography.bodyMedium)
        }

        if (session != null) {
            Button(onClick = onContinueToPhase6, modifier = Modifier.fillMaxWidth()) {
                Text("Continue to Phase 6 — Option Chart →")
            }
            HorizontalDivider()
            QuoteRow(label = "NIFTY 50 SPOT", quote = quotes[UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY])
            HorizontalDivider()
            Text("Locked contracts:", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (strike in session.strikes) {
                    val ceKey = session.contracts[RadarSession.contractKey(strike, "CE")]?.instrumentKey
                    val peKey = session.contracts[RadarSession.contractKey(strike, "PE")]?.instrumentKey
                    val marker = if (strike == session.atmStrike) " (ATM)" else ""
                    StrikeRow(
                        label = "$strike$marker",
                        ceQuote = ceKey?.let { quotes[it] },
                        peQuote = peKey?.let { quotes[it] }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(uiState: Phase4UiState, connectionState: FeedConnectionState) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                uiState is Phase4UiState.ConnectionError -> {
                    Text("❌ FAILED", style = MaterialTheme.typography.titleMedium)
                    Text(uiState.message, style = MaterialTheme.typography.bodySmall)
                }
                uiState is Phase4UiState.Authorizing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Calling Upstox feed authorize endpoint ...")
                    }
                }
                connectionState is FeedConnectionState.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Opening WebSocket ...")
                    }
                }
                connectionState is FeedConnectionState.Connected -> {
                    Text("✅ CONNECTED", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Market status: ${connectionState.marketStatusSummary}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                connectionState is FeedConnectionState.Failed -> {
                    Text("❌ FAILED", style = MaterialTheme.typography.titleMedium)
                    Text(connectionState.message, style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("Not connected yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun QuoteRow(label: String, quote: LiveQuote?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            if (quote != null) "LTP ${quote.ltp}" else "waiting for tick…",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun StrikeRow(label: String, ceQuote: LiveQuote?, peQuote: LiveQuote?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text("CE: ${quoteSummary(ceQuote)}", style = MaterialTheme.typography.bodySmall)
        Text("PE: ${quoteSummary(peQuote)}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun quoteSummary(quote: LiveQuote?): String {
    if (quote == null) return "…"
    val oi = quote.openInterest?.toLong()?.toString() ?: "-"
    val vol = quote.volumeTradedToday?.toString() ?: "-"
    return "LTP ${quote.ltp}  OI $oi  Vol $vol"
}
