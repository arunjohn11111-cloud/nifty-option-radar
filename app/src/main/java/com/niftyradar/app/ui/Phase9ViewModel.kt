package com.niftyradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyradar.app.domain.AverageTrueRange
import com.niftyradar.app.domain.PivotLevels
import com.niftyradar.app.domain.PivotPoints
import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.network.UpstoxApiClient
import com.niftyradar.app.security.SecureTokenStore
import com.niftyradar.app.storage.LiveTickEntity
import com.niftyradar.app.storage.LiveTickStore
import com.niftyradar.app.storage.RadarSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
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

/**
 * Step 1b of the 6-indicator build: NIFTY 50 spot's previous-day pivot levels
 * (Pivot Points indicator) and daily-candle ATR(14) (used only for
 * Target/SL sizing, not as a directional vote) — see PROJECT_SPEC.md's
 * 6-indicator design. Both come from [UpstoxApiClient.getHistoricalCandles]
 * fetched once when this screen loads, purely to prove real values are
 * flowing before the actual 6-indicator engine (Step 2) is built on top.
 */
sealed class DailyLevelsUiState {
    data object Loading : DailyLevelsUiState()
    data class Ready(val pivots: PivotLevels, val atr14: Double?) : DailyLevelsUiState()
    data class Failed(val message: String) : DailyLevelsUiState()
}

class Phase9ViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore = RadarSessionStore(application)
    private val liveTickStore = LiveTickStore(application)
    private val tokenStore = SecureTokenStore(application)
    private val apiClient = UpstoxApiClient()

    private val _uiState = MutableStateFlow<Phase9UiState>(Phase9UiState.NoRadarLocked)
    val uiState: StateFlow<Phase9UiState> = _uiState.asStateFlow()

    private val _ticksByInstrument = MutableStateFlow<Map<String, List<LiveTickEntity>>>(emptyMap())
    val ticksByInstrument: StateFlow<Map<String, List<LiveTickEntity>>> = _ticksByInstrument.asStateFlow()

    private val _dailyLevels = MutableStateFlow<DailyLevelsUiState>(DailyLevelsUiState.Loading)
    val dailyLevels: StateFlow<DailyLevelsUiState> = _dailyLevels.asStateFlow()

    /** IST trading-day key — same convention as the other ViewModels. */
    private fun todaySessionDate(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(System.currentTimeMillis())
    }

    /** Same "yyyy-MM-dd" IST convention as [todaySessionDate], [daysBack] calendar days earlier. */
    private fun dateDaysBeforeToday(daysBack: Int): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        calendar.add(Calendar.DAY_OF_YEAR, -daysBack)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(calendar.time)
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
        loadDailyLevels()
    }

    /**
     * Fetches NIFTY 50 spot's daily candles once, in a single 60-calendar-day window
     * (comfortably covering 14+ trading days even around holidays) — used both for
     * [AverageTrueRange.wilder] (which wants that whole series) and [PivotPoints.classic]
     * (which only needs the single most recent candle in it, i.e. the most recently
     * COMPLETED trading day's H/L/C). [toDate] is pinned to YESTERDAY, not today, on purpose:
     * Upstox's historical endpoint excludes an in-progress "today" candle during market
     * hours, but may include it once today's candle has actually closed — pinning to
     * yesterday guarantees "previous day" never accidentally becomes "today" depending on
     * what time of day this happens to run.
     */
    private fun loadDailyLevels() {
        val token = tokenStore.getAccessToken()
        if (token.isNullOrBlank()) {
            _dailyLevels.value = DailyLevelsUiState.Failed("No verified Upstox token found.")
            return
        }

        _dailyLevels.value = DailyLevelsUiState.Loading
        viewModelScope.launch {
            val result = apiClient.getHistoricalCandles(
                accessToken = token,
                instrumentKey = UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY,
                unit = "days",
                interval = "1",
                toDate = dateDaysBeforeToday(1),
                fromDate = dateDaysBeforeToday(60)
            )

            when (result) {
                is UpstoxApiClient.CandlesResult.Failure -> {
                    _dailyLevels.value = DailyLevelsUiState.Failed(result.message)
                }
                is UpstoxApiClient.CandlesResult.Success -> {
                    val candles = result.candles
                    val previousDay = candles.lastOrNull()
                    if (previousDay == null) {
                        _dailyLevels.value = DailyLevelsUiState.Failed(
                            "Upstox returned zero completed daily candles for NIFTY 50."
                        )
                        return@launch
                    }
                    val pivots = PivotPoints.classic(
                        previousHigh = previousDay.high,
                        previousLow = previousDay.low,
                        previousClose = previousDay.close
                    )
                    val atr14 = AverageTrueRange.wilder(candles, period = 14)
                    _dailyLevels.value = DailyLevelsUiState.Ready(pivots, atr14)
                }
            }
        }
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
