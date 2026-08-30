package com.niftyradar.app.domain

import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.storage.LiveTickEntity

/**
 * Combines the individual indicator calculators into the dashboard's ordered list of votes,
 * per PROJECT_SPEC.md's 6-indicator design. Currently computes 4 of the eventual 6 — Trend
 * (9/21 EMA) needs 15-min candle history this app doesn't fetch yet, so it's left out until
 * that's built; ATR is never in this list at all, since it doesn't vote (see
 * [IndicatorSignal]'s doc comment) — it's shown separately for Target/SL sizing.
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

    fun evaluate(
        session: RadarSession,
        ticksByInstrument: Map<String, List<LiveTickEntity>>,
        spotTicks: List<LiveTickEntity>,
        pivots: PivotLevels
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
