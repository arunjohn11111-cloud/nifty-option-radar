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
