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

/**
 * Radar v2.0 — foundation for the Scenario Library app.
 * The old monitor (radar lock, WebSocket feed, charts, history) was removed;
 * it is preserved in git tag "radar-final".
 */
private enum class Screen { Auth, Home }

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.Auth) }
                    when (screen) {
                        Screen.Auth -> AuthScreen(
                            viewModel = authViewModel,
                            onContinue = { screen = Screen.Home }
                        )
                        Screen.Home -> HomeScreen(onBack = { screen = Screen.Auth })
                    }
                }
            }
        }
    }
}

@Composable
fun AuthScreen(viewModel: AuthViewModel, onContinue: () -> Unit) {
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
        Text("Radar", style = MaterialTheme.typography.headlineSmall)
        Text("Upstox connection", style = MaterialTheme.typography.titleMedium)
        Text(
            "Paste your Upstox access token. It is encrypted on this device " +
                "(Android Keystore) and only sent to api.upstox.com over HTTPS.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (viewModel.hasStoredToken()) {
            Text(
                "Saved token: ${viewModel.storedTokenRedacted()}",
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
            ) { Text("Save & Verify") }

            OutlinedButton(
                onClick = { viewModel.verifyStoredToken() },
                enabled = viewModel.hasStoredToken() && uiState !is AuthUiState.Verifying
            ) { Text("Re-verify") }

            TextButton(onClick = {
                viewModel.clearToken()
                tokenInput = ""
            }) { Text("Clear") }
        }

        HorizontalDivider()
        StatusCard(uiState)

        if (uiState is AuthUiState.Connected) {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continue →")
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: AuthUiState) {
    when (uiState) {
        is AuthUiState.NotVerified ->
            Text("Status: not verified yet.", style = MaterialTheme.typography.bodyMedium)

        is AuthUiState.Verifying ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Checking token with Upstox ...")
            }

        is AuthUiState.Connected ->
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✅ CONNECTED", style = MaterialTheme.typography.titleMedium)
                    Text("User: ${uiState.userName} (${uiState.userId})")
                    Text("Broker: ${uiState.broker}")
                }
            }

        is AuthUiState.Failed ->
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("❌ FAILED", style = MaterialTheme.typography.titleMedium)
                    Text(uiState.message)
                }
            }
    }
}

@Composable
fun HomeScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Radar", style = MaterialTheme.typography.headlineSmall)
        Text("Scenario Library — foundation v2.0", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("✅ Old monitor code removed (saved in git tag radar-final)")
                Text("✅ Upstox token login kept")
                Text("Next: move log — every move, all scales, pre / current / after")
                Text("Then: scenario library, event calendar, global cues, money flow")
            }
        }
        OutlinedButton(onClick = onBack) { Text("← Token screen") }
    }
}
