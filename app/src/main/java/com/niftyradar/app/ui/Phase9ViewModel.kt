package com.niftyradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyradar.app.domain.AverageTrueRange
import com.niftyradar.app.domain.DashboardResult
import com.niftyradar.app.domain.IndicatorEngine
import com.niftyradar.app.domain.PanicAlert
import com.niftyradar.app.domain.PanicAlertResult
import com.niftyradar.app.domain.PivotLevels
import com.niftyradar.app.domain.PivotPoints
import com.niftyradar.app.model.Candle
import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.network.UpstoxApiClient
import com.niftyradar.app.notification.DashboardNotifier
import com.niftyradar.app.notification.PanicAlertNotifier
import com.niftyradar.app.security.SecureTokenStore
import com.niftyradar.app.storage.LiveTickEntity
import com.niftyradar.app.storage.LiveTickStore
import com.niftyradar.app.storage.RadarSessionStore
import kotlinx.coroutines.delay
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

    /**
     * [items] is the flat list the wide/TV grid still consumes. [rungs] is the same contracts
     * grouped the way an option chain is actually read — one entry per strike holding both its
     * legs — which is what the phone's single-scroll ladder needs and what a flat list cannot
     * express: in a flat list a strike's call and put are merely adjacent, and stop being
     * adjacent as soon as anything re-flows them into columns.
     */
    data class Ready(
        val items: List<RadarChartItem>,
        val rungs: List<LadderRung>
    ) : Phase9UiState()
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
    private val dashboardNotifier = DashboardNotifier(application)
    private val panicAlertNotifier = PanicAlertNotifier(application)

    private val _uiState = MutableStateFlow<Phase9UiState>(Phase9UiState.NoRadarLocked)
    val uiState: StateFlow<Phase9UiState> = _uiState.asStateFlow()

    private val _ticksByInstrument = MutableStateFlow<Map<String, List<LiveTickEntity>>>(emptyMap())
    val ticksByInstrument: StateFlow<Map<String, List<LiveTickEntity>>> = _ticksByInstrument.asStateFlow()

    private val _dailyLevels = MutableStateFlow<DailyLevelsUiState>(DailyLevelsUiState.Loading)
    val dailyLevels: StateFlow<DailyLevelsUiState> = _dailyLevels.asStateFlow()

    // Step 2: the 6-indicator dashboard (5 of 6 so far — see IndicatorEngine's doc comment).
    // Needs both the locked session (ATM strike/contracts) and the pivot levels above, so it's
    // recomputed every 5s refresh alongside ticksByInstrument rather than fetched separately.
    private var currentSession: RadarSession? = null
    private val _dashboard = MutableStateFlow<DashboardResult?>(null)
    val dashboard: StateFlow<DashboardResult?> = _dashboard.asStateFlow()

    // Trend (9/21 EMA)'s own 15-min candle series (historical + today's intraday, merged) —
    // refreshed on its own 60s loop, not the 5s tick loop: 15-min candles don't change often
    // enough to justify re-fetching from Upstox every 5 seconds.
    private var trendCandles: List<Candle> = emptyList()
    private var trendCandleLoopStarted = false

    // Live-refresh loop state — see startLiveRefreshLoop.
    private var liveRefreshLoopStarted = false
    private var lastSeenTickMillis: Long? = null
    private var pendingForce = true

    // Market-wide panic alert (NIFTY spot alone, independent of the dashboard above) — see
    // PanicAlert's doc comment for why this is separate from the 6-indicator dashboard.
    private val _panicAlert = MutableStateFlow<PanicAlertResult?>(null)
    val panicAlert: StateFlow<PanicAlertResult?> = _panicAlert.asStateFlow()

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

        val rungs = session.strikes.sorted().map { strike ->
            LadderRung(
                strike = strike,
                ceKey = session.contracts[RadarSession.contractKey(strike, "CE")]?.instrumentKey,
                peKey = session.contracts[RadarSession.contractKey(strike, "PE")]?.instrumentKey
            )
        }

        currentSession = session
        _uiState.value = Phase9UiState.Ready(items, rungs)
        refreshAll(items)
        loadDailyLevels()
        startTrendCandleRefreshLoop()
        startLiveRefreshLoop()
    }

    /**
     * The live loop, moved out of the screen and onto a two-second cadence.
     *
     * Two seconds rather than five because the cost was measured rather than feared. A whole
     * recorded trading day came to 39,613 rows across all 23 instruments — the feed snapshots
     * roughly once per instrument every fifteen seconds, not once a second — so a full re-read
     * is tens of thousands of small rows, not the half million a per-second feed would have
     * meant. The earlier refusal to speed this up was the right call on the evidence available
     * then; it is the wrong call on the evidence available now.
     *
     * It is also the CHEAP kind of fast, which matters more than the interval. At that snapshot
     * rate most two-second passes have nothing new in them at all, so each pass first asks for
     * one number — the newest tick's timestamp — and does nothing further when it has not
     * moved. Out of market hours, or in a lull, the loop costs a single scalar query and
     * allocates nothing.
     *
     * [pendingForce] exists because ticks are not the only input. The pivot levels and the
     * 15-minute trend candles arrive on their own schedules, and the dashboard cannot be
     * computed until they do — so without this flag a market that had gone quiet at the exact
     * moment the pivots landed would leave the dashboard blank until the next tick, which on a
     * slow strike could be a minute away.
     */
    private fun startLiveRefreshLoop() {
        if (liveRefreshLoopStarted) return
        liveRefreshLoopStarted = true
        viewModelScope.launch {
            while (true) {
                delay(LIVE_REFRESH_INTERVAL_MS)
                val state = _uiState.value
                if (state !is Phase9UiState.Ready) continue
                val latest = liveTickStore.latestTickMillis(todaySessionDate())
                if (!pendingForce && latest == lastSeenTickMillis) continue
                lastSeenTickMillis = latest
                pendingForce = false
                refreshAll(state.items)
            }
        }
    }

    /**
     * Starts (once — guarded by [trendCandleLoopStarted], since [load] can be called again if
     * this screen is re-entered later in the same app session) a loop that fetches
     * [loadTrendCandles] immediately, then every 60 seconds afterward.
     */
    private fun startTrendCandleRefreshLoop() {
        if (trendCandleLoopStarted) return
        trendCandleLoopStarted = true
        viewModelScope.launch {
            while (true) {
                loadTrendCandles()
                delay(60_000L)
            }
        }
    }

    /**
     * NIFTY 50 spot's 15-min candles for Trend (9/21 EMA): a historical window (10 calendar
     * days, comfortably covering several trading days of warm-up history even around a
     * holiday) plus today's still-forming candles from the separate intraday endpoint (see
     * [UpstoxApiClient.getIntradayCandles]'s doc comment for why these two calls are needed
     * instead of one). The two series are merged and de-duplicated by timestamp, keeping the
     * intraday copy of any candle that happens to appear in both (the freshest one) — Upstox's
     * historical endpoint is not expected to include today's data while the market is open,
     * but this stays correct if it ever does, e.g. after today's own candle has closed.
     */
    private suspend fun loadTrendCandles() {
        val token = tokenStore.getAccessToken() ?: return

        val historicalResult = apiClient.getHistoricalCandles(
            accessToken = token,
            instrumentKey = UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY,
            unit = "minutes",
            interval = "15",
            toDate = dateDaysBeforeToday(1),
            fromDate = dateDaysBeforeToday(10)
        )
        val intradayResult = apiClient.getIntradayCandles(
            accessToken = token,
            instrumentKey = UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY,
            unit = "minutes",
            interval = "15"
        )

        val historicalCandles =
            (historicalResult as? UpstoxApiClient.CandlesResult.Success)?.candles ?: emptyList()
        val intradayCandles =
            (intradayResult as? UpstoxApiClient.CandlesResult.Success)?.candles ?: emptyList()

        trendCandles = (historicalCandles + intradayCandles)
            .associateBy { it.timestampIso }
            .values
            .sortedBy { it.timestampIso }
        // New candles change the Trend vote even when no tick arrived, so the next loop pass
        // must not skip itself. See startLiveRefreshLoop.
        pendingForce = true
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
                    // The dashboard could not be computed at all before this resolved, so the
                    // next loop pass must run even if the market has gone quiet meanwhile.
                    pendingForce = true
                }
            }
        }
    }

    /**
     * Re-read every chart from Room unconditionally, skipping the change probe.
     *
     * Kept for the few callers that genuinely need a forced pass — nothing on the screen calls
     * it any more, because a refresh button was exactly what the user asked not to have.
     */
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
            updateDashboard(result)
            updatePanicAlert(result)
        }
    }

    /**
     * Runs independently of [updateDashboard] — unlike the dashboard, this only needs spot's
     * own ticks (no session/pivots prerequisite), so it can start alerting from the very first
     * few minutes of the day rather than waiting on anything else to be ready.
     */
    private fun updatePanicAlert(ticksByInstrument: Map<String, List<LiveTickEntity>>) {
        val spotTicks = ticksByInstrument[UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY] ?: emptyList()
        val result = PanicAlert.evaluate(spotTicks) ?: return
        _panicAlert.value = result
        panicAlertNotifier.onPanicEvaluated(result)
    }

    /**
     * Recomputes the 5-of-6 dashboard from whatever's currently available. Silently leaves
     * [dashboard] at its previous value (usually null, early in the day) until both the
     * locked session and the pivot levels are ready — the next 5s auto-refresh tick retries
     * on its own, no separate wiring needed once [loadDailyLevels] resolves.
     */
    private fun updateDashboard(ticksByInstrument: Map<String, List<LiveTickEntity>>) {
        val session = currentSession ?: return
        val pivots = (_dailyLevels.value as? DailyLevelsUiState.Ready)?.pivots ?: return
        val spotTicks = ticksByInstrument[UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY] ?: emptyList()
        val result = IndicatorEngine.evaluate(session, ticksByInstrument, spotTicks, pivots, trendCandles)
        _dashboard.value = result
        dashboardNotifier.onDashboardUpdated(result)
    }

    private companion object {
        /**
         * How often the live loop checks for new ticks. See [startLiveRefreshLoop] for why two
         * seconds is affordable here and why the loop is cheap on passes that find nothing.
         */
        const val LIVE_REFRESH_INTERVAL_MS = 2_000L
    }
}
