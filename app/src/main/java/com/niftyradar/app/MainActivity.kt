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
import com.niftyradar.app.ui.RadarSetupScreen
import com.niftyradar.app.ui.RadarSetupViewModel

/**
 * Phases 1-4. Nothing about local storage or charts lives here yet — those
 * are Phase 6 onward, built and tested one at a time. Screen switching is a
 * plain in-memory enum, not Navigation-Compose: there are only a handful of
 * screens right now and adding a nav-graph dependency for that would be
 * premature.
 */
private enum class Screen { Auth, RadarSetup, Phase4 }

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val radarSetupViewModel: RadarSetupViewModel by viewModels()
    private val phase4ViewModel: Phase4ViewModel by viewModels()

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
                            onBack = { screen = Screen.RadarSetup }
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
