package com.niftyradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyradar.app.network.UpstoxApiClient
import com.niftyradar.app.security.SecureTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 1 state machine: paste token -> Verify -> Connected/Failed.
 * Nothing here talks to a WebSocket yet; that starts in Phase 4.
 */
sealed class AuthUiState {
    data object NotVerified : AuthUiState()
    data object Verifying : AuthUiState()
    data class Connected(
        val userName: String,
        val userId: String,
        val broker: String,
        val exchanges: List<String>
    ) : AuthUiState()
    data class Failed(val message: String) : AuthUiState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = SecureTokenStore(application)
    private val apiClient = UpstoxApiClient()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.NotVerified)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /** True once a token is saved locally, independent of whether it has been verified this launch. */
    fun hasStoredToken(): Boolean = tokenStore.hasAccessToken()

    fun storedTokenRedacted(): String = SecureTokenStore.redacted(tokenStore.getAccessToken())

    fun saveAndVerify(rawToken: String) {
        val token = rawToken.trim()
        if (token.isEmpty()) {
            _uiState.value = AuthUiState.Failed("Paste your Upstox access token first.")
            return
        }

        tokenStore.saveAccessToken(token)
        verifyStoredToken()
    }

    fun verifyStoredToken() {
        val token = tokenStore.getAccessToken()
        if (token.isNullOrBlank()) {
            _uiState.value = AuthUiState.Failed("No token saved yet.")
            return
        }

        _uiState.value = AuthUiState.Verifying
        viewModelScope.launch {
            when (val result = apiClient.verifyToken(token)) {
                is UpstoxApiClient.ProfileResult.Success -> {
                    _uiState.value = AuthUiState.Connected(
                        userName = result.userName,
                        userId = result.userId,
                        broker = result.broker,
                        exchanges = result.exchanges
                    )
                }
                is UpstoxApiClient.ProfileResult.Failure -> {
                    _uiState.value = AuthUiState.Failed(result.message)
                }
            }
        }
    }

    fun clearToken() {
        tokenStore.clearAccessToken()
        _uiState.value = AuthUiState.NotVerified
    }
}
