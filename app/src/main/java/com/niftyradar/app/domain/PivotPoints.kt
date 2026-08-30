package com.niftyradar.app.domain

/**
 * Classic (floor-trader) pivot points, computed once per session from the
 * previous completed trading day's daily candle. Per PROJECT_SPEC.md's
 * 6-indicator design, only the Pivot Points indicator's own R1/S1 (plus the
 * pivot itself) are shown in the dashboard, but R2/S2 are computed too since
 * the standard formula gives them for free.
 */
data class PivotLevels(
    val pivot: Double,
    val r1: Double,
    val r2: Double,
    val s1: Double,
    val s2: Double
)

object PivotPoints {
    fun classic(previousHigh: Double, previousLow: Double, previousClose: Double): PivotLevels {
        val pivot = (previousHigh + previousLow + previousClose) / 3.0
        val range = previousHigh - previousLow
        return PivotLevels(
            pivot = pivot,
            r1 = (2.0 * pivot) - previousLow,
            r2 = pivot + range,
            s1 = (2.0 * pivot) - previousHigh,
            s2 = pivot - range
        )
    }
}
