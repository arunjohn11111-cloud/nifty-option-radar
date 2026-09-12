package com.niftyradar.app.domain

import com.niftyradar.app.storage.LiveTickEntity
import kotlin.math.abs

/**
 * Put-call parity deviation, and — the part that is actually the signal — how fast it is
 * moving.
 *
 * For European options, which NIFTY index options are, parity says
 *
 *     C - P = S - K * exp(-rT)
 *
 * Drop the discount factor and it reduces to `C - P = S - K`, so the deviation is
 *
 *     dev = (CE - PE) - (spot - strike)
 *
 * DROPPING THE RATE TERM IS DELIBERATE and only defensible because this reads the deviation's
 * VELOCITY rather than its level. `K * (1 - exp(-rT))` is worth roughly 12 points at a 23,400
 * strike three days out (23,400 x 6.5% x 3/365), which is far too big to ignore if you were
 * reading the absolute number — you would conclude the market was permanently mispriced. But
 * it moves only with the clock, smoothly and by fractions of a point over minutes, so in a
 * first difference it cancels to almost nothing. Read the level and this is wrong; read the
 * change and it is fine.
 *
 * WHY MID-PRICES, NOT LAST-TRADED. Both legs are priced off [LiveQuote.midPrice] wherever the
 * order book gives one, falling back to LTP. Using LTP for both legs is the dominant source of
 * false signals here: a strike that has not traded for two minutes carries a stale last price,
 * so the moment its next trade prints, `dev` jumps — and the jump is entirely an artefact of
 * one leg catching up, not of any change in the relationship between them. A quote does not
 * need a trade to exist, so the mid is always current. [ParityReading.usedMidPrices] records
 * which basis was actually available, so a reading built on stale LTPs can be discounted
 * rather than silently trusted.
 *
 * This is a PARALLEL signal: it is not one of the five voting indicators in [IndicatorEngine]
 * and deliberately does not touch them. The point of keeping it separate is to be able to
 * compare it against the existing TBQ/TSQ order-flow read over the same sessions before
 * deciding whether it deserves a vote.
 */
data class ParityReading(
    val strike: Double,
    val atMillis: Long,
    /** `(CE - PE) - (spot - strike)`, in index points. */
    val deviation: Double,
    val cePrice: Double,
    val pePrice: Double,
    val spot: Double,
    /** False when either leg had to fall back to its last traded price — see the class doc. */
    val usedMidPrices: Boolean
)

/**
 * How fast the deviation is moving, in points per minute, measured across a window.
 *
 * [pointsPerMinute] is the signal. [samples] and [windowMillis] are carried so a caller can
 * refuse to act on a velocity computed from two ticks a second apart, which would be almost
 * entirely noise however large the number looks.
 */
data class ParityVelocity(
    val strike: Double,
    val pointsPerMinute: Double,
    val firstDeviation: Double,
    val lastDeviation: Double,
    val samples: Int,
    val windowMillis: Long,
    val allMidPriced: Boolean
)

object PutCallParity {

    /**
     * Only ATM and one strike either side are worth reading. Further out, both legs are cheap
     * and thinly quoted, so the deviation is dominated by tick size and spread rather than by
     * anything informative — the noise floor is larger than the effect.
     */
    const val STRIKES_EACH_SIDE_OF_ATM = 1

    /**
     * A velocity is only reported when the window actually spans at least this long. Below it
     * the denominator is so small that ordinary quote flicker produces enormous
     * points-per-minute figures that mean nothing.
     */
    const val MIN_VELOCITY_WINDOW_MS = 30_000L

    /** And only from at least this many samples, for the same reason. */
    const val MIN_VELOCITY_SAMPLES = 4

    /**
     * The price to use for one leg: the order-book mid when both sides are quoted, otherwise
     * the last traded price. Returns the price and whether it was a mid.
     */
    private fun legPrice(tick: LiveTickEntity): Pair<Double, Boolean> {
        val bid = tick.bestBidPrice
        val ask = tick.bestAskPrice
        return if (bid != null && ask != null && bid > 0.0 && ask > 0.0 && ask >= bid) {
            ((bid + ask) / 2.0) to true
        } else {
            tick.ltp to false
        }
    }

    /**
     * One reading from the nearest CE tick, PE tick and spot tick at or before [atMillis].
     *
     * "At or before" matters: the three instruments do not tick in lockstep, so picking each
     * one's latest tick regardless of time would silently compare a CE from now against a spot
     * from a minute ago. Every input is therefore taken as of the same instant, and the
     * reading is refused outright if any leg has no tick by then.
     */
    fun readingAt(
        strike: Double,
        atMillis: Long,
        ceTicks: List<LiveTickEntity>,
        peTicks: List<LiveTickEntity>,
        spotTicks: List<LiveTickEntity>
    ): ParityReading? {
        val ce = ceTicks.lastOrNull { it.receivedAtMillis <= atMillis } ?: return null
        val pe = peTicks.lastOrNull { it.receivedAtMillis <= atMillis } ?: return null
        val spotTick = spotTicks.lastOrNull { it.receivedAtMillis <= atMillis } ?: return null

        val (cePrice, ceWasMid) = legPrice(ce)
        val (pePrice, peWasMid) = legPrice(pe)
        val spot = spotTick.ltp

        return ParityReading(
            strike = strike,
            atMillis = atMillis,
            deviation = (cePrice - pePrice) - (spot - strike),
            cePrice = cePrice,
            pePrice = pePrice,
            spot = spot,
            usedMidPrices = ceWasMid && peWasMid
        )
    }

    /**
     * A series of readings for one strike, one per spot tick inside [windowMillis] of the most
     * recent tick seen.
     *
     * Sampled on the SPOT clock on purpose. Spot is the one leg that is always liquid, so it
     * gives an even time base; sampling on option ticks instead would bunch the series around
     * whichever leg happened to be busy and bias the velocity toward whatever was happening
     * then.
     */
    fun series(
        strike: Double,
        ceTicks: List<LiveTickEntity>,
        peTicks: List<LiveTickEntity>,
        spotTicks: List<LiveTickEntity>,
        windowMillis: Long
    ): List<ParityReading> {
        if (spotTicks.isEmpty()) return emptyList()
        val ce = ceTicks.sortedBy { it.receivedAtMillis }
        val pe = peTicks.sortedBy { it.receivedAtMillis }
        val spot = spotTicks.sortedBy { it.receivedAtMillis }
        val cutoff = spot.last().receivedAtMillis - windowMillis
        return spot
            .filter { it.receivedAtMillis >= cutoff }
            .mapNotNull { readingAt(strike, it.receivedAtMillis, ce, pe, spot) }
    }

    /**
     * Velocity of the deviation across [readings], in points per minute.
     *
     * A plain first-to-last difference over the window, not a fitted slope: the series is
     * short, unevenly spaced and noisy, and a regression on that would suggest more precision
     * than the data carries. What matters is the direction and rough size of the drift, and
     * endpoints answer that honestly.
     *
     * Returns null when the window or the sample count is too small to mean anything — see
     * [MIN_VELOCITY_WINDOW_MS] and [MIN_VELOCITY_SAMPLES].
     */
    fun velocity(strike: Double, readings: List<ParityReading>): ParityVelocity? {
        if (readings.size < MIN_VELOCITY_SAMPLES) return null
        val ordered = readings.sortedBy { it.atMillis }
        val first = ordered.first()
        val last = ordered.last()
        val spanMillis = last.atMillis - first.atMillis
        if (spanMillis < MIN_VELOCITY_WINDOW_MS) return null

        val perMinute = (last.deviation - first.deviation) / (spanMillis / 60_000.0)
        return ParityVelocity(
            strike = strike,
            pointsPerMinute = perMinute,
            firstDeviation = first.deviation,
            lastDeviation = last.deviation,
            samples = ordered.size,
            windowMillis = spanMillis,
            allMidPriced = ordered.all { it.usedMidPrices }
        )
    }

    /**
     * The strikes worth reading: ATM and [STRIKES_EACH_SIDE_OF_ATM] either side, restricted to
     * strikes the session actually locked.
     *
     * Takes the ATM from live [spot] rather than from whatever was ATM when the session
     * locked — the same mistake that had two of the five voting indicators reading a contract
     * 140 points away from the money for a whole morning.
     */
    fun strikesToWatch(lockedStrikes: List<Double>, spot: Double): List<Double> {
        if (lockedStrikes.isEmpty()) return emptyList()
        val sorted = lockedStrikes.sorted()
        val atmIndex = sorted.indices.minByOrNull { abs(sorted[it] - spot) } ?: return emptyList()
        val from = (atmIndex - STRIKES_EACH_SIDE_OF_ATM).coerceAtLeast(0)
        val to = (atmIndex + STRIKES_EACH_SIDE_OF_ATM).coerceAtMost(sorted.lastIndex)
        return sorted.subList(from, to + 1)
    }
}
