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
    val impliedVolatility: Double?,
    val totalBuyQuantity: Double? = null,
    val totalSellQuantity: Double? = null,
    val delta: Double? = null,
    val theta: Double? = null,
    val gamma: Double? = null,
    val vega: Double? = null,
    val rho: Double? = null
)
