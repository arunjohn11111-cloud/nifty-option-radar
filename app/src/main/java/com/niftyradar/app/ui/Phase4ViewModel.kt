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
 * Phase 4: authorize + connect the Market Data Feed V3 WebSocket for NIFTY 50
 * spot + the 22 instrument keys locked in Phase 2/3, and show live ticks
 * arriving. Phase 5: every tick is also persisted to Room (see [liveTickStore]
 * / [refreshStoredTickSummary]) so it survives an app restart. No charts yet
 * — that's Phase 6 onward (PROJECT_SPEC.md section 20).
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
    private val liveTickStore = LiveTickStore(application)

    private val _uiState = MutableStateFlow<Phase4UiState>(Phase4UiState.NoRadarLocked)
    val uiState: StateFlow<Phase4UiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<FeedConnectionState> = feedClient.connectionState
    val quotes: StateFlow<Map<String, LiveQuote>> = feedClient.quotes

    /** Phase 5: null until [refreshStoredTickSummary] is called — see Phase4Screen's "Check stored ticks" button. */
    private val _storedTickSummary = MutableStateFlow<String?>(null)
    val storedTickSummary: StateFlow<String?> = _storedTickSummary.asStateFlow()

    /** Retention readout: how much is on disk across ALL recorded days, and what the trim did. */
    private val _storageSummary = MutableStateFlow<String?>(null)
    val storageSummary: StateFlow<String?> = _storageSummary.asStateFlow()

    private val _compacting = MutableStateFlow(false)
    val compacting: StateFlow<Boolean> = _compacting.asStateFlow()

    /**
     * The measured snapshot rate, and the per-day breakdown behind it. Null until
     * [refreshStoredTickSummary] runs — same pattern as the two summaries above.
     *
     * This is the one reading that has to come from a live market day, and it is worth saying
     * why it is a screen rather than a calculation: the total tick count was read as a rate
     * once, and it is not one, because this app's feed only runs while a screen holds it open.
     * Everything downstream of "how fast do snapshots arrive" — refresh cadence, whether
     * per-second candles hold information, whether a volume-delta flow proxy has samples, how
     * many megabytes a recorded day costs — was resting on that bad divisor.
     */
    private val _snapshotRate = MutableStateFlow<String?>(null)
    val snapshotRate: StateFlow<String?> = _snapshotRate.asStateFlow()

    /**
     * Whether stored tick timestamps agree with the device clock. Null when they do — this
     * reports a problem or says nothing at all.
     */
    private val _clockWarning = MutableStateFlow<String?>(null)
    val clockWarning: StateFlow<String?> = _clockWarning.asStateFlow()

    private var lockedSession: RadarSession? = null

    init {
        // Phase 5: persist every tick as it arrives, for as long as this ViewModel is alive —
        // independent of connect()/disconnect(), so re-subscribing never has to re-wire this.
        viewModelScope.launch {
            feedClient.tickEvents.collect { event ->
                liveTickStore.recordTick(todaySessionDate(), event)
            }
        }

        // Enforce the rolling retention window BEFORE any connect() can start inserting, and
        // only once per process. Nothing used to delete old ticks at all, so this database
        // could only grow; see LiveTickStore.trimToRecentSessions.
        viewModelScope.launch {
            val trim = liveTickStore.trimOnceThisProcess()
            if (trim != null && trim.didAnything) {
                _storageSummary.value =
                    "Retention: removed ${trim.deletedTicks} tick(s) from " +
                        "${trim.deletedDays.size} day(s) older than the most recent " +
                        "${LiveTickStore.RETENTION_SESSIONS}."
            }
        }
    }

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

    /**
     * Phase 5 proof-of-life: read back (from Room, not from the in-memory
     * [quotes] map) how many ticks are actually on disk for today. Works even
     * right after a fresh app launch with no connection made yet — that's the
     * whole point, it proves persistence survived past the process that wrote it.
     */
    fun refreshStoredTickSummary() {
        viewModelScope.launch {
            val date = todaySessionDate()
            val tickCount = liveTickStore.countForSession(date)
            val instrumentCount = liveTickStore.instrumentCountForSession(date)
            _storedTickSummary.value =
                "$tickCount tick(s) stored for today across $instrumentCount instrument(s)."
            _storageSummary.value = describe(liveTickStore.storageSummary())
            _snapshotRate.value = describeRate(
                rate = liveTickStore.snapshotRate(),
                perDay = liveTickStore.ticksPerRecordedDay()
            )
            _clockWarning.value = describeClock(liveTickStore.clockCheck())
        }
    }

    /**
     * States the clock finding as facts side by side, or returns null when there is nothing
     * wrong.
     *
     * Deliberately reports the stamps in full — date and time, not just a clock face. The
     * anomaly that prompted this was invisible in "23:19" and obvious in
     * "2026-09-12 23:19 while the phone says 2026-09-12 11:30".
     */
    private fun describeClock(check: LiveTickStore.ClockCheck): String? {
        if (check.futureTicks == 0) return null
        val skew = check.skewMinutes ?: 0L
        val skewText = if (skew >= 120L) "%.1f hours".format(skew / 60.0) else "$skew minutes"
        return "⚠ ${check.futureTicks} stored tick(s) are timestamped in the FUTURE.\n" +
            "Furthest ahead: ${stamp(check.furthestFutureMillis)} — $skewText ahead of this " +
            "phone's clock, which now reads ${stamp(check.nowMillis)}.\n" +
            "Oldest stored tick: ${stamp(check.earliestMillis)}. " +
            "Newest: ${stamp(check.latestMillis)}.\n" +
            "Ticks are stamped with the phone's own clock at the moment they arrive, so this " +
            "means the clock was wrong while they were being recorded — most likely automatic " +
            "date & time is off. Charts leave these ticks out rather than stretch their time " +
            "axis to reach them."
    }

    private fun stamp(millis: Long?): String {
        if (millis == null) return "—"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(millis)
    }

    /**
     * Turns the measured rate into the two sentences that are actually decision-relevant: the
     * rate itself, and what a full session of it would cost on disk.
     *
     * The cost line uses the SAME 316-bytes-per-row figure the retention window was sized
     * from, so the two can never silently disagree — and it is projected from the measured
     * rate rather than from the bytes already on disk, because what matters is the day this
     * app is about to record with a live screen held open, not the partial days it has.
     */
    private fun describeRate(
        rate: LiveTickStore.SnapshotRate,
        perDay: List<com.niftyradar.app.storage.DayTickCount>
    ): String {
        val perMinute = rate.perInstrumentPerMinute
        val head = if (perMinute == null) {
            "Snapshot rate: no ticks in the last ${rate.windowSeconds}s — nothing to measure. " +
                "Connect the feed during market hours and check again."
        } else {
            val seconds = rate.secondsPerSnapshot
            val cadence = if (seconds != null && seconds >= 1.0) {
                "one every %.1fs".format(seconds)
            } else {
                "%.1f per second".format(perMinute / 60.0)
            }
            val rowsPerSession = perMinute / 60.0 * SESSION_SECONDS * rate.instruments
            val bytesPerSession = (rowsPerSession * BYTES_PER_TICK).toLong()
            "Snapshot rate: %.1f per instrument per minute ($cadence), measured over the last "
                .format(perMinute) +
                "${rate.windowSeconds}s across ${rate.instruments} instrument(s). " +
                "A full 6h15m session at this rate is ~${"%,.0f".format(rowsPerSession)} rows, " +
                "${formatBytes(bytesPerSession)} — so ${LiveTickStore.RETENTION_SESSIONS} days " +
                "would hold ${formatBytes(bytesPerSession * LiveTickStore.RETENTION_SESSIONS)}."
        }
        if (perDay.isEmpty()) return head
        return "$head\n\n" + perDay.take(8).joinToString("\n") { describeDay(it) }
    }

    /**
     * One recorded day as a rate rather than a count.
     *
     * A count on its own is not readable and was actively misleading here: four recorded days
     * held 39,613 ticks on a single Monday and 253 across the rest, so both the total and the
     * average described a device that does not exist. First-to-last gives a real denominator,
     * which turns any past day with enough ticks into the measurement — no live session needed.
     *
     * The caveat stays attached rather than being dropped for brevity: the span covers only
     * the minutes the app was actually open, so it is the rate WHILE CONNECTED. That happens
     * to be exactly the quantity every downstream decision needs, since a screen only has to
     * keep up while it is on.
     */
    private fun describeDay(day: com.niftyradar.app.storage.DayTickCount): String {
        val span = day.spanSeconds
        val head = "${day.day}: ${day.ticks} tick(s)"
        if (span < MIN_SPAN_TO_RATE_SECONDS || day.ticks < 2) {
            return "$head — too short a window to read a rate from"
        }
        val minutes = span / 60
        val connected = if (minutes >= 60) "%.1fh".format(span / 3600.0) else "${minutes}m"
        // The instrument count is not stored per day, so it is inferred from the subscription
        // this app always uses: spot plus the 22 locked contracts. Stated, not hidden, because
        // a day recorded under a different set would make this line wrong.
        val perInstrumentPerMinute = day.ticks.toDouble() / ASSUMED_INSTRUMENTS / (span / 60.0)
        val secondsEach = if (perInstrumentPerMinute > 0.0) 60.0 / perInstrumentPerMinute else 0.0
        val rowsPerSession = perInstrumentPerMinute / 60.0 * SESSION_SECONDS * ASSUMED_INSTRUMENTS
        val bytes = (rowsPerSession * BYTES_PER_TICK).toLong()
        // Below one a minute, the per-minute figure rounds to "0.0/instrument/min", which reads
        // as nothing arriving when in fact something did — so a slow day is described only by
        // its cadence, which stays truthful at any magnitude.
        val rateText = if (perInstrumentPerMinute >= 1.0) {
            "%.1f/instrument/min (one every %.1fs)".format(perInstrumentPerMinute, secondsEach)
        } else {
            "one every %.0fs per instrument".format(secondsEach)
        }
        return "$head over $connected connected — $rateText. " +
            "A full session at this rate: ${formatBytes(bytes)}."
    }

    /**
     * Reclaims the disk space a trim freed (see [LiveTickStore.compact]). Behind an explicit
     * button because VACUUM rewrites the whole file — slow, and it needs headroom while it
     * runs, so it is never something to do to someone's phone unasked.
     */
    fun compactDatabase() {
        if (_compacting.value) return
        viewModelScope.launch {
            _compacting.value = true
            val freed = liveTickStore.compact()
            val summary = liveTickStore.storageSummary()
            _compacting.value = false
            _storageSummary.value =
                "Compacted: ${formatBytes(freed)} returned to the phone. ${describe(summary)}"
        }
    }

    private fun describe(summary: LiveTickStore.StorageSummary): String = when {
        summary.recordedDays == 0 -> "Nothing recorded on disk yet."
        else -> "On disk: ${summary.totalTicks} tick(s) across ${summary.recordedDays} " +
            "recorded day(s) (${summary.oldestDay} to ${summary.newestDay}), " +
            "${formatBytes(summary.onDiskBytes)}. Keeping the most recent " +
            "${LiveTickStore.RETENTION_SESSIONS} trading days."
    }

    private fun formatBytes(bytes: Long): String = when {
        // A GB branch matters specifically for the projection: the whole reason the retention
        // window was cut is a worst case in the gigabytes, and "3119.1 MB" is a number a
        // reader has to stop and divide before it lands.
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    override fun onCleared() {
        super.onCleared()
        feedClient.disconnect()
    }

    private companion object {
        /**
         * 09:15 to 15:40 IST, in seconds — one NSE equity-derivatives session.
         *
         * 15:40, not 15:30: NSE extended the equity-derivatives close by ten minutes on
         * 3 August 2026, to align with the new 15:15–15:35 closing auction session in the cash
         * segment. This constant said 15:30 when it was written, three weeks after that took
         * effect, which made every projection built on it 2.7% light.
         *
         * It is also the ONLY place in this app that hardcodes a market time, and it should
         * stay that way. Everything else reads the exchange's own status messages from the
         * feed (NORMAL_OPEN / NORMAL_CLOSE / CLOSING_END), which is why a change to the
         * trading day broke a projection here and nothing at all anywhere else.
         */
        const val SESSION_SECONDS = 6 * 3600 + 25 * 60

        /**
         * Bytes a single stored tick costs, indices and write-ahead log included. Measured,
         * not assumed: 12.6 MB across 39,843 rows on a real device.
         */
        const val BYTES_PER_TICK = 316

        /**
         * NIFTY 50 spot plus the 22 locked contracts — the set this app always subscribes to
         * (Phase4ViewModel.connect). Used only as the divisor for a PAST day, whose own
         * instrument count is not stored per row.
         */
        const val ASSUMED_INSTRUMENTS = 23

        /**
         * Below this, first-to-last is too short to divide by. A day where the app was opened
         * for forty seconds produces a denominator that turns one heartbeat into a wild rate.
         */
        const val MIN_SPAN_TO_RATE_SECONDS = 120L
    }
}
