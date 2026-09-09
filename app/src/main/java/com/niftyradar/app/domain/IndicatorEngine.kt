package com.niftyradar.app.domain

import com.niftyradar.app.model.Candle
import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.storage.LiveTickEntity
import kotlin.math.abs

/**
 * Combines the individual indicator calculators into the dashboard's ordered list of votes,
 * per PROJECT_SPEC.md's 6-indicator design. Currently computes 5 of the eventual 6 — ATR is
 * never in this list at all, since it doesn't vote (see [IndicatorSignal]'s doc comment) —
 * it's shown separately for Target/SL sizing.
 *
 * ATM CE/PE combining, used by both OI+Price Quadrant and Order-Flow Imbalance: each
 * indicator's own-price read on the PE side gets INVERTED before counting it as a
 * NIFTY-direction vote, because PE price moves opposite to NIFTY (PE up when NIFTY down).
 * When CE and PE disagree after that inversion, the result is NEUTRAL with both readings
 * named in the reason — a real split shouldn't be forced into a false consensus.
 */
data class DashboardResult(
    val signals: List<IndicatorSignal>,
    val bullishCount: Int,
    val bearishCount: Int,
    val neutralCount: Int
) {
    val total: Int get() = signals.size
}

object IndicatorEngine {
    /** Same "recent move" window every reading below uses — 5 minutes. */
    private const val WINDOW_MS = 5 * 60_000L

    /**
     * How far apart EMA9 and EMA21 must be, as a fraction of the index level, before Trend
     * (9/21 EMA) is willing to vote at all. 0.05% of NIFTY is roughly 12 points at 24,000.
     * See [trendSignal] for why this exists.
     */
    private const val TREND_DEADBAND_FRACTION = 0.0005

    fun evaluate(
        session: RadarSession,
        ticksByInstrument: Map<String, List<LiveTickEntity>>,
        spotTicks: List<LiveTickEntity>,
        pivots: PivotLevels,
        trendCandles: List<Candle>
    ): DashboardResult {
        val atmStrike = session.atmStrike
        val ceKey = session.contracts[RadarSession.contractKey(atmStrike, "CE")]?.instrumentKey
        val peKey = session.contracts[RadarSession.contractKey(atmStrike, "PE")]?.instrumentKey
        val ceTicks = ceKey?.let { ticksByInstrument[it] } ?: emptyList()
        val peTicks = peKey?.let { ticksByInstrument[it] } ?: emptyList()

        val signals = listOf(
            oiPriceQuadrantSignal(ceTicks, peTicks),
            orderFlowSignal(ceTicks, peTicks),
            pivotPointSignal(spotTicks, pivots),
            trendSignal(trendCandles),
            gammaExposureSignal(session, ticksByInstrument, spotTicks)
        )

        return DashboardResult(
            signals = signals,
            bullishCount = signals.count { it.direction == SignalDirection.BULLISH },
            bearishCount = signals.count { it.direction == SignalDirection.BEARISH },
            neutralCount = signals.count { it.direction == SignalDirection.NEUTRAL }
        )
    }

    private fun oiPriceQuadrantSignal(
        ceTicks: List<LiveTickEntity>,
        peTicks: List<LiveTickEntity>
    ): IndicatorSignal {
        val ce = OiPriceQuadrant.classify(ceTicks, WINDOW_MS)
        val pe = OiPriceQuadrant.classify(peTicks, WINDOW_MS)
        if (ce == null && pe == null) {
            return IndicatorSignal("OI + Price Quadrant", SignalDirection.NEUTRAL, "Not enough OI history yet.")
        }

        val ceView = ce?.bullishForOwnPrice
        val peView = pe?.bullishForOwnPrice?.let { !it }
        val ceLabel = ce?.label?.let(::describeQuadrant) ?: "no read"
        val peLabel = pe?.label?.let(::describeQuadrant) ?: "no read"
        val reason = "ATM CE: $ceLabel (%.2f%% price, %.2f%% OI). ATM PE: $peLabel (%.2f%% price, %.2f%% OI).".format(
            ce?.priceChangePercent ?: 0.0, ce?.oiChangePercent ?: 0.0,
            pe?.priceChangePercent ?: 0.0, pe?.oiChangePercent ?: 0.0
        )
        return IndicatorSignal("OI + Price Quadrant", combineViews(ceView, peView), reason)
    }

    private fun orderFlowSignal(
        ceTicks: List<LiveTickEntity>,
        peTicks: List<LiveTickEntity>
    ): IndicatorSignal {
        val ce = OrderFlowImbalance.latest(ceTicks)
        val pe = OrderFlowImbalance.latest(peTicks)
        if (ce == null && pe == null) {
            return IndicatorSignal("Order-Flow Imbalance", SignalDirection.NEUTRAL, "No buy/sell quantity data yet.")
        }

        val ceView = ce?.bullishForOwnPrice
        val peView = pe?.bullishForOwnPrice?.let { !it }
        val reason = "ATM CE TBQ/TSQ: %.0f/%.0f. ATM PE TBQ/TSQ: %.0f/%.0f.".format(
            ce?.totalBuyQuantity ?: 0.0, ce?.totalSellQuantity ?: 0.0,
            pe?.totalBuyQuantity ?: 0.0, pe?.totalSellQuantity ?: 0.0
        )
        return IndicatorSignal("Order-Flow Imbalance", combineViews(ceView, peView), reason)
    }

    private fun pivotPointSignal(spotTicks: List<LiveTickEntity>, pivots: PivotLevels): IndicatorSignal {
        val spot = spotTicks.lastOrNull()?.ltp
            ?: return IndicatorSignal("Pivot Points", SignalDirection.NEUTRAL, "No live spot price yet.")

        val direction = when {
            spot > pivots.r1 -> SignalDirection.BULLISH
            spot < pivots.s1 -> SignalDirection.BEARISH
            else -> SignalDirection.NEUTRAL
        }
        val reason = when (direction) {
            SignalDirection.BULLISH -> "Spot %.2f is above R1 %.2f — broke resistance.".format(spot, pivots.r1)
            SignalDirection.BEARISH -> "Spot %.2f is below S1 %.2f — broke support.".format(spot, pivots.s1)
            SignalDirection.NEUTRAL ->
                "Spot %.2f is between S1 %.2f and R1 %.2f — no breakout yet.".format(spot, pivots.s1, pivots.r1)
        }
        return IndicatorSignal("Pivot Points", direction, reason)
    }

    /**
     * [candles] are NIFTY 50's own 15-min candles, historical+intraday already merged by the
     * caller (see [com.niftyradar.app.ui.Phase9ViewModel.loadTrendCandles]). EMA9 above EMA21
     * = uptrend (bullish), below = downtrend (bearish) — the standard retail reading of a
     * 9/21 EMA crossover. Notes "(just crossed)" when the previous candle had the opposite
     * relationship, since a fresh cross is a more notable event than one that's held for a
     * while.
     *
     * THE DEADBAND is the part that matters. A bare `ema9 > ema21` comparison has no idea how
     * FAR apart the two lines are, so it reports a confident direction on a gap of half a
     * point. That was seen live on 2026-09-09: EMA9 24058.14 against EMA21 24058.68, a gap of
     * 0.54 points — 0.002% of spot, and a number that flips sign on the next candle. That is
     * not a trend, it is two lines lying on top of each other, and a vote cast on it makes the
     * whole board noisier without adding any information. So the lines must be at least
     * [TREND_DEADBAND_FRACTION] of the index apart before this indicator takes a side; inside
     * that band it returns NEUTRAL and says plainly why, rather than dressing up a coin flip
     * as a signal.
     */
    private fun trendSignal(candles: List<Candle>): IndicatorSignal {
        val ema9Series = ExponentialMovingAverage.series(candles, 9)
        val ema21Series = ExponentialMovingAverage.series(candles, 21)
        if (ema9Series.isEmpty() || ema21Series.isEmpty()) {
            return IndicatorSignal("Trend (9/21 EMA)", SignalDirection.NEUTRAL, "Not enough 15-min candles yet.")
        }

        val ema9 = ema9Series.last()
        val ema21 = ema21Series.last()
        val gap = ema9 - ema21
        val deadband = abs(ema21) * TREND_DEADBAND_FRACTION

        if (abs(gap) < deadband) {
            return IndicatorSignal(
                "Trend (9/21 EMA)",
                SignalDirection.NEUTRAL,
                "EMA9 %.2f vs EMA21 %.2f — only %.2f apart, inside the %.2f flat band. Too flat to call."
                    .format(ema9, ema21, abs(gap), deadband)
            )
        }

        val direction = if (gap > 0) SignalDirection.BULLISH else SignalDirection.BEARISH

        val justCrossed = if (ema9Series.size >= 2 && ema21Series.size >= 2) {
            val wasAbove = ema9Series[ema9Series.size - 2] > ema21Series[ema21Series.size - 2]
            wasAbove != (gap > 0)
        } else {
            false
        }
        val reason = "EMA9 %.2f vs EMA21 %.2f — %.2f apart%s"
            .format(ema9, ema21, abs(gap), if (justCrossed) " (just crossed)" else "")
        return IndicatorSignal("Trend (9/21 EMA)", direction, reason)
    }

    private fun gammaExposureSignal(
        session: RadarSession,
        ticksByInstrument: Map<String, List<LiveTickEntity>>,
        spotTicks: List<LiveTickEntity>
    ): IndicatorSignal {
        val spot = spotTicks.lastOrNull()?.ltp
            ?: return IndicatorSignal("Gamma Exposure", SignalDirection.NEUTRAL, "No live spot price yet.")

        val latestByInstrument = ticksByInstrument.mapNotNull { (key, ticks) ->
            ticks.lastOrNull { it.gamma != null }?.let { key to it }
        }.toMap()
        val gex = GammaExposure.compute(latestByInstrument, session.contracts, session.strikes, spot)
            ?: return IndicatorSignal("Gamma Exposure", SignalDirection.NEUTRAL, "Not enough Greeks data yet.")

        if (gex.netGex >= 0.0) {
            return IndicatorSignal(
                "Gamma Exposure",
                SignalDirection.NEUTRAL,
                "Net GEX positive (%.0f) — dealers likely dampening moves, range-bound expected.".format(gex.netGex)
            )
        }

        val momentum = TickWindow.endpoints(spotTicks, WINDOW_MS)
        val momentumPercent = momentum?.let { (it.newest.ltp - it.oldest.ltp) / it.oldest.ltp * 100.0 }
        val direction = when {
            momentumPercent == null -> SignalDirection.NEUTRAL
            momentumPercent > 0.05 -> SignalDirection.BULLISH
            momentumPercent < -0.05 -> SignalDirection.BEARISH
            else -> SignalDirection.NEUTRAL
        }
        val reason = if (direction == SignalDirection.NEUTRAL || momentumPercent == null) {
            "Net GEX negative (%.0f) but no clear momentum yet — squeeze risk without a direction.".format(gex.netGex)
        } else {
            "Net GEX negative (%.0f) + %.2f%% momentum — dealer hedging could accelerate this move.".format(
                gex.netGex, momentumPercent
            )
        }
        return IndicatorSignal("Gamma Exposure", direction, reason)
    }

    /**
     * [ceView]/[peView] are already NIFTY-direction votes (PE inverted by the caller) —
     * true = bullish, false = bearish, null = no read for that side. Two present sides that
     * disagree land on NEUTRAL rather than being forced into a false consensus.
     */
    private fun combineViews(ceView: Boolean?, peView: Boolean?): SignalDirection = when {
        ceView == true && peView == true -> SignalDirection.BULLISH
        ceView == false && peView == false -> SignalDirection.BEARISH
        ceView != null && peView != null -> SignalDirection.NEUTRAL // present but disagree
        ceView == true || peView == true -> SignalDirection.BULLISH
        ceView == false || peView == false -> SignalDirection.BEARISH
        else -> SignalDirection.NEUTRAL
    }

    private fun describeQuadrant(label: QuadrantLabel): String = when (label) {
        QuadrantLabel.LONG_BUILDUP -> "Long Buildup"
        QuadrantLabel.SHORT_COVERING -> "Short Covering"
        QuadrantLabel.SHORT_BUILDUP -> "Short Buildup"
        QuadrantLabel.LONG_UNWINDING -> "Long Unwinding"
        QuadrantLabel.FLAT -> "Flat"
    }
}
