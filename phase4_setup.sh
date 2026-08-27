#!/data/data/com.termux/files/usr/bin/bash
# Phase 4 (Market Data Feed V3 WebSocket) — file setup script.
#
# HOW TO USE:
#   1. cd into your existing clone of the nifty-option-radar repo in Termux
#      (the same folder you used to push Phases 1-3).
#   2. Put this script in that folder (see the chat message for how).
#   3. Run:  bash phase4_setup.sh
#   4. It creates/overwrites the Phase 4 files, then commits and pushes.
#
# It refuses to run if the current directory doesn't look like the repo root
# (no settings.gradle.kts found), so it's safe even if you're in the wrong
# folder.

set -e

if [ ! -f "settings.gradle.kts" ]; then
  echo "ERROR: settings.gradle.kts not found in the current directory."
  echo "cd into your nifty-option-radar repo clone first, then re-run this script."
  exit 1
fi

echo "Creating directories..."
mkdir -p app/src/main/proto
mkdir -p app/src/main/java/com/niftyradar/app/feed

echo "Writing app/src/main/proto/MarketDataFeed.proto ..."
cat > app/src/main/proto/MarketDataFeed.proto << 'EOF'
// Upstox Market Data Feed V3 protobuf schema.
//
// Source: https://assets.upstox.com/feed/market-data-feed/v3/MarketDataFeed.proto
// (fetched and hand-verified 2026-08-27 for Phase 4 of this app; re-check
// against the live file before relying on this if Upstox ever changes it —
// see PROJECT_SPEC.md's standing "docs can drift" warning).
//
// The two `option` lines below (java_package / java_multiple_files) were
// ADDED by us — they only control the shape of the generated Kotlin/Java
// code (one top-level class per message, in our own package), not the wire
// format, so they cannot cause a mismatch with what Upstox's server sends.
// Every message/enum/field name and number below is unchanged from Upstox's
// original file.
syntax = "proto3";
package com.upstox.marketdatafeederv3udapi.rpc.proto;

option java_package = "com.niftyradar.app.marketdatafeed";
option java_multiple_files = true;

message LTPC {
  double ltp = 1;
  int64 ltt = 2;
  int64 ltq = 3;
  double cp = 4;
}

message MarketLevel {
  repeated Quote bidAskQuote = 1;
}

message MarketOHLC {
  repeated OHLC ohlc = 1;
}

message Quote {
  int64 bidQ = 1;
  double bidP = 2;
  int64 askQ = 3;
  double askP = 4;
}

message OptionGreeks {
  double delta = 1;
  double theta = 2;
  double gamma = 3;
  double vega = 4;
  double rho = 5;
}

message OHLC {
  string interval = 1;
  double open = 2;
  double high = 3;
  double low = 4;
  double close = 5;
  int64 vol = 6;
  int64 ts = 7;
}

enum Type{
  initial_feed = 0;
  live_feed = 1;
  market_info = 2;
}

message MarketFullFeed{
  LTPC ltpc = 1;
  MarketLevel marketLevel = 2;
  OptionGreeks optionGreeks = 3;
  MarketOHLC marketOHLC = 4;
  double atp = 5; //avg traded price
  int64 vtt = 6; //volume traded today
  double oi = 7; //open interest
  double iv = 8; //implied volatility
  double tbq =9; //total buy quantity
  double tsq = 10; //total sell quantity
}

message IndexFullFeed{
  LTPC ltpc = 1;
  MarketOHLC marketOHLC = 2;
}


message FullFeed {
  oneof FullFeedUnion {
    MarketFullFeed marketFF = 1;
    IndexFullFeed indexFF = 2;
  }
}

message FirstLevelWithGreeks{
  LTPC ltpc = 1;
  Quote firstDepth = 2;
  OptionGreeks optionGreeks = 3;
  int64 vtt = 4; //volume traded today
  double oi = 5; //open interest
  double iv = 6; //implied volatility
}

message Feed {
  oneof FeedUnion {
    LTPC ltpc = 1;
    FullFeed fullFeed = 2;
    FirstLevelWithGreeks firstLevelWithGreeks = 3;
  }
  RequestMode requestMode = 4;
}

enum RequestMode {
  ltpc = 0;
  full_d5 = 1;
  option_greeks = 2;
  full_d30 = 3;
}

enum MarketStatus {
  PRE_OPEN_START = 0;
  PRE_OPEN_END = 1;
  NORMAL_OPEN = 2;
  NORMAL_CLOSE = 3;
  CLOSING_START = 4;
  CLOSING_END = 5;
}


message MarketInfo {
  map<string, MarketStatus> segmentStatus = 1;
}

message FeedResponse{
  Type type = 1;
  map<string, Feed> feeds = 2;
  int64 currentTs = 3;
  MarketInfo marketInfo = 4;
}
EOF

echo "Writing app/src/main/java/com/niftyradar/app/feed/LiveQuote.kt ..."
cat > app/src/main/java/com/niftyradar/app/feed/LiveQuote.kt << 'EOF'
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
EOF

echo "Writing app/src/main/java/com/niftyradar/app/feed/MarketFeedClient.kt ..."
cat > app/src/main/java/com/niftyradar/app/feed/MarketFeedClient.kt << 'EOF'
package com.niftyradar.app.feed

import com.niftyradar.app.marketdatafeed.Feed
import com.niftyradar.app.marketdatafeed.FeedResponse
import com.niftyradar.app.marketdatafeed.LTPC
import com.niftyradar.app.marketdatafeed.Type
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

        val updated = _quotes.value.toMutableMap()
        for ((instrumentKey, feed) in response.feedsMap) {
            extractLiveQuote(feed)?.let { updated[instrumentKey] = it }
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
EOF

echo "Writing app/src/main/java/com/niftyradar/app/ui/Phase4ViewModel.kt ..."
cat > app/src/main/java/com/niftyradar/app/ui/Phase4ViewModel.kt << 'EOF'
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
EOF

echo "Writing app/src/main/java/com/niftyradar/app/ui/Phase4Screen.kt ..."
cat > app/src/main/java/com/niftyradar/app/ui/Phase4Screen.kt << 'EOF'
package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * PHASE 4 SCREEN ONLY: authorize + connect the Market Data Feed V3 WebSocket
 * and show live LTP/OI/volume ticking in for NIFTY 50 spot + the 22 locked
 * contracts. No charts, no local storage yet — that's Phase 6 onward. This
 * screen exists purely to prove the feed connects and carries the right
 * fields for exactly the locked instruments before anything is built on top.
 */
@Composable
fun Phase4Screen(viewModel: Phase4ViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val quotes by viewModel.quotes.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadLockedSession()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }

        Text("Phase 4 — Live Market Data Feed (V3)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connects the Market Data Feed V3 WebSocket and subscribes to NIFTY 50 spot + " +
                "the 22 locked contracts in 'full' mode (LTP, OI, volume). No charts or local " +
                "storage yet — this screen only proves live ticks arrive.",
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

        if (session != null) {
            HorizontalDivider()
            QuoteRow(label = "NIFTY 50 SPOT", quote = quotes[UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY])
            HorizontalDivider()
            Text("Locked contracts:", style = MaterialTheme.typography.titleSmall)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(session.strikes) { strike ->
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
EOF

echo "Overwriting build.gradle.kts (root) ..."
cat > build.gradle.kts << 'EOF'
// Top-level build file. Individual module build files apply the plugins they need.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Phase 4: compiles app/src/main/proto/MarketDataFeed.proto into Kotlin
    // classes for decoding Market Data Feed V3 WebSocket messages.
    id("com.google.protobuf") version "0.10.0" apply false
}
EOF

echo "Overwriting app/build.gradle.kts ..."
cat > app/build.gradle.kts << 'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
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

    debugImplementation("androidx.compose.ui:ui-tooling")
}
EOF

echo "Overwriting app/src/main/java/com/niftyradar/app/network/UpstoxApiClient.kt ..."
cat > app/src/main/java/com/niftyradar/app/network/UpstoxApiClient.kt << 'EOF'
package com.niftyradar.app.network

import com.niftyradar.app.model.OptionContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * REST client for the (small) set of official Upstox endpoints this app needs
 * before the Market Data Feed V3 WebSocket takes over in Phase 4:
 *  - Get Profile (Phase 1): is this token valid?
 *  - LTP Quotes V3 (Phase 3): one-shot NIFTY 50 spot price to compute ATM at
 *    session start — cheaper than standing up the WebSocket just for this.
 *  - Option Contracts (Phase 2): the strikes/instrument keys for an expiry.
 *  - Market Data Feed Authorize V3 (Phase 4): one-time wss:// URL for the feed.
 *
 * Endpoints/fields below were verified against the live Upstox developer docs
 * on 2026-08-26/27; re-check before relying on this in production, per the
 * project spec's own warning that these can drift.
 */
class UpstoxApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class ProfileResult {
        data class Success(
            val userName: String,
            val userId: String,
            val email: String,
            val broker: String,
            val exchanges: List<String>,
            val isActive: Boolean
        ) : ProfileResult()

        data class Failure(val message: String, val httpCode: Int? = null) : ProfileResult()
    }

    sealed class SpotResult {
        data class Success(val lastPrice: Double) : SpotResult()
        data class Failure(val message: String, val httpCode: Int? = null) : SpotResult()
    }

    sealed class ContractsResult {
        data class Success(val contracts: List<OptionContract>) : ContractsResult()
        data class Failure(val message: String, val httpCode: Int? = null) : ContractsResult()
    }

    sealed class FeedAuthorizeResult {
        data class Success(val webSocketUrl: String) : FeedAuthorizeResult()
        data class Failure(val message: String, val httpCode: Int? = null) : FeedAuthorizeResult()
    }

    /**
     * Calls GET /v2/user/profile with the given bearer token. Runs on Dispatchers.IO;
     * safe to call from a Compose coroutine scope / ViewModel without extra wrapping.
     */
    suspend fun verifyToken(accessToken: String): ProfileResult = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) {
            return@withContext ProfileResult.Failure("No token entered.")
        }

        val request = Request.Builder()
            .url("$BASE_URL/v2/user/profile")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val errorMessage = extractErrorMessage(bodyString)
                        ?: "Upstox returned HTTP ${response.code}."
                    return@withContext ProfileResult.Failure(errorMessage, response.code)
                }

                val json = JSONObject(bodyString)
                val data = json.getJSONObject("data")

                val exchanges = mutableListOf<String>()
                data.optJSONArray("exchanges")?.let { arr ->
                    for (i in 0 until arr.length()) exchanges.add(arr.getString(i))
                }

                ProfileResult.Success(
                    userName = data.optString("user_name", "(unknown)"),
                    userId = data.optString("user_id", "(unknown)"),
                    email = data.optString("email", "(unknown)"),
                    broker = data.optString("broker", "UPSTOX"),
                    exchanges = exchanges,
                    isActive = data.optBoolean("is_active", true)
                )
            }
        } catch (io: IOException) {
            ProfileResult.Failure("Network error: ${io.message ?: "could not reach Upstox"}.")
        } catch (parse: Exception) {
            ProfileResult.Failure("Could not parse Upstox response: ${parse.message}")
        }
    }

    /**
     * GET /v3/market-quote/ltp?instrument_key=NSE_INDEX|Nifty 50
     *
     * NOTE (important quirk, confirmed from Upstox docs): the response's
     * `data` object is keyed by something like `"NSE_INDEX:Nifty 50"` — a
     * colon-separated exchange:trading-symbol string — which is NOT the same
     * string as the `instrument_key` (pipe-separated) used in the request. We
     * only ever ask for one instrument here, so rather than guess the exact
     * key format, we just read whichever single entry `data` contains.
     */
    suspend fun getNiftySpotLtp(accessToken: String): SpotResult = withContext(Dispatchers.IO) {
        val url = "$BASE_URL_V3/market-quote/ltp".toHttpUrl().newBuilder()
            .addQueryParameter("instrument_key", NIFTY_50_INSTRUMENT_KEY)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val errorMessage = extractErrorMessage(bodyString)
                        ?: "Upstox returned HTTP ${response.code}."
                    return@withContext SpotResult.Failure(errorMessage, response.code)
                }

                val json = JSONObject(bodyString)
                val data = json.getJSONObject("data")
                val keys = data.keys()
                if (!keys.hasNext()) {
                    return@withContext SpotResult.Failure("Upstox returned no quote for NIFTY 50.")
                }
                val entry = data.getJSONObject(keys.next())
                SpotResult.Success(entry.getDouble("last_price"))
            }
        } catch (io: IOException) {
            SpotResult.Failure("Network error: ${io.message ?: "could not reach Upstox"}.")
        } catch (parse: Exception) {
            SpotResult.Failure("Could not parse Upstox spot quote: ${parse.message}")
        }
    }

    /**
     * GET /v2/option/contract?instrument_key=NSE_INDEX|Nifty 50&expiry_date=yyyy-MM-dd
     */
    suspend fun getOptionContracts(accessToken: String, expiryDate: String): ContractsResult =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/v2/option/contract".toHttpUrl().newBuilder()
                .addQueryParameter("instrument_key", NIFTY_50_INSTRUMENT_KEY)
                .addQueryParameter("expiry_date", expiryDate)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        val errorMessage = extractErrorMessage(bodyString)
                            ?: "Upstox returned HTTP ${response.code}."
                        return@withContext ContractsResult.Failure(errorMessage, response.code)
                    }

                    val json = JSONObject(bodyString)
                    val dataArr = json.getJSONArray("data")
                    val contracts = mutableListOf<OptionContract>()
                    for (i in 0 until dataArr.length()) {
                        val c = dataArr.getJSONObject(i)
                        contracts += OptionContract(
                            strikePrice = c.getDouble("strike_price"),
                            instrumentKey = c.getString("instrument_key"),
                            instrumentType = c.getString("instrument_type"),
                            expiry = c.optString("expiry", expiryDate),
                            tradingSymbol = c.optString("trading_symbol", ""),
                            lotSize = c.optInt("lot_size", 0)
                        )
                    }

                    if (contracts.isEmpty()) {
                        return@withContext ContractsResult.Failure(
                            "Upstox returned zero contracts for expiry $expiryDate. Check the " +
                                "expiry date is a valid, currently-listed NIFTY expiry."
                        )
                    }

                    ContractsResult.Success(contracts)
                }
            } catch (io: IOException) {
                ContractsResult.Failure("Network error: ${io.message ?: "could not reach Upstox"}.")
            } catch (parse: Exception) {
                ContractsResult.Failure("Could not parse Upstox option contracts: ${parse.message}")
            }
        }

    /**
     * GET /v3/feed/market-data-feed/authorize — returns a one-time-use `wss://`
     * URL (`data.authorized_redirect_uri`) for opening the actual Market Data
     * Feed V3 WebSocket (see [com.niftyradar.app.feed.MarketFeedClient]).
     *
     * IMPORTANT: Market Data Feed V3 is a gated scope — Upstox requires
     * "Market Data Feed V3 – Read" to be manually enabled per app (post your
     * app's Client ID/API Key on community.upstox.com asking for it to be
     * enabled) before this call will succeed. A 403 here almost always means
     * that scope isn't enabled yet, not a bug in this code.
     */
    suspend fun getMarketDataFeedAuthorizeUrl(accessToken: String): FeedAuthorizeResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE_URL_V3/feed/market-data-feed/authorize")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        var errorMessage = extractErrorMessage(bodyString)
                            ?: "Upstox returned HTTP ${response.code}."
                        if (response.code == 403) {
                            errorMessage += " If this is a permission error: Market Data Feed V3 " +
                                "must be manually enabled for your app — post your app's Client " +
                                "ID on community.upstox.com asking for the 'Market Data Feed V3 " +
                                "– Read' scope."
                        }
                        return@withContext FeedAuthorizeResult.Failure(errorMessage, response.code)
                    }

                    val json = JSONObject(bodyString)
                    val data = json.getJSONObject("data")
                    FeedAuthorizeResult.Success(data.getString("authorized_redirect_uri"))
                }
            } catch (io: IOException) {
                FeedAuthorizeResult.Failure("Network error: ${io.message ?: "could not reach Upstox"}.")
            } catch (parse: Exception) {
                FeedAuthorizeResult.Failure("Could not parse Upstox feed-authorize response: ${parse.message}")
            }
        }

    private fun extractErrorMessage(bodyString: String): String? {
        return try {
            val json = JSONObject(bodyString)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val first = errors.getJSONObject(0)
                first.optString("message").ifBlank { null }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val BASE_URL = "https://api.upstox.com"
        const val BASE_URL_V3 = "https://api.upstox.com/v3"
        const val NIFTY_50_INSTRUMENT_KEY = "NSE_INDEX|Nifty 50"
    }
}
EOF

echo "Overwriting app/src/main/java/com/niftyradar/app/MainActivity.kt ..."
cat > app/src/main/java/com/niftyradar/app/MainActivity.kt << 'EOF'
package com.niftyradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.niftyradar.app.ui.AuthUiState
import com.niftyradar.app.ui.AuthViewModel
import com.niftyradar.app.ui.Phase4Screen
import com.niftyradar.app.ui.Phase4ViewModel
import com.niftyradar.app.ui.RadarSetupScreen
import com.niftyradar.app.ui.RadarSetupViewModel

/**
 * Phases 1-4. Nothing about local storage or charts lives here yet — those
 * are Phase 6 onward, built and tested one at a time. Screen switching is a
 * plain in-memory enum, not Navigation-Compose: there are only a handful of
 * screens right now and adding a nav-graph dependency for that would be
 * premature.
 */
private enum class Screen { Auth, RadarSetup, Phase4 }

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val radarSetupViewModel: RadarSetupViewModel by viewModels()
    private val phase4ViewModel: Phase4ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.Auth) }

                    when (screen) {
                        Screen.Auth -> Phase1Screen(
                            viewModel = authViewModel,
                            onContinueToRadarSetup = { screen = Screen.RadarSetup }
                        )
                        Screen.RadarSetup -> RadarSetupScreen(
                            viewModel = radarSetupViewModel,
                            onBackToAuth = { screen = Screen.Auth },
                            onContinueToPhase4 = { screen = Screen.Phase4 }
                        )
                        Screen.Phase4 -> Phase4Screen(
                            viewModel = phase4ViewModel,
                            onBack = { screen = Screen.RadarSetup }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Phase1Screen(viewModel: AuthViewModel, onContinueToRadarSetup: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var tokenInput by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Nifty Option Radar", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Phase 1 — Upstox connection check",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            "Paste your Upstox access token below. It is encrypted on this device " +
                "(Android Keystore) and is only ever sent to api.upstox.com over HTTPS — " +
                "never anywhere else, never logged, never hard-coded.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (viewModel.hasStoredToken()) {
            Text(
                "A token is already saved on this device: ${viewModel.storedTokenRedacted()}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Upstox access token") },
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = tokenVisible, onCheckedChange = { tokenVisible = it })
            Text("Show token")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.saveAndVerify(tokenInput) },
                enabled = uiState !is AuthUiState.Verifying
            ) {
                Text("Save & Verify")
            }

            OutlinedButton(
                onClick = { viewModel.verifyStoredToken() },
                enabled = viewModel.hasStoredToken() && uiState !is AuthUiState.Verifying
            ) {
                Text("Re-verify saved token")
            }

            TextButton(onClick = {
                viewModel.clearToken()
                tokenInput = ""
            }) {
                Text("Clear")
            }
        }

        HorizontalDivider()

        StatusCard(uiState)

        if (uiState is AuthUiState.Connected) {
            Button(onClick = onContinueToRadarSetup, modifier = Modifier.fillMaxWidth()) {
                Text("Continue to Phase 2/3 — Build Today's Radar →")
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: AuthUiState) {
    when (uiState) {
        is AuthUiState.NotVerified -> {
            Text("Status: not verified yet.", style = MaterialTheme.typography.bodyMedium)
        }

        is AuthUiState.Verifying -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Calling Upstox GET /v2/user/profile ...")
            }
        }

        is AuthUiState.Connected -> {
            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✅ CONNECTED", style = MaterialTheme.typography.titleMedium)
                    Text("User: ${uiState.userName} (${uiState.userId})")
                    Text("Broker: ${uiState.broker}")
                    Text("Exchanges: ${uiState.exchanges.joinToString(", ")}")
                    Text(
                        "Token verified — continue below to build today's radar.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        is AuthUiState.Failed -> {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("❌ FAILED", style = MaterialTheme.typography.titleMedium)
                    Text(uiState.message)
                }
            }
        }
    }
}
EOF

echo "Overwriting app/src/main/java/com/niftyradar/app/ui/RadarSetupScreen.kt ..."
cat > app/src/main/java/com/niftyradar/app/ui/RadarSetupScreen.kt << 'EOF'
package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niftyradar.app.model.RadarSession

/**
 * PHASE 2/3 SCREEN ONLY: build (or re-load) today's locked radar. No live
 * ticks, no charts here — this screen exists purely to prove the option
 * chain fetch + ATM/strike selection + 22-contract lock works before Phase 4
 * (WebSocket) gets built on top of it.
 */
@Composable
fun RadarSetupScreen(
    viewModel: RadarSetupViewModel,
    onBackToAuth: () -> Unit,
    onContinueToPhase4: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var expiryDate by remember { mutableStateOf("2026-09-01") }

    LaunchedEffect(Unit) {
        viewModel.loadExistingSessionIfAny()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBackToAuth) { Text("← Back") }
        }

        Text("Phase 2/3 — Build Today's Radar", style = MaterialTheme.typography.titleMedium)
        Text(
            "Fetches NIFTY 50 spot + the option chain for the expiry below, then locks " +
                "5 strikes below ATM + ATM + 5 above (22 CE/PE contracts) for the rest of " +
                "today's session. Once locked, this never silently rebuilds itself.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = expiryDate,
            onValueChange = { expiryDate = it },
            label = { Text("Expiry date (yyyy-MM-dd)") },
            singleLine = true,
            enabled = !viewModel.hasLockedSessionToday(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.buildTodaysRadar(expiryDate) },
                enabled = uiState !is RadarSetupUiState.LoadingSpot && uiState !is RadarSetupUiState.LoadingContracts
            ) {
                Text(if (viewModel.hasLockedSessionToday()) "Load Today's Radar" else "Lock Today's Radar")
            }
        }

        if (viewModel.hasLockedSessionToday()) {
            Text(
                "A radar is already locked for today. Rebuilding is only for testing before " +
                    "you rely on this for a real session, since it discards the original lock.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { viewModel.buildTodaysRadar(expiryDate, force = true) }) {
                Text("Force rebuild today's radar (testing only)")
            }
        }

        HorizontalDivider()

        RadarStatusView(uiState)

        if (uiState is RadarSetupUiState.Locked) {
            Button(onClick = onContinueToPhase4, modifier = Modifier.fillMaxWidth()) {
                Text("Continue to Phase 4 — Live Market Data Feed →")
            }
        }
    }
}

@Composable
private fun RadarStatusView(uiState: RadarSetupUiState) {
    when (uiState) {
        is RadarSetupUiState.Idle -> {
            Text("No radar built yet today.", style = MaterialTheme.typography.bodyMedium)
        }

        is RadarSetupUiState.LoadingSpot -> {
            LoadingRow("Fetching NIFTY 50 spot (GET /v3/market-quote/ltp) ...")
        }

        is RadarSetupUiState.LoadingContracts -> {
            LoadingRow("Fetching option chain (GET /v2/option/contract) ...")
        }

        is RadarSetupUiState.Failed -> {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("❌ FAILED", style = MaterialTheme.typography.titleMedium)
                    Text(uiState.message)
                }
            }
        }

        is RadarSetupUiState.Locked -> {
            RadarLockedCard(uiState.session, uiState.reused)
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
private fun RadarLockedCard(session: RadarSession, reused: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (reused) "🔒 RADAR ALREADY LOCKED (loaded, not rebuilt)" else "🔒 RADAR LOCKED",
                    style = MaterialTheme.typography.titleMedium
                )
                Text("Session date: ${session.sessionDate}")
                Text("Expiry: ${session.expiry}")
                Text("Spot at lock: ${session.spotAtLock}")
                Text("ATM strike: ${session.atmStrike}")
                Text("Radar range: ${session.strikes.minOrNull()} – ${session.strikes.maxOrNull()}")
                Text("Strikes locked: ${session.strikes.size}, contracts resolved: ${session.contracts.size} / ${session.strikes.size * 2}")
            }
        }

        if (session.warnings.isNotEmpty()) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚠️ Warnings", style = MaterialTheme.typography.titleSmall)
                    session.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Text("Locked contracts:", style = MaterialTheme.typography.titleSmall)
        Column {
            for (strike in session.strikes) {
                val ce = session.contracts[RadarSession.contractKey(strike, "CE")]
                val pe = session.contracts[RadarSession.contractKey(strike, "PE")]
                val marker = if (strike == session.atmStrike) " (ATM)" else ""
                Text(
                    "$strike$marker  —  CE: ${ce?.instrumentKey ?: "MISSING"}   PE: ${pe?.instrumentKey ?: "MISSING"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
EOF

echo ""
echo "All Phase 4 files written. Staging + committing + pushing..."
git add -A
git commit -m "Phase 4: Market Data Feed V3 WebSocket (connect, subscribe, decode, show live LTP/OI/volume)"
git push

echo ""
echo "Done. Now go trigger the GitHub Actions build (Actions tab -> Build debug APK -> Run workflow),"
echo "same as before, and re-install the new APK once it succeeds."
