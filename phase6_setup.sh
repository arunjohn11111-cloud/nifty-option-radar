#!/data/data/com.termux/files/usr/bin/bash
# Phase 6 (PROJECT_SPEC.md section 20, step 7): one working live option
# chart. Reads today's stored ticks for the ATM CE contract back out of
# Room (Phase 5) and draws a simple line chart with Compose Canvas. New
# files: LiveTickChart.kt, Phase6ViewModel.kt, Phase6Screen.kt. Updated:
# Phase4Screen.kt (adds "Continue to Phase 6" button), MainActivity.kt
# (wires the new screen in). No Gradle changes needed — Canvas is already
# part of the Compose foundation dependency.
set -e

if [ ! -f "settings.gradle.kts" ]; then
    echo "ERROR: settings.gradle.kts not found in the current directory."
    echo "cd into your NiftyOptionRadar repo clone first (e.g. ~/nifty-build/NiftyOptionRadar), then re-run this script."
    exit 1
fi

UI_DIR="app/src/main/java/com/niftyradar/app/ui"
if [ ! -d "$UI_DIR" ]; then
    echo "ERROR: $UI_DIR not found — is this really the NiftyOptionRadar repo?"
    exit 1
fi

# ---------------------------------------------------------------------------
# New: the chart itself
# ---------------------------------------------------------------------------
cat > "$UI_DIR/LiveTickChart.kt" << 'LIVETICKCHART_EOF'
package com.niftyradar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.niftyradar.app.storage.LiveTickEntity

/**
 * Phase 6: the simplest possible live line chart — LTP over the sequence of
 * ticks stored today for one instrument (read back from Room via
 * [com.niftyradar.app.storage.LiveTickStore.ticksFor]). No axes, no zoom/pan,
 * no time-based x-spacing (ticks are just spaced evenly by index) — that's
 * all deliberately deferred. This exists purely to prove "stored ticks ->
 * a line that moves" before Phase 7 repeats the same idea for all 22
 * contracts.
 */
@Composable
fun LiveTickChart(ticks: List<LiveTickEntity>, modifier: Modifier = Modifier) {
    if (ticks.size < 2) {
        Box(modifier = modifier.height(160.dp), contentAlignment = Alignment.Center) {
            Text("Not enough ticks yet to draw a chart (need at least 2).")
        }
        return
    }

    val minLtp = ticks.minOf { it.ltp }
    val maxLtp = ticks.maxOf { it.ltp }
    val range = (maxLtp - minLtp).takeIf { it > 0.0 } ?: 1.0

    Column(modifier = modifier) {
        Text("High: %.2f".format(maxLtp), style = MaterialTheme.typography.bodySmall)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        ) {
            val stepX = size.width / (ticks.size - 1)
            val path = Path()
            ticks.forEachIndexed { index, tick ->
                val x = index * stepX
                val normalized = (tick.ltp - minLtp) / range
                // Canvas y=0 is the TOP, so a higher LTP must map to a SMALLER y.
                val y = (size.height * (1.0 - normalized)).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = Color(0xFF6750A4),
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
        }
        Text("Low: %.2f".format(minLtp), style = MaterialTheme.typography.bodySmall)
    }
}
LIVETICKCHART_EOF
echo "Wrote LiveTickChart.kt"

# ---------------------------------------------------------------------------
# New: Phase6ViewModel.kt
# ---------------------------------------------------------------------------
cat > "$UI_DIR/Phase6ViewModel.kt" << 'PHASE6VIEWMODEL_EOF'
package com.niftyradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.storage.LiveTickEntity
import com.niftyradar.app.storage.LiveTickStore
import com.niftyradar.app.storage.RadarSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Phase 6 (PROJECT_SPEC.md section 20, step 7): "one working live option
 * chart" — reads today's stored ticks for the ATM call contract back out of
 * Room (Phase 5's storage) and hands them to [LiveTickChart]. Deliberately
 * just ONE contract for now; Phase 7 repeats this same read-and-draw for all
 * 22 locked contracts.
 */
sealed class Phase6UiState {
    data object NoRadarLocked : Phase6UiState()
    data class Ready(val atmStrike: Double, val instrumentKey: String) : Phase6UiState()
}

class Phase6ViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore = RadarSessionStore(application)
    private val liveTickStore = LiveTickStore(application)

    private val _uiState = MutableStateFlow<Phase6UiState>(Phase6UiState.NoRadarLocked)
    val uiState: StateFlow<Phase6UiState> = _uiState.asStateFlow()

    private val _ticks = MutableStateFlow<List<LiveTickEntity>>(emptyList())
    val ticks: StateFlow<List<LiveTickEntity>> = _ticks.asStateFlow()

    /** IST trading-day key — same convention as RadarSetupViewModel/Phase4ViewModel. */
    private fun todaySessionDate(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(System.currentTimeMillis())
    }

    /** Call once when this screen opens: is there a locked radar with an ATM CE to chart? */
    fun load() {
        val session = sessionStore.loadForDate(todaySessionDate())
        if (session == null) {
            _uiState.value = Phase6UiState.NoRadarLocked
            return
        }

        val atmCeKey = session.contracts[RadarSession.contractKey(session.atmStrike, "CE")]?.instrumentKey
        if (atmCeKey == null) {
            _uiState.value = Phase6UiState.NoRadarLocked
            return
        }

        _uiState.value = Phase6UiState.Ready(session.atmStrike, atmCeKey)
        refreshChart(atmCeKey)
    }

    /** Re-read from Room — call this any time to pick up ticks stored since the last read. */
    fun refreshChart() {
        val state = _uiState.value
        if (state is Phase6UiState.Ready) refreshChart(state.instrumentKey)
    }

    private fun refreshChart(instrumentKey: String) {
        viewModelScope.launch {
            _ticks.value = liveTickStore.ticksFor(todaySessionDate(), instrumentKey)
        }
    }
}
PHASE6VIEWMODEL_EOF
echo "Wrote Phase6ViewModel.kt"

# ---------------------------------------------------------------------------
# New: Phase6Screen.kt
# ---------------------------------------------------------------------------
cat > "$UI_DIR/Phase6Screen.kt" << 'PHASE6SCREEN_EOF'
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
PHASE6SCREEN_EOF
echo "Wrote Phase6Screen.kt"

# ---------------------------------------------------------------------------
# Updated: Phase4Screen.kt (adds "Continue to Phase 6" button + param)
# ---------------------------------------------------------------------------
cat > "$UI_DIR/Phase4Screen.kt" << 'PHASE4SCREEN_EOF'
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
PHASE4SCREEN_EOF
echo "Wrote Phase4Screen.kt"

# ---------------------------------------------------------------------------
# Updated: MainActivity.kt (wires Phase6Screen in)
# ---------------------------------------------------------------------------
cat > app/src/main/java/com/niftyradar/app/MainActivity.kt << 'MAINACTIVITY_EOF'
package com.niftyradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.niftyradar.app.ui.AuthUiState
import com.niftyradar.app.ui.AuthViewModel
import com.niftyradar.app.ui.Phase4Screen
import com.niftyradar.app.ui.Phase4ViewModel
import com.niftyradar.app.ui.Phase6Screen
import com.niftyradar.app.ui.Phase6ViewModel
import com.niftyradar.app.ui.RadarSetupScreen
import com.niftyradar.app.ui.RadarSetupViewModel

/**
 * Phases 1-6. Screen switching is a plain in-memory enum, not
 * Navigation-Compose: there are only a handful of screens right now and
 * adding a nav-graph dependency for that would be premature.
 */
private enum class Screen { Auth, RadarSetup, Phase4, Phase6 }

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val radarSetupViewModel: RadarSetupViewModel by viewModels()
    private val phase4ViewModel: Phase4ViewModel by viewModels()
    private val phase6ViewModel: Phase6ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.Auth) }

                    when (screen) {
                        Screen.Auth -> Phase1Screen(
                            viewModel = authViewModel,
                            onContinueToRadarSetup = { screen = Screen.RadarSetup }
                        )
                        Screen.RadarSetup -> RadarSetupScreen(
                            viewModel = radarSetupViewModel,
                            onBackToAuth = { screen = Screen.Auth },
                            onContinueToPhase4 = { screen = Screen.Phase4 }
                        )
                        Screen.Phase4 -> Phase4Screen(
                            viewModel = phase4ViewModel,
                            onBack = { screen = Screen.RadarSetup },
                            onContinueToPhase6 = { screen = Screen.Phase6 }
                        )
                        Screen.Phase6 -> Phase6Screen(
                            viewModel = phase6ViewModel,
                            onBack = { screen = Screen.Phase4 }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Phase1Screen(viewModel: AuthViewModel, onContinueToRadarSetup: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var tokenInput by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Nifty Option Radar", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Phase 1 — Upstox connection check",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            "Paste your Upstox access token below. It is encrypted on this device " +
                "(Android Keystore) and is only ever sent to api.upstox.com over HTTPS — " +
                "never anywhere else, never logged, never hard-coded.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (viewModel.hasStoredToken()) {
            Text(
                "A token is already saved on this device: ${viewModel.storedTokenRedacted()}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Upstox access token") },
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = tokenVisible, onCheckedChange = { tokenVisible = it })
            Text("Show token")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.saveAndVerify(tokenInput) },
                enabled = uiState !is AuthUiState.Verifying
            ) {
                Text("Save & Verify")
            }

            OutlinedButton(
                onClick = { viewModel.verifyStoredToken() },
                enabled = viewModel.hasStoredToken() && uiState !is AuthUiState.Verifying
            ) {
                Text("Re-verify saved token")
            }

            TextButton(onClick = {
                viewModel.clearToken()
                tokenInput = ""
            }) {
                Text("Clear")
            }
        }

        HorizontalDivider()

        StatusCard(uiState)

        if (uiState is AuthUiState.Connected) {
            Button(onClick = onContinueToRadarSetup, modifier = Modifier.fillMaxWidth()) {
                Text("Continue to Phase 2/3 — Build Today's Radar →")
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: AuthUiState) {
    when (uiState) {
        is AuthUiState.NotVerified -> {
            Text("Status: not verified yet.", style = MaterialTheme.typography.bodyMedium)
        }

        is AuthUiState.Verifying -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Calling Upstox GET /v2/user/profile ...")
            }
        }

        is AuthUiState.Connected -> {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✅ CONNECTED", style = MaterialTheme.typography.titleMedium)
                    Text("User: ${uiState.userName} (${uiState.userId})")
                    Text("Broker: ${uiState.broker}")
                    Text("Exchanges: ${uiState.exchanges.joinToString(", ")}")
                    Text(
                        "Token verified — continue below to build today's radar.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        is AuthUiState.Failed -> {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("❌ FAILED", style = MaterialTheme.typography.titleMedium)
                    Text(uiState.message)
                }
            }
        }
    }
}
MAINACTIVITY_EOF
echo "Wrote MainActivity.kt"

git add \
    "$UI_DIR/LiveTickChart.kt" \
    "$UI_DIR/Phase6ViewModel.kt" \
    "$UI_DIR/Phase6Screen.kt" \
    "$UI_DIR/Phase4Screen.kt" \
    app/src/main/java/com/niftyradar/app/MainActivity.kt

git commit -m "Phase 6: one working live option chart (ATM CE) reading ticks back from Room"
git push

echo "Done. If push asked for a username/password, use your GitHub username and the fine-grained PAT (Contents: Read and write) as the password."
