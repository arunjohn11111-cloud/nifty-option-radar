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
