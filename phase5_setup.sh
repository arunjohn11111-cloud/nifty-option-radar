#!/data/data/com.termux/files/usr/bin/bash
# Phase 5: on-device storage (Room) for every live tick Phase 4's WebSocket
# receives. Writes new storage files, updates the feed/UI wiring, adds Room +
# kapt to the Gradle files, appends a PROJECT_SPEC.md note, then commits and
# pushes.
set -e

if [ ! -f "settings.gradle.kts" ]; then
    echo "ERROR: settings.gradle.kts not found in the current directory."
    echo "cd into your NiftyOptionRadar repo clone first (e.g. ~/nifty-build/NiftyOptionRadar), then re-run this script."
    exit 1
fi

mkdir -p app/src/main/java/com/niftyradar/app/storage

# ---------------------------------------------------------------------------
# New: Room entity
# ---------------------------------------------------------------------------
cat > app/src/main/java/com/niftyradar/app/storage/LiveTickEntity.kt << 'LIVETICKENTITY_EOF'
package com.niftyradar.app.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 5: one persisted tick for one instrument, one IST trading day.
 * [sessionDate] uses the exact same "yyyy-MM-dd in Asia/Kolkata" convention
 * as [com.niftyradar.app.model.RadarSession.sessionDate] so a later phase can
 * join ticks back to the radar session that was locked that day.
 *
 * Deliberately flat/denormalized (one row per tick, not a "latest value"
 * table) — Phase 6+ charts need the full intra-day series, not just the
 * most recent price.
 */
@Entity(
    tableName = "live_ticks",
    indices = [Index(value = ["sessionDate", "instrumentKey"])]
)
data class LiveTickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionDate: String,
    val instrumentKey: String,
    val receivedAtMillis: Long,
    val ltp: Double,
    val closePrice: Double,
    val lastTradeTimeMillis: Long,
    val openInterest: Double?,
    val volumeTradedToday: Long?,
    val impliedVolatility: Double?
)
LIVETICKENTITY_EOF
echo "Wrote LiveTickEntity.kt"

# ---------------------------------------------------------------------------
# New: Room DAO
# ---------------------------------------------------------------------------
cat > app/src/main/java/com/niftyradar/app/storage/LiveTickDao.kt << 'LIVETICKDAO_EOF'
package com.niftyradar.app.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LiveTickDao {

    @Insert
    suspend fun insert(tick: LiveTickEntity)

    /** Full intra-day tick history for one instrument — what a Phase 6+ chart will read. */
    @Query(
        "SELECT * FROM live_ticks WHERE sessionDate = :sessionDate AND instrumentKey = :instrumentKey " +
            "ORDER BY receivedAtMillis ASC"
    )
    suspend fun ticksFor(sessionDate: String, instrumentKey: String): List<LiveTickEntity>

    /** Cheap proof-of-life count for the Phase 4/5 screen: is anything actually being saved? */
    @Query("SELECT COUNT(*) FROM live_ticks WHERE sessionDate = :sessionDate")
    suspend fun countForSession(sessionDate: String): Int

    @Query("SELECT COUNT(DISTINCT instrumentKey) FROM live_ticks WHERE sessionDate = :sessionDate")
    suspend fun instrumentCountForSession(sessionDate: String): Int
}
LIVETICKDAO_EOF
echo "Wrote LiveTickDao.kt"

# ---------------------------------------------------------------------------
# New: Room database
# ---------------------------------------------------------------------------
cat > app/src/main/java/com/niftyradar/app/storage/NiftyRadarDatabase.kt << 'NIFTYRADARDB_EOF'
package com.niftyradar.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Phase 5: the app's only Room database so far — just [LiveTickEntity].
 * `exportSchema = false` on purpose: this is still an early, single-developer
 * phase of the app (no migration history to preserve yet, per PROJECT_SPEC.md's
 * "build incrementally, don't over-engineer ahead of need" approach); revisit
 * once real migrations matter.
 */
@Database(entities = [LiveTickEntity::class], version = 1, exportSchema = false)
abstract class NiftyRadarDatabase : RoomDatabase() {

    abstract fun liveTickDao(): LiveTickDao

    companion object {
        @Volatile private var instance: NiftyRadarDatabase? = null

        fun getInstance(context: Context): NiftyRadarDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NiftyRadarDatabase::class.java,
                    "nifty_radar.db"
                ).build().also { instance = it }
            }
    }
}
NIFTYRADARDB_EOF
echo "Wrote NiftyRadarDatabase.kt"

# ---------------------------------------------------------------------------
# New: app-facing store wrapper
# ---------------------------------------------------------------------------
cat > app/src/main/java/com/niftyradar/app/storage/LiveTickStore.kt << 'LIVETICKSTORE_EOF'
package com.niftyradar.app.storage

import android.content.Context
import com.niftyradar.app.feed.TickEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 5: thin app-facing wrapper around [LiveTickDao] — Phase4ViewModel
 * (and later, chart-reading code) talks to this, not to Room directly, same
 * pattern as [RadarSessionStore] / [com.niftyradar.app.security.SecureTokenStore].
 */
class LiveTickStore(context: Context) {

    private val dao = NiftyRadarDatabase.getInstance(context).liveTickDao()

    suspend fun recordTick(sessionDate: String, event: TickEvent) = withContext(Dispatchers.IO) {
        dao.insert(
            LiveTickEntity(
                sessionDate = sessionDate,
                instrumentKey = event.instrumentKey,
                receivedAtMillis = event.receivedAtMillis,
                ltp = event.quote.ltp,
                closePrice = event.quote.closePrice,
                lastTradeTimeMillis = event.quote.lastTradeTimeMillis,
                openInterest = event.quote.openInterest,
                volumeTradedToday = event.quote.volumeTradedToday,
                impliedVolatility = event.quote.impliedVolatility
            )
        )
    }

    suspend fun countForSession(sessionDate: String): Int =
        withContext(Dispatchers.IO) { dao.countForSession(sessionDate) }

    suspend fun instrumentCountForSession(sessionDate: String): Int =
        withContext(Dispatchers.IO) { dao.instrumentCountForSession(sessionDate) }

    suspend fun ticksFor(sessionDate: String, instrumentKey: String): List<LiveTickEntity> =
        withContext(Dispatchers.IO) { dao.ticksFor(sessionDate, instrumentKey) }
}
LIVETICKSTORE_EOF
echo "Wrote LiveTickStore.kt"

# ---------------------------------------------------------------------------
# Updated: LiveQuote.kt (adds TickEvent)
# ---------------------------------------------------------------------------
cat > app/src/main/java/com/niftyradar/app/feed/LiveQuote.kt << 'LIVEQUOTE_EOF'
package com.niftyradar.app.feed

/**
 * Live quote extracted from one Market Data Feed V3 `Feed` protobuf message,
 * decoupled from the generated protobuf types so nothing outside this
 * package ever touches them directly.
 *
 * [openInterest], [volumeTradedToday], [impliedVolatility] are null for the
 * NIFTY 50 index feed (it only ever carries an `IndexFullFeed`, which has no
 * such fields) and populated for option contracts (`MarketFullFeed`), per
 * PROJECT_SPEC.md section 8 — "full" mode is what carries `oi`/`vtt`.
 */
data class LiveQuote(
    val ltp: Double,
    val closePrice: Double,
    val lastTradeTimeMillis: Long,
    val openInterest: Double? = null,
    val volumeTradedToday: Long? = null,
    val impliedVolatility: Double? = null
)

/** Connection lifecycle for [MarketFeedClient], surfaced to Phase4ViewModel/Phase4Screen. */
sealed class FeedConnectionState {
    data object Disconnected : FeedConnectionState()
    data object Connecting : FeedConnectionState()
    data class Connected(val marketStatusSummary: String) : FeedConnectionState()
    data class Failed(val message: String) : FeedConnectionState()
}

/**
 * Phase 5: one individual tick as it arrives, rather than the collapsed
 * "latest quote per instrument" view [MarketFeedClient.quotes] exposes for
 * the UI. [MarketFeedClient.tickEvents] emits one of these per instrument
 * update in every [com.niftyradar.app.marketdatafeed.FeedResponse] so a
 * collector (Phase4ViewModel, writing to Room) can persist full tick
 * history instead of only ever seeing the most recent value.
 */
data class TickEvent(
    val instrumentKey: String,
    val quote: LiveQuote,
    val receivedAtMillis: Long
)
LIVEQUOTE_EOF
echo "Wrote LiveQuote.kt"

# ---------------------------------------------------------------------------
# Updated: MarketFeedClient.kt (adds tickEvents SharedFlow)
# ---------------------------------------------------------------------------
cat > app/src/main/java/com/niftyradar/app/feed/MarketFeedClient.kt << 'MARKETFEEDCLIENT_EOF'
package com.niftyradar.app.feed

import com.niftyradar.app.marketdatafeed.Feed
import com.niftyradar.app.marketdatafeed.FeedResponse
import com.niftyradar.app.marketdatafeed.LTPC
import com.niftyradar.app.marketdatafeed.Type
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages exactly one Market Data Feed V3 WebSocket connection: connect,
 * subscribe, decode incoming Protobuf `FeedResponse` messages, and expose the
 * latest [LiveQuote] per instrument key as a [StateFlow] the UI can collect.
 *
 * Endpoint/message-format details verified against Upstox's V3 docs on
 * 2026-08-27 — re-check before relying on this in production, per
 * PROJECT_SPEC.md's standing "docs can drift" warning:
 *  - Subscribe request must be sent as a BINARY frame even though its payload
 *    is JSON text — Upstox silently ignores a text-frame subscribe.
 *  - Market Data Feed V3 is a gated scope: Upstox must manually enable
 *    "Market Data Feed V3 – Read" for this app's Client ID before the
 *    authorize call (in [com.niftyradar.app.network.UpstoxApiClient]) will
 *    succeed — a 403 there is almost always that, not a code bug.
 */
class MarketFeedClient {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // a WebSocket stays open indefinitely
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow<FeedConnectionState>(FeedConnectionState.Disconnected)
    val connectionState: StateFlow<FeedConnectionState> = _connectionState.asStateFlow()

    private val _quotes = MutableStateFlow<Map<String, LiveQuote>>(emptyMap())
    val quotes: StateFlow<Map<String, LiveQuote>> = _quotes.asStateFlow()

    // Phase 5: every individual tick, for Phase4ViewModel to persist to Room —
    // separate from [quotes] above, which only ever holds the latest value per
    // instrument and would lose tick history the moment a newer one arrives.
    // A dropping buffer is fine here: this is a live radar, not a trade ledger,
    // so an occasional dropped tick under extreme load beats blocking the
    // WebSocket's read loop.
    private val _tickEvents = MutableSharedFlow<TickEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tickEvents: SharedFlow<TickEvent> = _tickEvents.asSharedFlow()

    /**
     * @param wssUrl the one-time-use `authorized_redirect_uri` from the V3 authorize REST call.
     * @param accessToken same Upstox token used for the authorize call — sent again as the
     *   WebSocket handshake's Authorization header, per Upstox's connection docs.
     * @param instrumentKeys NIFTY 50 spot key + the 22 locked option instrument keys. This app
     *   never changes this set mid-session — "the radar is locked for the day" (spec section 3).
     * @param mode "full" by default: the only mode that carries OI + volume (spec section 8).
     */
    fun connect(
        wssUrl: String,
        accessToken: String,
        instrumentKeys: List<String>,
        mode: String = "full"
    ) {
        disconnect()
        _connectionState.value = FeedConnectionState.Connecting

        val request = Request.Builder()
            .url(wssUrl)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "*/*")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                val subscribeJson = JSONObject().apply {
                    put("guid", "niftyradar-${System.nanoTime()}")
                    put("method", "sub")
                    put(
                        "data",
                        JSONObject().apply {
                            put("mode", mode)
                            put("instrumentKeys", JSONArray(instrumentKeys))
                        }
                    )
                }
                // BINARY frame required — see class doc.
                webSocket.send(subscribeJson.toString().encodeUtf8())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    handleFeedResponse(FeedResponse.parseFrom(bytes.toByteArray()))
                } catch (e: Exception) {
                    // One malformed/unexpected frame shouldn't kill the whole connection.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { " (HTTP ${it.code})" }.orEmpty()
                _connectionState.value = FeedConnectionState.Failed(
                    (t.message ?: "WebSocket connection failed") + detail
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = FeedConnectionState.Disconnected
            }
        })
    }

    private fun handleFeedResponse(response: FeedResponse) {
        if (response.type == Type.market_info) {
            val statuses = response.marketInfo.segmentStatusMap
                .entries.joinToString(", ") { "${it.key}=${it.value}" }
            _connectionState.value = FeedConnectionState.Connected(statuses.ifBlank { "live" })
        } else if (_connectionState.value !is FeedConnectionState.Connected) {
            _connectionState.value = FeedConnectionState.Connected("live")
        }

        if (response.feedsCount == 0) return

        val receivedAt = System.currentTimeMillis()
        val updated = _quotes.value.toMutableMap()
        for ((instrumentKey, feed) in response.feedsMap) {
            extractLiveQuote(feed)?.let {
                updated[instrumentKey] = it
                _tickEvents.tryEmit(TickEvent(instrumentKey, it, receivedAt))
            }
        }
        _quotes.value = updated
    }

    /**
     * A [Feed] is a oneof of ltpc / fullFeed / firstLevelWithGreeks; we asked for "full" mode so
     * fullFeed is what actually arrives, but the other two are handled defensively in case Upstox
     * ever returns a different shape than requested.
     */
    private fun extractLiveQuote(feed: Feed): LiveQuote? = when {
        feed.hasFullFeed() -> {
            val full = feed.fullFeed
            when {
                full.hasMarketFF() -> full.marketFF.let {
                    it.ltpc.toLiveQuote(
                        openInterest = it.oi,
                        volumeTradedToday = it.vtt,
                        impliedVolatility = it.iv
                    )
                }
                full.hasIndexFF() -> full.indexFF.ltpc.toLiveQuote()
                else -> null
            }
        }
        feed.hasFirstLevelWithGreeks() -> feed.firstLevelWithGreeks.let {
            it.ltpc.toLiveQuote(openInterest = it.oi, volumeTradedToday = it.vtt, impliedVolatility = it.iv)
        }
        feed.hasLtpc() -> feed.ltpc.toLiveQuote()
        else -> null
    }

    private fun LTPC.toLiveQuote(
        openInterest: Double? = null,
        volumeTradedToday: Long? = null,
        impliedVolatility: Double? = null
    ) = LiveQuote(
        ltp = ltp,
        closePrice = cp,
        lastTradeTimeMillis = ltt,
        openInterest = openInterest,
        volumeTradedToday = volumeTradedToday,
        impliedVolatility = impliedVolatility
    )

    fun disconnect() {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        _connectionState.value = FeedConnectionState.Disconnected
        _quotes.value = emptyMap()
    }
}
MARKETFEEDCLIENT_EOF
echo "Wrote MarketFeedClient.kt"

# ---------------------------------------------------------------------------
# Updated: Phase4ViewModel.kt (wires Room persistence + "check stored ticks")
# ---------------------------------------------------------------------------
cat > app/src/main/java/com/niftyradar/app/ui/Phase4ViewModel.kt << 'PHASE4VIEWMODEL_EOF'
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

    private var lockedSession: RadarSession? = null

    init {
        // Phase 5: persist every tick as it arrives, for as long as this ViewModel is alive —
        // independent of connect()/disconnect(), so re-subscribing never has to re-wire this.
        viewModelScope.launch {
            feedClient.tickEvents.collect { event ->
                liveTickStore.recordTick(todaySessionDate(), event)
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
        }
    }

    override fun onCleared() {
        super.onCleared()
        feedClient.disconnect()
    }
}
PHASE4VIEWMODEL_EOF
echo "Wrote Phase4ViewModel.kt"

# ---------------------------------------------------------------------------
# Updated: Phase4Screen.kt (adds "Check stored ticks" button + summary text)
# ---------------------------------------------------------------------------
cat > app/src/main/java/com/niftyradar/app/ui/Phase4Screen.kt << 'PHASE4SCREEN_EOF'
package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niftyradar.app.feed.FeedConnectionState
import com.niftyradar.app.feed.LiveQuote
import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.network.UpstoxApiClient

/**
 * PHASE 4/5 SCREEN: authorize + connect the Market Data Feed V3 WebSocket and
 * show live LTP/OI/volume ticking in for NIFTY 50 spot + the 22 locked
 * contracts (Phase 4), while every tick is also persisted to Room in the
 * background (Phase 5) — see the "Check stored ticks" button. No charts yet
 * — that's Phase 6 onward. This screen exists purely to prove the feed
 * connects and the ticks really land on disk, before anything is built on top.
 */
@Composable
fun Phase4Screen(viewModel: Phase4ViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val quotes by viewModel.quotes.collectAsState()
    val storedTickSummary by viewModel.storedTickSummary.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadLockedSession()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }

        Text("Phase 4 — Live Market Data Feed (V3)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connects the Market Data Feed V3 WebSocket and subscribes to NIFTY 50 spot + " +
                "the 22 locked contracts in 'full' mode (LTP, OI, volume), and saves every " +
                "tick to an on-device database. No charts yet — this screen only proves live " +
                "ticks arrive and land on disk.",
            style = MaterialTheme.typography.bodyMedium
        )

        val session = viewModel.lockedSessionOrNull()

        if (uiState is Phase4UiState.NoRadarLocked) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No radar locked for today yet.", style = MaterialTheme.typography.titleMedium)
                    Text("Go back to Phase 2/3 and lock today's radar first.")
                }
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.connect() },
                enabled = uiState !is Phase4UiState.Authorizing &&
                    connectionState !is FeedConnectionState.Connected &&
                    connectionState !is FeedConnectionState.Connecting
            ) {
                Text("Connect Live Feed")
            }
            OutlinedButton(onClick = { viewModel.disconnect() }) {
                Text("Disconnect")
            }
        }

        ConnectionStatusCard(uiState, connectionState)

        HorizontalDivider()
        Text("Phase 5 — local storage (Room)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Every tick above is also written to an on-device database as it arrives, " +
                "independent of this screen. Tap below any time — even right after opening " +
                "the app, before connecting — to prove it's really on disk, not just in memory.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.refreshStoredTickSummary() }) {
                Text("Check stored ticks (today)")
            }
        }
        if (storedTickSummary != null) {
            Text(storedTickSummary!!, style = MaterialTheme.typography.bodyMedium)
        }

        if (session != null) {
            HorizontalDivider()
            QuoteRow(label = "NIFTY 50 SPOT", quote = quotes[UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY])
            HorizontalDivider()
            Text("Locked contracts:", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (strike in session.strikes) {
                    val ceKey = session.contracts[RadarSession.contractKey(strike, "CE")]?.instrumentKey
                    val peKey = session.contracts[RadarSession.contractKey(strike, "PE")]?.instrumentKey
                    val marker = if (strike == session.atmStrike) " (ATM)" else ""
                    StrikeRow(
                        label = "$strike$marker",
                        ceQuote = ceKey?.let { quotes[it] },
                        peQuote = peKey?.let { quotes[it] }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(uiState: Phase4UiState, connectionState: FeedConnectionState) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                uiState is Phase4UiState.ConnectionError -> {
                    Text("❌ FAILED", style = MaterialTheme.typography.titleMedium)
                    Text(uiState.message, style = MaterialTheme.typography.bodySmall)
                }
                uiState is Phase4UiState.Authorizing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Calling Upstox feed authorize endpoint ...")
                    }
                }
                connectionState is FeedConnectionState.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Opening WebSocket ...")
                    }
                }
                connectionState is FeedConnectionState.Connected -> {
                    Text("✅ CONNECTED", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Market status: ${connectionState.marketStatusSummary}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                connectionState is FeedConnectionState.Failed -> {
                    Text("❌ FAILED", style = MaterialTheme.typography.titleMedium)
                    Text(connectionState.message, style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("Not connected yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun QuoteRow(label: String, quote: LiveQuote?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            if (quote != null) "LTP ${quote.ltp}" else "waiting for tick…",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun StrikeRow(label: String, ceQuote: LiveQuote?, peQuote: LiveQuote?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text("CE: ${quoteSummary(ceQuote)}", style = MaterialTheme.typography.bodySmall)
        Text("PE: ${quoteSummary(peQuote)}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun quoteSummary(quote: LiveQuote?): String {
    if (quote == null) return "…"
    val oi = quote.openInterest?.toLong()?.toString() ?: "-"
    val vol = quote.volumeTradedToday?.toString() ?: "-"
    return "LTP ${quote.ltp}  OI $oi  Vol $vol"
}
PHASE4SCREEN_EOF
echo "Wrote Phase4Screen.kt"

# ---------------------------------------------------------------------------
# Updated: root build.gradle.kts (adds kapt plugin declaration)
# ---------------------------------------------------------------------------
cat > build.gradle.kts << 'ROOTBUILD_EOF'
// Top-level build file. Individual module build files apply the plugins they need.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Phase 5: Room's annotation processor (generates the DAO/Database implementations
    // in NiftyRadarDatabase.kt et al.) — kapt rather than KSP so its version always
    // matches the Kotlin Gradle plugin version above with nothing extra to pin/verify.
    id("org.jetbrains.kotlin.kapt") version "2.0.21" apply false
    // Phase 4: compiles app/src/main/proto/MarketDataFeed.proto into Kotlin
    // classes for decoding Market Data Feed V3 WebSocket messages.
    id("com.google.protobuf") version "0.10.0" apply false
}
ROOTBUILD_EOF
echo "Wrote root build.gradle.kts"

# ---------------------------------------------------------------------------
# Updated: app/build.gradle.kts (adds kapt plugin + Room dependencies)
# ---------------------------------------------------------------------------
cat > app/build.gradle.kts << 'APPBUILD_EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.niftyradar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.niftyradar.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Phase 4: compiles app/src/main/proto/MarketDataFeed.proto (Upstox's Market
// Data Feed V3 schema) into standalone Kotlin/Java classes in package
// com.niftyradar.app.marketdatafeed (see the `option java_*` lines in that
// file). protoc is downloaded automatically at build time — this only needs
// normal internet access (available on the GitHub Actions runner), not
// anything special.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.0"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Jetpack Compose (versions managed by the BOM)
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Secure, encrypted local storage for the Upstox access token (Phase 1)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking (Phase 1-3 REST calls + Phase 4's Market Data Feed V3
    // WebSocket both go through OkHttp — it already includes WebSocket
    // support, no extra dependency needed for that part)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Phase 4: decodes Market Data Feed V3's binary Protobuf messages.
    // "javalite" runtime (not full protobuf-java) — smaller, Android-friendly,
    // matches the "lite" builtin configured in the protobuf {} block above.
    implementation("com.google.protobuf:protobuf-javalite:4.36.0")

    // Phase 5: on-device storage for live ticks (survives app restarts,
    // feeds Phase 6+ charts). room-ktx adds the suspend-fun DAO support used
    // in LiveTickDao/LiveTickStore.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
APPBUILD_EOF
echo "Wrote app/build.gradle.kts"

# ---------------------------------------------------------------------------
# Append Phase 5 note to PROJECT_SPEC.md (only if not already present)
# ---------------------------------------------------------------------------
if [ -f "PROJECT_SPEC.md" ] && ! grep -q "## Phase 5 note" PROJECT_SPEC.md; then
    cat >> PROJECT_SPEC.md << 'SPECNOTE_EOF'

## Phase 4 addendum (2026-08-27) — scroll bug found and fixed same day

Confirmed working end-to-end on-device on 2026-08-27 with live ticks for
NIFTY 50 spot + all 22 locked contracts. One bug found and fixed the same
day: `Phase4Screen`'s original layout used a fixed header (description +
buttons + connection-status card) above a `weight(1f)` `LazyColumn` for the
spot row + locked-contracts list. Once "CONNECTED" made the status card
multi-line (one line per exchange segment), the fixed header grew tall
enough to squeeze the weighted list toward zero height with nothing left
scrollable to reach it — the spot row and contracts list were still there,
just unreachably below the fold. Fixed by making the whole screen one
`verticalScroll`'d `Column` (matching `RadarSetupScreen`'s pattern) and
replacing the `LazyColumn` with a plain `Column` — only 11 strikes ever
render here, so there's no performance reason to keep the lazy version.

## Phase 5 note (2026-08-27)

Adds on-device persistence for every tick Phase 4's WebSocket receives, via
Room: `LiveTickEntity`/`LiveTickDao`/`NiftyRadarDatabase`/`LiveTickStore`
under `com.niftyradar.app.storage`. `MarketFeedClient` gained a second
stream, `tickEvents: SharedFlow<TickEvent>` (one event per instrument per
incoming `FeedResponse`), separate from the existing `quotes:
StateFlow<Map<String, LiveQuote>>` — `quotes` only ever holds the latest
value per instrument and would lose history the moment a newer tick
overwrote it, so persistence has to hook the per-event stream, not the
collapsed map. `Phase4ViewModel` collects `tickEvents` once in `init {}`
(independent of `connect()`/`disconnect()`, so reconnecting never needs to
re-wire storage) and writes each one to Room tagged with the same IST
`sessionDate` convention `RadarSession` already uses, so a later phase can
join ticks back to the day's locked radar.

Used kapt rather than KSP for Room's annotation processor specifically
because this sandbox cannot compile Kotlin/Gradle to verify a KSP-version
pairing before shipping it — kapt's version is pinned to the same Kotlin
Gradle plugin version already declared in the root `build.gradle.kts`, so
there's one fewer version number to get wrong sight-unseen. Revisit KSP once
there's a way to actually build-verify a version bump.

Verification for this phase is the "Check stored ticks (today)" button on
the Phase 4/5 screen: it reads the count back from Room (not from the
in-memory `quotes` map), so it should show a growing number while connected,
and — the actual point of this phase — a non-zero number if you force-close
and reopen the app without reconnecting at all.
SPECNOTE_EOF
    echo "Appended Phase 5 note to PROJECT_SPEC.md"
else
    echo "PROJECT_SPEC.md already has a Phase 5 note or file missing — skipped."
fi

git add \
    app/src/main/java/com/niftyradar/app/storage/LiveTickEntity.kt \
    app/src/main/java/com/niftyradar/app/storage/LiveTickDao.kt \
    app/src/main/java/com/niftyradar/app/storage/NiftyRadarDatabase.kt \
    app/src/main/java/com/niftyradar/app/storage/LiveTickStore.kt \
    app/src/main/java/com/niftyradar/app/feed/LiveQuote.kt \
    app/src/main/java/com/niftyradar/app/feed/MarketFeedClient.kt \
    app/src/main/java/com/niftyradar/app/ui/Phase4ViewModel.kt \
    app/src/main/java/com/niftyradar/app/ui/Phase4Screen.kt \
    build.gradle.kts \
    app/build.gradle.kts \
    PROJECT_SPEC.md

git commit -m "Phase 5: persist every live tick to Room (on-device storage), survives app restart"
git push

echo "Done. If push asked for a username/password, use your GitHub username and the fine-grained PAT (Contents: Read and write) as the password."
