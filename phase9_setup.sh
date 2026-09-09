#!/data/data/com.termux/files/usr/bin/bash
# Phase 9 (PROJECT_SPEC.md section 20, step 10): the final combined radar
# view — NIFTY 50 spot (Phase 8) plus all 22 locked option contracts (Phase
# 7), all 23 charts on one screen. No new chart logic: merges the two lists
# Phase 7 and Phase 8 already built and reuses LiveTickChart.kt unchanged.
# New files: Phase9ViewModel.kt, Phase9Screen.kt. Updated: Phase8Screen.kt
# (adds "Continue to Phase 9" button), MainActivity.kt (wires the new screen
# in). No Gradle changes needed.
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
# New: Phase9ViewModel.kt
# ---------------------------------------------------------------------------
cat > "$UI_DIR/Phase9ViewModel.kt" << 'PHASE9VIEWMODEL_EOF'
package com.niftyradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.network.UpstoxApiClient
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
 * Phase 9 (PROJECT_SPEC.md section 20, step 10): the final combined radar
 * view — NIFTY 50 spot (Phase 8) plus all 22 locked option contracts (Phase
 * 7), all 23 on one screen. No new chart logic: this just merges the two
 * lists Phase 7 and Phase 8 already built separately and reuses
 * [LiveTickChart] unchanged for every one of them.
 */
data class RadarChartItem(val label: String, val instrumentKey: String)

sealed class Phase9UiState {
    data object NoRadarLocked : Phase9UiState()
    data class Ready(val items: List<RadarChartItem>) : Phase9UiState()
}

class Phase9ViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore = RadarSessionStore(application)
    private val liveTickStore = LiveTickStore(application)

    private val _uiState = MutableStateFlow<Phase9UiState>(Phase9UiState.NoRadarLocked)
    val uiState: StateFlow<Phase9UiState> = _uiState.asStateFlow()

    private val _ticksByInstrument = MutableStateFlow<Map<String, List<LiveTickEntity>>>(emptyMap())
    val ticksByInstrument: StateFlow<Map<String, List<LiveTickEntity>>> = _ticksByInstrument.asStateFlow()

    /** IST trading-day key — same convention as the other ViewModels. */
    private fun todaySessionDate(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(System.currentTimeMillis())
    }

    /** Call once when this screen opens: build the list of all 23 charts (spot + 22 contracts). */
    fun load() {
        val session = sessionStore.loadForDate(todaySessionDate())
        if (session == null) {
            _uiState.value = Phase9UiState.NoRadarLocked
            return
        }

        val items = mutableListOf<RadarChartItem>()
        items.add(RadarChartItem("NIFTY 50 SPOT", UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY))
        for (strike in session.strikes) {
            val marker = if (strike == session.atmStrike) " (ATM)" else ""
            val ceKey = session.contracts[RadarSession.contractKey(strike, "CE")]?.instrumentKey
            val peKey = session.contracts[RadarSession.contractKey(strike, "PE")]?.instrumentKey
            if (ceKey != null) {
                items.add(RadarChartItem("$strike$marker CE", ceKey))
            }
            if (peKey != null) {
                items.add(RadarChartItem("$strike$marker PE", peKey))
            }
        }

        _uiState.value = Phase9UiState.Ready(items)
        refreshAll(items)
    }

    /** Re-read every chart from Room — call this any time to pick up new ticks. */
    fun refreshAll() {
        val state = _uiState.value
        if (state is Phase9UiState.Ready) refreshAll(state.items)
    }

    private fun refreshAll(items: List<RadarChartItem>) {
        viewModelScope.launch {
            val date = todaySessionDate()
            val result = mutableMapOf<String, List<LiveTickEntity>>()
            for (item in items) {
                result[item.instrumentKey] = liveTickStore.ticksFor(date, item.instrumentKey)
            }
            _ticksByInstrument.value = result
        }
    }
}
PHASE9VIEWMODEL_EOF
echo "Wrote Phase9ViewModel.kt"

# ---------------------------------------------------------------------------
# New: Phase9Screen.kt
# ---------------------------------------------------------------------------
cat > "$UI_DIR/Phase9Screen.kt" << 'PHASE9SCREEN_EOF'
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
PHASE9SCREEN_EOF
echo "Wrote Phase9Screen.kt"

# ---------------------------------------------------------------------------
# Updated: Phase8Screen.kt (adds "Continue to Phase 9" button + param)
# ---------------------------------------------------------------------------
cat > "$UI_DIR/Phase8Screen.kt" << 'PHASE8SCREEN_EOF'
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
fun Phase8Screen(viewModel: Phase8ViewModel, onBack: () -> Unit, onContinueToPhase9: () -> Unit) {
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

        HorizontalDivider()
        Button(onClick = onContinueToPhase9, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to Phase 9 — Full Radar View →")
        }
    }
}
PHASE8SCREEN_EOF
echo "Wrote Phase8Screen.kt"

# ---------------------------------------------------------------------------
# Updated: MainActivity.kt (wires Phase9Screen in)
# ---------------------------------------------------------------------------
cat > "app/src/main/java/com/niftyradar/app/MainActivity.kt" << 'MAINACTIVITY_EOF'
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
import com.niftyradar.app.ui.Phase7Screen
import com.niftyradar.app.ui.Phase7ViewModel
import com.niftyradar.app.ui.Phase8Screen
import com.niftyradar.app.ui.Phase8ViewModel
import com.niftyradar.app.ui.Phase9Screen
import com.niftyradar.app.ui.Phase9ViewModel
import com.niftyradar.app.ui.RadarSetupScreen
import com.niftyradar.app.ui.RadarSetupViewModel

/**
 * Phases 1-9. Screen switching is a plain in-memory enum, not
 * Navigation-Compose: there are only a handful of screens right now and
 * adding a nav-graph dependency for that would be premature.
 */
private enum class Screen { Auth, RadarSetup, Phase4, Phase6, Phase7, Phase8, Phase9 }

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val radarSetupViewModel: RadarSetupViewModel by viewModels()
    private val phase4ViewModel: Phase4ViewModel by viewModels()
    private val phase6ViewModel: Phase6ViewModel by viewModels()
    private val phase7ViewModel: Phase7ViewModel by viewModels()
    private val phase8ViewModel: Phase8ViewModel by viewModels()
    private val phase9ViewModel: Phase9ViewModel by viewModels()

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
                            onBack = { screen = Screen.Phase4 },
                            onContinueToPhase7 = { screen = Screen.Phase7 }
                        )
                        Screen.Phase7 -> Phase7Screen(
                            viewModel = phase7ViewModel,
                            onBack = { screen = Screen.Phase6 },
                            onContinueToPhase8 = { screen = Screen.Phase8 }
                        )
                        Screen.Phase8 -> Phase8Screen(
                            viewModel = phase8ViewModel,
                            onBack = { screen = Screen.Phase7 },
                            onContinueToPhase9 = { screen = Screen.Phase9 }
                        )
                        Screen.Phase9 -> Phase9Screen(
                            viewModel = phase9ViewModel,
                            onBack = { screen = Screen.Phase8 }
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
    "$UI_DIR/Phase9ViewModel.kt" \
    "$UI_DIR/Phase9Screen.kt" \
    "$UI_DIR/Phase8Screen.kt" \
    app/src/main/java/com/niftyradar/app/MainActivity.kt

git commit -m "Phase 9: full radar view (NIFTY spot + all 22 contracts, 23 charts on one screen)"
git push

echo "Done. If push asked for a username/password, use your GitHub username (arunjohn11111-cloud) and the fine-grained PAT (Contents: Read and write) as the password."
