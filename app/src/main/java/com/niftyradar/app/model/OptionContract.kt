package com.niftyradar.app.model

/**
 * One row from Upstox's Option Contracts API
 * (`GET /v2/option/contract`) response `data[]` array.
 *
 * Field names match Upstox's documented response exactly (verified 2026-08-26):
 * strike_price, instrument_key, instrument_type ("CE"/"PE"), expiry,
 * trading_symbol, lot_size.
 */
data class OptionContract(
    val strikePrice: Double,
    val instrumentKey: String,
    val instrumentType: String, // "CE" or "PE"
    val expiry: String,
    val tradingSymbol: String,
    val lotSize: Int
)
