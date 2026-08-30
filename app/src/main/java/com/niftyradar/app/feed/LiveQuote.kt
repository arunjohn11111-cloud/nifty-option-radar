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
 *
 * [totalBuyQuantity]/[totalSellQuantity] are the exchange's own aggregate
 * pending buy/sell order quantity for the instrument at that moment (`tbq`/
 * `tsq` on `MarketFullFeed`) — a direct buy-vs-sell pressure reading, unlike
 * OI which only reflects settled positions. Same NIFTY-50-spot exception as
 * OI: only `MarketFullFeed` (option contracts) carries these, so they're
 * null for the index feed.
 *
 * [delta]/[theta]/[gamma]/[vega]/[rho] are Upstox's own server-side-computed
 * option Greeks (`OptionGreeks` on `MarketFullFeed`/`FirstLevelWithGreeks`) —
 * this app never runs a Black-Scholes calculation itself, it only reads what
 * Upstox already sends on every tick. Same NIFTY-50-spot exception as OI/IV:
 * an index has no Greeks, so these are null for the spot feed.
 */
data class LiveQuote(
    val ltp: Double,
    val closePrice: Double,
    val lastTradeTimeMillis: Long,
    val openInterest: Double? = null,
    val volumeTradedToday: Long? = null,
    val impliedVolatility: Double? = null,
    val totalBuyQuantity: Double? = null,
    val totalSellQuantity: Double? = null,
    val delta: Double? = null,
    val theta: Double? = null,
    val gamma: Double? = null,
    val vega: Double? = null,
    val rho: Double? = null
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
