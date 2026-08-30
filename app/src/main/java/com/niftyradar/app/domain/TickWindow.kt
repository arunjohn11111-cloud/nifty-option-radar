package com.niftyradar.app.domain

import com.niftyradar.app.storage.LiveTickEntity

/**
 * Shared helper for indicators that need "how much did X change over the last N minutes":
 * [OiPriceQuadrant] and [OrderFlowImbalance]'s combining logic (in [IndicatorEngine]) both
 * compare the most recent tick against the oldest tick still inside a trailing window, not
 * just the very first tick of the day — that would blur a fresh few-minute move into the
 * whole session's move.
 *
 * [ticks] must be sorted oldest-first (guaranteed by
 * [com.niftyradar.app.storage.LiveTickDao.ticksFor]'s `ORDER BY receivedAtMillis ASC`).
 * Returns null if there are fewer than 2 ticks. If the whole session so far is younger than
 * [windowMs], the very first tick of the day is used as the "oldest" endpoint instead of
 * returning null — so an indicator can still show *something* early in the day, just over a
 * shorter-than-ideal window, rather than staying blank until [windowMs] has actually elapsed.
 */
object TickWindow {
    data class Endpoints(val oldest: LiveTickEntity, val newest: LiveTickEntity)

    fun endpoints(ticks: List<LiveTickEntity>, windowMs: Long): Endpoints? {
        if (ticks.size < 2) return null
        val newest = ticks.last()
        val cutoff = newest.receivedAtMillis - windowMs
        val oldest = ticks.firstOrNull { it.receivedAtMillis >= cutoff } ?: ticks.first()
        if (oldest === newest) return null
        return Endpoints(oldest, newest)
    }
}
