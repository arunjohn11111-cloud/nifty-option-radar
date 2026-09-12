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
 *
 * [bestBidPrice]/[bestAskPrice]/[bestBidQuantity]/[bestAskQuantity] are level 1 of the order
 * book (`marketLevel.bidAskQuote[0]` on `MarketFullFeed`, `firstDepth` on
 * `FirstLevelWithGreeks`). This data has been arriving on every tick since Phase 4 and was
 * simply discarded — `marketLevel` appeared nowhere in the codebase. It is read now because
 * two things need it: classifying a trade as buy- or sell-aggressive (is the last price at
 * the ask or at the bid?), and pricing an option by its MID rather than its last trade, so a
 * leg that has not traded for a while stops poisoning anything computed from it. Same
 * NIFTY-50-spot exception as OI: an index has no order book, so these are null there.
 *
 * [averageTradedPrice] is Upstox's own `atp` — the day's volume-weighted average price,
 * computed server-side. Also previously discarded. Worth having as a reference level, since
 * it is a real VWAP this app does not have to calculate or keep state for.
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
    val rho: Double? = null,
    val bestBidPrice: Double? = null,
    val bestAskPrice: Double? = null,
    val bestBidQuantity: Long? = null,
    val bestAskQuantity: Long? = null,
    val averageTradedPrice: Double? = null
) {
    /**
     * The mid of the best bid and ask, or null when either side is missing or non-positive.
     *
     * Preferred over [ltp] anywhere a "current fair price" is wanted. An option's last traded
     * price can be minutes stale on a quiet strike, and anything derived from two such prices
     * (put-call parity being the obvious case) inherits that staleness as a fake spike. The
     * mid is always current, because a quote does not need a trade to exist.
     *
     * Returns null rather than guessing when only one side is quoted — a one-sided book has no
     * meaningful mid, and a fabricated one would be worse than an absent one.
     */
    val midPrice: Double?
        get() {
            val bid = bestBidPrice ?: return null
            val ask = bestAskPrice ?: return null
            if (bid <= 0.0 || ask <= 0.0 || ask < bid) return null
            return (bid + ask) / 2.0
        }

    /** Best price available now: the mid when the book gives one, otherwise the last trade. */
    val referencePrice: Double get() = midPrice ?: ltp
}

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
