package com.niftyradar.app.model

/**
 * One OHLC(+volume+OI) candle from Upstox's V3 Historical Candle Data API
 * (`GET /v3/historical-candle/{instrument_key}/{unit}/{interval}/{to_date}/{from_date}`).
 *
 * [timestampIso] is kept as the raw ISO-8601 string Upstox sends (e.g.
 * "2026-08-25T00:00:00+05:30") rather than parsed into a Long here — every
 * current use (pivot points, ATR) only needs each candle's own H/L/C, never
 * a parsed instant, so there's no need to pull in a date library yet. Because
 * Upstox always uses the same fixed "+05:30" IST offset, a plain lexicographic
 * string sort of this field is already a correct chronological sort — see
 * [com.niftyradar.app.network.UpstoxApiClient]'s candle parsing, which relies
 * on exactly that instead of trusting the API's own (undocumented, and seen
 * to vary) return order.
 */
data class Candle(
    val timestampIso: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val openInterest: Double
)
