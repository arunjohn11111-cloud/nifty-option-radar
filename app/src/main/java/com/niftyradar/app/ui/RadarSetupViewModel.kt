package com.niftyradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyradar.app.domain.RadarStrikeSelector
import com.niftyradar.app.model.LockedContract
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
 * Phases 2+3: fetch NIFTY 50 spot, fetch the option chain for a (configurable)
 * expiry, pick 5-below/ATM/5-above from the strikes Upstox actually returned,
 * resolve CE+PE instrument keys for each, and LOCK that as today's
 * [RadarSession] — spec section 3: once built for a trading day, it is never
 * silently rebuilt, even if this screen is reopened later that same day.
 *
 * No WebSocket, no charts, no live ticks here yet — that starts Phase 4.
 */
sealed class RadarSetupUiState {
    data object Idle : RadarSetupUiState()
    data object LoadingSpot : RadarSetupUiState()
    data object LoadingContracts : RadarSetupUiState()
    data class Locked(val session: RadarSession, val reused: Boolean) : RadarSetupUiState()
    data class Failed(val message: String) : RadarSetupUiState()
}

class RadarSetupViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = SecureTokenStore(application)
    private val sessionStore = RadarSessionStore(application)
    private val apiClient = UpstoxApiClient()

    private val _uiState = MutableStateFlow<RadarSetupUiState>(RadarSetupUiState.Idle)
    val uiState: StateFlow<RadarSetupUiState> = _uiState.asStateFlow()

    /** IST trading-day key, e.g. "2026-08-26". Upstox's market hours are IST regardless of device timezone. */
    private fun todaySessionDate(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(System.currentTimeMillis())
    }

    /**
     * Loads today's already-locked radar if one exists, WITHOUT calling any
     * API — this is what should run every time this screen opens, before
     * ever considering [buildTodaysRadar].
     */
    fun loadExistingSessionIfAny() {
        val existing = sessionStore.loadForDate(todaySessionDate())
        if (existing != null) {
            _uiState.value = RadarSetupUiState.Locked(existing, reused = true)
        }
    }

    fun hasLockedSessionToday(): Boolean = sessionStore.loadForDate(todaySessionDate()) != null

    /**
     * Builds and locks today's radar. Refuses to do anything if a session for
     * today is already locked, unless [force] is explicitly true (manual
     * "rebuild" escape hatch for testing only — never called automatically).
     */
    fun buildTodaysRadar(expiryDate: String, force: Boolean = false) {
        val today = todaySessionDate()
        val existing = sessionStore.loadForDate(today)
        if (existing != null && !force) {
            _uiState.value = RadarSetupUiState.Locked(existing, reused = true)
            return
        }

        val token = tokenStore.getAccessToken()
        if (token.isNullOrBlank()) {
            _uiState.value = RadarSetupUiState.Failed("No verified Upstox token found. Go back to Phase 1 first.")
            return
        }

        viewModelScope.launch {
            _uiState.value = RadarSetupUiState.LoadingSpot
            val spotResult = apiClient.getNiftySpotLtp(token)
            val spot = when (spotResult) {
                is UpstoxApiClient.SpotResult.Failure -> {
                    _uiState.value = RadarSetupUiState.Failed("Spot price: ${spotResult.message}")
                    return@launch
                }
                is UpstoxApiClient.SpotResult.Success -> spotResult.lastPrice
            }

            _uiState.value = RadarSetupUiState.LoadingContracts
            val contractsResult = apiClient.getOptionContracts(token, expiryDate)
            val contracts = when (contractsResult) {
                is UpstoxApiClient.ContractsResult.Failure -> {
                    _uiState.value = RadarSetupUiState.Failed("Option contracts: ${contractsResult.message}")
                    return@launch
                }
                is UpstoxApiClient.ContractsResult.Success -> contractsResult.contracts
            }

            val availableStrikes = contracts.map { it.strikePrice }.distinct()
            val selection = try {
                RadarStrikeSelector.select(spot, availableStrikes)
            } catch (e: IllegalArgumentException) {
                _uiState.value = RadarSetupUiState.Failed(e.message ?: "Could not select strikes.")
                return@launch
            }

            val byStrikeAndType = contracts.associateBy { "${it.strikePrice}_${it.instrumentType}" }
            val lockedContracts = mutableMapOf<String, LockedContract>()
            val warnings = selection.warnings.toMutableList()

            for (strike in selection.strikes) {
                for (type in listOf("CE", "PE")) {
                    val key = RadarSession.contractKey(strike, type)
                    val match = byStrikeAndType["${strike}_$type"]
                    if (match == null) {
                        warnings += "No $type contract found for strike $strike at expiry $expiryDate."
                        continue
                    }
                    lockedContracts[key] = LockedContract(
                        instrumentKey = match.instrumentKey,
                        tradingSymbol = match.tradingSymbol,
                        lotSize = match.lotSize
                    )
                }
            }

            val session = RadarSession(
                sessionDate = today,
                expiry = expiryDate,
                spotAtLock = spot,
                atmStrike = selection.atmStrike,
                strikes = selection.strikes,
                contracts = lockedContracts,
                warnings = warnings
            )

            sessionStore.save(session)
            _uiState.value = RadarSetupUiState.Locked(session, reused = false)
        }
    }
}
