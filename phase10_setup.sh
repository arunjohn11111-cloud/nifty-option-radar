#!/data/data/com.termux/files/usr/bin/bash
# Phase 10 (PROJECT_SPEC.md section 20, steps 10-11 — the last two of the 11
# spec steps): daily session locking (every locked day is now kept, not just
# the latest one) + historical review (pick a past day and see its 23
# charts, same as Phase 9 does for today). Storage change: RadarSessionStore
# now keys sessions per-date instead of one shared slot, with a safe
# one-time migration of whatever was already locked under the old key so
# nothing already on your phone is lost. New files: Phase10ViewModel.kt,
# Phase10Screen.kt. Updated: RadarSessionStore.kt (per-date storage),
# Phase9Screen.kt (adds "Continue to Phase 10" button), MainActivity.kt
# (wires the new screen in). No Gradle changes needed.
set -e

if [ ! -f "settings.gradle.kts" ]; then
    echo "ERROR: settings.gradle.kts not found in the current directory."
    echo "cd into your NiftyOptionRadar repo clone first (e.g. ~/nifty-build/NiftyOptionRadar), then re-run this script."
    exit 1
fi

UI_DIR="app/src/main/java/com/niftyradar/app/ui"
STORAGE_DIR="app/src/main/java/com/niftyradar/app/storage"
if [ ! -d "$UI_DIR" ]; then
    echo "ERROR: $UI_DIR not found — is this really the NiftyOptionRadar repo?"
    exit 1
fi

# ---------------------------------------------------------------------------
# New: Phase10ViewModel.kt
# ---------------------------------------------------------------------------
cat > "$UI_DIR/Phase10ViewModel.kt" << 'PHASE10VIEWMODEL_EOF'
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

/**
 * Phase 10 (PROJECT_SPEC.md section 20, steps 10-11: daily session locking +
 * historical review): [RadarSessionStore] now keeps every locked day instead
 * of only the latest one. This screen lists those dates and, for whichever
 * one is picked, reloads that day's 23 charts exactly the way Phase 9 shows
 * today's — same [RadarChartItem]/[LiveTickChart], just for ticks stored
 * under a past date instead of today's.
 */
class Phase10ViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore = RadarSessionStore(application)
    private val liveTickStore = LiveTickStore(application)

    private val _lockedDates = MutableStateFlow<List<String>>(emptyList())
    val lockedDates: StateFlow<List<String>> = _lockedDates.asStateFlow()

    private val _selectedDate = MutableStateFlow<String?>(null)
    val selectedDate: StateFlow<String?> = _selectedDate.asStateFlow()

    private val _items = MutableStateFlow<List<RadarChartItem>>(emptyList())
    val items: StateFlow<List<RadarChartItem>> = _items.asStateFlow()

    private val _ticksByInstrument = MutableStateFlow<Map<String, List<LiveTickEntity>>>(emptyMap())
    val ticksByInstrument: StateFlow<Map<String, List<LiveTickEntity>>> = _ticksByInstrument.asStateFlow()

    /** Call once when this screen opens: which dates have a locked session at all? */
    fun loadDates() {
        _lockedDates.value = sessionStore.listLockedDates()
    }

    /** Reload that date's locked contracts + stored ticks, same shape as Phase 9. */
    fun selectDate(date: String) {
        _selectedDate.value = date

        val session = sessionStore.loadForDate(date)
        if (session == null) {
            _items.value = emptyList()
            _ticksByInstrument.value = emptyMap()
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
        _items.value = items

        viewModelScope.launch {
            val result = mutableMapOf<String, List<LiveTickEntity>>()
            for (item in items) {
                result[item.instrumentKey] = liveTickStore.ticksFor(date, item.instrumentKey)
            }
            _ticksByInstrument.value = result
        }
    }

    /** Back to the date list. */
    fun clearSelection() {
        _selectedDate.value = null
        _items.value = emptyList()
        _ticksByInstrument.value = emptyMap()
    }
}
PHASE10VIEWMODEL_EOF
echo "Wrote Phase10ViewModel.kt"

# ---------------------------------------------------------------------------
# New: Phase10Screen.kt
# ---------------------------------------------------------------------------
cat > "$UI_DIR/Phase10Screen.kt" << 'PHASE10SCREEN_EOF'
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
PHASE10SCREEN_EOF
echo "Wrote Phase10Screen.kt"

# ---------------------------------------------------------------------------
# Updated: RadarSessionStore.kt (per-date storage + legacy migration)
# ---------------------------------------------------------------------------
cat > "$STORAGE_DIR/RadarSessionStore.kt" << 'RADARSESSIONSTORE_EOF'
package com.niftyradar.app.storage

import android.content.Context
import com.niftyradar.app.model.RadarSession

/**
 * Persists LOCKED radars (11 strikes / 22 instrument keys) — one per trading
 * day — in plain (unencrypted) SharedPreferences: this is public market
 * structure data (strikes, instrument keys, trading symbols), not a secret,
 * unlike the access token in SecureTokenStore.
 *
 * Phase 10: before this, only the single most-recently-locked day was ever
 * kept (each new lock silently discarded the previous day's). Now every
 * locked day gets its own key, plus an index of known dates, so Phase 10's
 * history screen can list and reload any past day. [loadForDate] behaves
 * exactly as before for the "today" callers added in Phases 2-9 — nothing
 * about looking up today's session changes; only that it's no longer the
 * only day this can ever remember. A one-time migration in [loadForDate]
 * picks up whatever was stored under the old single-slot key before this
 * change, so an already-locked "today" isn't silently lost by the upgrade.
 *
 * The whole point (spec section 3 + 16) is still: once a session is locked
 * for a given date, it must be loaded back as-is, never silently rebuilt,
 * even across app restarts on the same day.
 */
class RadarSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    fun save(session: RadarSession) {
        prefs.edit()
            .putString(sessionKey(session.sessionDate), session.toJson())
            .apply()
        addKnownDate(session.sessionDate)
    }

    /** Returns the locked session for [date], or null if none was ever locked for that date. */
    fun loadForDate(date: String): RadarSession? {
        val raw = prefs.getString(sessionKey(date), null)
        if (raw != null) {
            return try {
                RadarSession.fromJson(raw)
            } catch (e: Exception) {
                null
            }
        }

        // Legacy fallback: before Phase 10, only one session was ever stored,
        // under a single shared key. Migrate it forward into the new
        // per-date storage if it matches this date, so an already-locked
        // "today" from before this update isn't silently lost.
        val legacyRaw = prefs.getString(KEY_LEGACY_SESSION_JSON, null) ?: return null
        return try {
            val legacySession = RadarSession.fromJson(legacyRaw)
            if (legacySession.sessionDate == date) {
                save(legacySession)
                legacySession
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Every date that has a locked session, most recent first — for Phase 10's history screen. */
    fun listLockedDates(): List<String> {
        val dates = prefs.getStringSet(KEY_KNOWN_DATES, emptySet()) ?: emptySet()
        return dates.sortedDescending()
    }

    private fun addKnownDate(date: String) {
        val dates = (prefs.getStringSet(KEY_KNOWN_DATES, emptySet()) ?: emptySet()).toMutableSet()
        if (dates.add(date)) {
            prefs.edit().putStringSet(KEY_KNOWN_DATES, dates).apply()
        }
    }

    private fun sessionKey(date: String) = "$KEY_SESSION_JSON_PREFIX$date"

    companion object {
        private const val PREFS_FILE_NAME = "niftyradar_session_prefs"
        private const val KEY_SESSION_JSON_PREFIX = "locked_radar_session_"
        private const val KEY_KNOWN_DATES = "locked_radar_session_dates"

        /** Old single-slot key from before Phase 10 — read-only now, for migration. */
        private const val KEY_LEGACY_SESSION_JSON = "locked_radar_session"
    }
}
RADARSESSIONSTORE_EOF
echo "Wrote RadarSessionStore.kt"

# ---------------------------------------------------------------------------
# Updated: Phase9Screen.kt (adds "Continue to Phase 10" button + param)
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

        HorizontalDivider()
        Button(onClick = onContinueToPhase10, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to Phase 10 — Session History →")
        }
    }
}
PHASE9SCREEN_EOF
echo "Wrote Phase9Screen.kt"

# ---------------------------------------------------------------------------
# Updated: MainActivity.kt (wires Phase10Screen in)
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
import com.niftyradar.app.ui.Phase10Screen
import com.niftyradar.app.ui.Phase10ViewModel
import com.niftyradar.app.ui.RadarSetupScreen
import com.niftyradar.app.ui.RadarSetupViewModel

/**
 * Phases 1-10 (the full 11-step spec). Screen switching is a plain in-memory
 * enum, not Navigation-Compose: there are only a handful of screens right
 * now and adding a nav-graph dependency for that would be premature.
 */
private enum class Screen { Auth, RadarSetup, Phase4, Phase6, Phase7, Phase8, Phase9, Phase10 }

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val radarSetupViewModel: RadarSetupViewModel by viewModels()
    private val phase4ViewModel: Phase4ViewModel by viewModels()
    private val phase6ViewModel: Phase6ViewModel by viewModels()
    private val phase7ViewModel: Phase7ViewModel by viewModels()
    private val phase8ViewModel: Phase8ViewModel by viewModels()
    private val phase9ViewModel: Phase9ViewModel by viewModels()
    private val phase10ViewModel: Phase10ViewModel by viewModels()

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
                            onBack = { screen = Screen.Phase8 },
                            onContinueToPhase10 = { screen = Screen.Phase10 }
                        )
                        Screen.Phase10 -> Phase10Screen(
                            viewModel = phase10ViewModel,
                            onBack = { screen = Screen.Phase9 }
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
    "$UI_DIR/Phase10ViewModel.kt" \
    "$UI_DIR/Phase10Screen.kt" \
    "$STORAGE_DIR/RadarSessionStore.kt" \
    "$UI_DIR/Phase9Screen.kt" \
    app/src/main/java/com/niftyradar/app/MainActivity.kt

git commit -m "Phase 10: daily session locking (per-date storage) + session history screen"
git push

echo "Done. If push asked for a username/password, use your GitHub username (arunjohn11111-cloud) and the fine-grained PAT (Contents: Read and write) as the password."
