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
 * Phase 7 (PROJECT_SPEC.md section 20, step 8): the exact same "read ticks
 * back from Room, draw a line" idea as Phase 6 ([Phase6Screen]/[LiveTickChart]),
 * just repeated for all 22 locked contracts instead of only the ATM CE one.
 * Deliberately reuses [LiveTickChart] as-is — no new chart code, only more of it.
 */
data class ContractChartInfo(val label: String, val instrumentKey: String)

sealed class Phase7UiState {
    data object NoRadarLocked : Phase7UiState()
    data class Ready(val contracts: List<ContractChartInfo>) : Phase7UiState()
}

class Phase7ViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore = RadarSessionStore(application)
    private val liveTickStore = LiveTickStore(application)

    private val _uiState = MutableStateFlow<Phase7UiState>(Phase7UiState.NoRadarLocked)
    val uiState: StateFlow<Phase7UiState> = _uiState.asStateFlow()

    private val _ticksByInstrument = MutableStateFlow<Map<String, List<LiveTickEntity>>>(emptyMap())
    val ticksByInstrument: StateFlow<Map<String, List<LiveTickEntity>>> = _ticksByInstrument.asStateFlow()

    /** IST trading-day key — same convention as the other ViewModels. */
    private fun todaySessionDate(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(System.currentTimeMillis())
    }

    /** Call once when this screen opens: build the list of all 22 CE/PE contracts to chart. */
    fun load() {
        val session = sessionStore.loadForDate(todaySessionDate())
        if (session == null) {
            _uiState.value = Phase7UiState.NoRadarLocked
            return
        }

        val contracts = mutableListOf<ContractChartInfo>()
        for (strike in session.strikes) {
            val marker = if (strike == session.atmStrike) " (ATM)" else ""
            val ceKey = session.contracts[RadarSession.contractKey(strike, "CE")]?.instrumentKey
            val peKey = session.contracts[RadarSession.contractKey(strike, "PE")]?.instrumentKey
            if (ceKey != null) {
                contracts.add(ContractChartInfo("$strike$marker CE", ceKey))
            }
            if (peKey != null) {
                contracts.add(ContractChartInfo("$strike$marker PE", peKey))
            }
        }

        _uiState.value = Phase7UiState.Ready(contracts)
        refreshAll(contracts)
    }

    /** Re-read every contract from Room — call this any time to pick up new ticks. */
    fun refreshAll() {
        val state = _uiState.value
        if (state is Phase7UiState.Ready) refreshAll(state.contracts)
    }

    private fun refreshAll(contracts: List<ContractChartInfo>) {
        viewModelScope.launch {
            val date = todaySessionDate()
            val result = mutableMapOf<String, List<LiveTickEntity>>()
            for (contract in contracts) {
                result[contract.instrumentKey] = liveTickStore.ticksFor(date, contract.instrumentKey)
            }
            _ticksByInstrument.value = result
        }
    }
}
