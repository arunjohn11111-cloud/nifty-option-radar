package com.niftyradar.app.domain

/**
 * Pure logic, no Android/network dependencies on purpose — this is the piece
 * of the spec ("PROJECT_SPEC.md" section 10) that most needs to be gotten
 * exactly right and is the easiest to unit-test in isolation:
 *
 *   1. Given the spot price and the list of strikes Upstox actually returned
 *      for the expiry (never assume a fixed 50-point gap),
 *   2. find the nearest strike -> that's ATM,
 *   3. take 5 strikes below + ATM + 5 strikes above.
 *
 * If fewer than 5 strikes exist on either side (e.g. very near a listed
 * strike boundary), we take as many as are available on that side rather than
 * crashing, and report it via [RadarStrikeSelection.warnings] so the UI can
 * surface it instead of silently shipping fewer than 11 strikes.
 */
object RadarStrikeSelector {

    data class RadarStrikeSelection(
        val atmStrike: Double,
        val strikes: List<Double>, // ascending, ATM included, ideally size 11
        val warnings: List<String>
    )

    fun select(
        spotPrice: Double,
        availableStrikes: List<Double>,
        strikesEachSide: Int = 5
    ): RadarStrikeSelection {
        val warnings = mutableListOf<String>()
        val sorted = availableStrikes.distinct().sorted()

        require(sorted.isNotEmpty()) { "No strikes available to select a radar from." }

        val atmIndex = sorted.indices.minByOrNull { i -> kotlin.math.abs(sorted[i] - spotPrice) }!!
        val atmStrike = sorted[atmIndex]

        val fromIndex = (atmIndex - strikesEachSide).coerceAtLeast(0)
        val toIndex = (atmIndex + strikesEachSide).coerceAtMost(sorted.lastIndex)

        if (atmIndex - strikesEachSide < 0) {
            warnings += "Only ${atmIndex} strike(s) available below ATM " +
                "(wanted $strikesEachSide) — ATM is near the bottom of the option chain."
        }
        if (atmIndex + strikesEachSide > sorted.lastIndex) {
            warnings += "Only ${sorted.lastIndex - atmIndex} strike(s) available above ATM " +
                "(wanted $strikesEachSide) — ATM is near the top of the option chain."
        }

        val selected = sorted.subList(fromIndex, toIndex + 1)

        if (selected.size != 2 * strikesEachSide + 1) {
            warnings += "Radar has ${selected.size} strikes instead of the usual " +
                "${2 * strikesEachSide + 1} (see warnings above)."
        }

        return RadarStrikeSelection(atmStrike = atmStrike, strikes = selected, warnings = warnings)
    }

    /**
     * Not used until Phase 4/5 (needs a live spot feed to call it repeatedly), but it's a
     * one-line pure function so there's no reason not to have it ready: is the current spot
     * still inside the locked radar's [min strike, max strike] range, or should the UI show
     * "SPOT OUTSIDE RADAR RANGE" per spec section 3?
     */
    fun isSpotWithinRadar(spotPrice: Double, lockedStrikes: List<Double>): Boolean {
        if (lockedStrikes.isEmpty()) return true
        return spotPrice >= lockedStrikes.min() && spotPrice <= lockedStrikes.max()
    }
}
