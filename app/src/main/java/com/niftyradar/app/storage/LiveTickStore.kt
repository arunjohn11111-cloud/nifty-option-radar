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
                impliedVolatility = event.quote.impliedVolatility,
                totalBuyQuantity = event.quote.totalBuyQuantity,
                totalSellQuantity = event.quote.totalSellQuantity,
                delta = event.quote.delta,
                theta = event.quote.theta,
                gamma = event.quote.gamma,
                vega = event.quote.vega,
                rho = event.quote.rho
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
