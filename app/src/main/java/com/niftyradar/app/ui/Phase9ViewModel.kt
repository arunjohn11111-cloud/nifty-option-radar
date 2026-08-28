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
