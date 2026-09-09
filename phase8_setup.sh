#!/data/data/com.termux/files/usr/bin/bash
# Phase 8 (PROJECT_SPEC.md section 20, step 9): the same "read ticks back
# from Room, draw a line" idea as Phase 6/7, applied to the NIFTY 50 spot
# index chart instead of an option contract. Reuses LiveTickChart.kt
# unchanged. New files: Phase8ViewModel.kt, Phase8Screen.kt. Updated:
# Phase7Screen.kt (adds "Continue to Phase 8" button), MainActivity.kt
# (wires the new screen in). No Gradle changes needed.
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
# New: Phase8ViewModel.kt
# ---------------------------------------------------------------------------
cat > "$UI_DIR/Phase8ViewModel.kt" << 'PHASE8VIEWMODEL_EOF'
package com.niftyradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyradar.app.network.UpstoxApiClient
import com.niftyradar.app.storage.LiveTickEntity
import com.niftyradar.app.storage.LiveTickStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Phase 8 (PROJECT_SPEC.md section 20, step 9): the same "read ticks back
 * from Room, draw a line" idea as Phase 6/7, applied to the NIFTY 50 spot
 * index instead of an option contract. No locked-radar check needed here —
 * Phase 4 always subscribes to the spot index alongside whatever contracts
 * are locked, so this screen just shows whatever ticks exist for today
 * (possibly none yet), same as [LiveTickChart]'s own empty-state message.
 */
class Phase8ViewModel(application: Application) : AndroidViewModel(application) {

    private val liveTickStore = LiveTickStore(application)

    private val _ticks = MutableStateFlow<List<LiveTickEntity>>(emptyList())
    val ticks: StateFlow<List<LiveTickEntity>> = _ticks.asStateFlow()

    /** IST trading-day key — same convention as the other ViewModels. */
    private fun todaySessionDate(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(System.currentTimeMillis())
    }

    /** Call when this screen opens, and again any time to pick up new ticks. */
    fun load() {
        viewModelScope.launch {
            _ticks.value = liveTickStore.ticksFor(todaySessionDate(), UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY)
        }
    }
}
PHASE8VIEWMODEL_EOF
echo "Wrote Phase8ViewModel.kt"

# ---------------------------------------------------------------------------
# New: Phase8Screen.kt
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
PHASE8SCREEN_EOF
echo "Wrote Phase8Screen.kt"

# ---------------------------------------------------------------------------
# Updated: Phase7Screen.kt (adds "Continue to Phase 8" button + param)
# ---------------------------------------------------------------------------
cat > "$UI_DIR/Phase7Screen.kt" << 'PHASE7SCREEN_EOF'
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
                for (contract in state.contracts) {
                    HorizontalDivider()
                    Text(contract.label, style = MaterialTheme.typography.titleSmall)
                    LiveTickChart(
                        ticks = ticksByInstrument[contract.instrumentKey] ?: emptyList(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                HorizontalDivider()
                Button(onClick = onContinueToPhase8, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue to Phase 8 — NIFTY Spot Chart →")
                }
            }
        }
    }
}
PHASE7SCREEN_EOF
echo "Wrote Phase7Screen.kt"

# ---------------------------------------------------------------------------
# Updated: MainActivity.kt (wires Phase8Screen in)
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
import com.niftyradar.app.ui.Phase7Screen
import com.niftyradar.app.ui.Phase7ViewModel
import com.niftyradar.app.ui.Phase8Screen
import com.niftyradar.app.ui.Phase8ViewModel
import com.niftyradar.app.ui.RadarSetupScreen
import com.niftyradar.app.ui.RadarSetupViewModel

/**
 * Phases 1-8. Screen switching is a plain in-memory enum, not
 * Navigation-Compose: there are only a handful of screens right now and
 * adding a nav-graph dependency for that would be premature.
 */
private enum class Screen { Auth, RadarSetup, Phase4, Phase6, Phase7, Phase8 }

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val radarSetupViewModel: RadarSetupViewModel by viewModels()
    private val phase4ViewModel: Phase4ViewModel by viewModels()
    private val phase6ViewModel: Phase6ViewModel by viewModels()
    private val phase7ViewModel: Phase7ViewModel by viewModels()
    private val phase8ViewModel: Phase8ViewModel by viewModels()

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
                            onBack = { screen = Screen.Phase7 }
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
    "$UI_DIR/Phase8ViewModel.kt" \
    "$UI_DIR/Phase8Screen.kt" \
    "$UI_DIR/Phase7Screen.kt" \
    app/src/main/java/com/niftyradar/app/MainActivity.kt

git commit -m "Phase 8: NIFTY 50 spot chart (reuses Phase 6's LiveTickChart for the index instead of an option contract)"
git push

echo "Done. If push asked for a username/password, use your GitHub username (arunjohn11111-cloud) and the fine-grained PAT (Contents: Read and write) as the password."
