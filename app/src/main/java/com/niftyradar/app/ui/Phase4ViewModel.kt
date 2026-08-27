package com.niftyradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyradar.app.feed.FeedConnectionState
import com.niftyradar.app.feed.LiveQuote
import com.niftyradar.app.feed.MarketFeedClient
import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.network.UpstoxApiClient
import com.niftyradar.app.security.SecureTokenStore
import com.niftyradar.app.storage.RadarSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Phase 4 only: authorize + connect the Market Data Feed V3 WebSocket for
 * NIFTY 50 spot + the 22 instrument keys locked in Phase 2/3, and show live
 * ticks arriving. No local storage, no charts yet — that's Phase 6 onward
 * (PROJECT_SPEC.md section 20, steps 4-5).
 */
sealed class Phase4UiState {
    data object NoRadarLocked : Phase4UiState()
    data object Ready : Phase4UiState()
    data object Authorizing : Phase4UiState()
    data class ConnectionError(val message: String) : Phase4UiState()
}

class Phase4ViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = SecureTokenStore(application)
    private val sessionStore = RadarSessionStore(application)
    private val apiClient = UpstoxApiClient()
    private val feedClient = MarketFeedClient()

    private val _uiState = MutableStateFlow<Phase4UiState>(Phase4UiState.NoRadarLocked)
    val uiState: StateFlow<Phase4UiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<FeedConnectionState> = feedClient.connectionState
    val quotes: StateFlow<Map<String, LiveQuote>> = feedClient.quotes

    private var lockedSession: RadarSession? = null

    /** IST trading-day key — same convention as RadarSetupViewModel.todaySessionDate(). */
    private fun todaySessionDate(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(System.currentTimeMillis())
    }

    /** Call once when this screen opens: is there a locked radar to connect a feed to at all? */
    fun loadLockedSession() {
        val session = sessionStore.loadForDate(todaySessionDate())
        lockedSession = session
        _uiState.value = if (session == null) Phase4UiState.NoRadarLocked else Phase4UiState.Ready
    }

    fun lockedSessionOrNull(): RadarSession? = lockedSession

    fun connect() {
        val session = lockedSession ?: run {
            _uiState.value = Phase4UiState.NoRadarLocked
            return
        }
        val token = tokenStore.getAccessToken()
        if (token.isNullOrBlank()) {
            _uiState.value =
                Phase4UiState.ConnectionError("No verified Upstox token found. Go back to Phase 1 first.")
            return
        }

        _uiState.value = Phase4UiState.Authorizing
        viewModelScope.launch {
            when (val result = apiClient.getMarketDataFeedAuthorizeUrl(token)) {
                is UpstoxApiClient.FeedAuthorizeResult.Failure -> {
                    _uiState.value = Phase4UiState.ConnectionError(result.message)
                }
                is UpstoxApiClient.FeedAuthorizeResult.Success -> {
                    _uiState.value = Phase4UiState.Ready
                    // NIFTY 50 spot + exactly the 22 locked contracts — never a different set,
                    // per this app's "radar is locked for the day" rule (spec section 3).
                    val instrumentKeys = listOf(UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY) +
                        session.contracts.values.map { it.instrumentKey }
                    feedClient.connect(result.webSocketUrl, token, instrumentKeys, mode = "full")
                }
            }
        }
    }

    fun disconnect() {
        feedClient.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        feedClient.disconnect()
    }
}
