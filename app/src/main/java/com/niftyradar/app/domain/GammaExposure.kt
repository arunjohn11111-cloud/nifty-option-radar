package com.niftyradar.app.domain

import com.niftyradar.app.model.LockedContract
import com.niftyradar.app.model.RadarSession
import com.niftyradar.app.storage.LiveTickEntity

/**
 * Approximate net Gamma Exposure (GEX) across every locked strike, per PROJECT_SPEC.md's
 * 6-indicator design and the Gamma Squeeze concept discussed while designing it: in a
 * NEGATIVE gamma regime, dealers hedging their option book must buy as price rises and sell
 * as it falls, which accelerates whatever move is already underway; in a POSITIVE gamma
 * regime dealers do the opposite (buy dips, sell rallies), dampening moves into a range.
 *
 * CAVEAT this app cannot get around: real dealer positioning (who is actually net long/short
 * each strike) isn't observable from OI alone — OI only counts open contracts, not which
 * side is "the dealer". This uses the standard retail-trader approximation: call OI treated
 * as dealer short-gamma exposure (positive contribution to net GEX), put OI treated as
 * dealer long-gamma exposure (negative contribution):
 *
 *   netGex = sum over strikes[ (callGamma * callOI) - (putGamma * putOI) ] * spot^2 * 0.01
 *
 * This is a widely used first-pass formula, not a certainty — a real desk's actual
 * positioning can differ. Good enough to flag "is this a regime where a move could run away
 * from me", not precise enough to trade on the raw number itself. Because the SIGN is the
 * reliable part of this approximation and the exact magnitude isn't, [IndicatorEngine] only
 * asks "which side of zero" rather than calibrating a magnitude threshold.
 */
data class GammaExposureResult(val netGex: Double)

object GammaExposure {
    fun compute(
        latestByInstrument: Map<String, LiveTickEntity>,
        contracts: Map<String, LockedContract>,
        strikes: List<Double>,
        spot: Double
    ): GammaExposureResult? {
        var total = 0.0
        var sawAny = false
        for (strike in strikes) {
            val ceKey = contracts[RadarSession.contractKey(strike, "CE")]?.instrumentKey
            val peKey = contracts[RadarSession.contractKey(strike, "PE")]?.instrumentKey
            val ce = ceKey?.let { latestByInstrument[it] }
            val pe = peKey?.let { latestByInstrument[it] }
            val ceGamma = ce?.gamma
            val ceOi = ce?.openInterest
            val peGamma = pe?.gamma
            val peOi = pe?.openInterest
            if (ceGamma != null && ceOi != null) {
                total += ceGamma * ceOi
                sawAny = true
            }
            if (peGamma != null && peOi != null) {
                total -= peGamma * peOi
                sawAny = true
            }
        }
        if (!sawAny) return null
        return GammaExposureResult(total * spot * spot * 0.01)
    }
}
