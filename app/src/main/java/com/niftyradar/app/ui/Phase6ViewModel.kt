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
 * Phase 6 (PROJECT_SPEC.md section 20, step 7): "one working live option
 * chart" — reads today's stored ticks for the ATM call contract back out of
 * Room (Phase 5's storage) and hands them to [LiveTickChart]. Deliberately
 * just ONE contract for now; Phase 7 repeats this same read-and-draw for all
 * 22 locked contracts.
 */
sealed class Phase6UiState {
    data object NoRadarLocked : Phase6UiState()
    data class Ready(val atmStrike: Double, val instrumentKey: String) : Phase6UiState()
}

class Phase6ViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore = RadarSessionStore(application)
    private val liveTickStore = LiveTickStore(application)

    private val _uiState = MutableStateFlow<Phase6UiState>(Phase6UiState.NoRadarLocked)
    val uiState: StateFlow<Phase6UiState> = _uiState.asStateFlow()

    private val _ticks = MutableStateFlow<List<LiveTickEntity>>(emptyList())
    val ticks: StateFlow<List<LiveTickEntity>> = _ticks.asStateFlow()

    /** IST trading-day key — same convention as RadarSetupViewModel/Phase4ViewModel. */
    private fun todaySessionDate(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(System.currentTimeMillis())
    }

    /** Call once when this screen opens: is there a locked radar with an ATM CE to chart? */
    fun load() {
        val session = sessionStore.loadForDate(todaySessionDate())
        if (session == null) {
            _uiState.value = Phase6UiState.NoRadarLocked
            return
        }

        val atmCeKey = session.contracts[RadarSession.contractKey(session.atmStrike, "CE")]?.instrumentKey
        if (atmCeKey == null) {
            _uiState.value = Phase6UiState.NoRadarLocked
            return
        }

        _uiState.value = Phase6UiState.Ready(session.atmStrike, atmCeKey)
        refreshChart(atmCeKey)
    }

    /** Re-read from Room — call this any time to pick up ticks stored since the last read. */
    fun refreshChart() {
        val state = _uiState.value
        if (state is Phase6UiState.Ready) refreshChart(state.instrumentKey)
    }

    private fun refreshChart(instrumentKey: String) {
        viewModelScope.launch {
            _ticks.value = liveTickStore.ticksFor(todaySessionDate(), instrumentKey)
        }
    }
}
