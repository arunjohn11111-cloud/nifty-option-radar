package com.niftyradar.app.domain

import com.niftyradar.app.storage.LiveTickEntity

/**
 * TBQ vs TSQ (the exchange's own aggregate pending buy/sell order quantity) for a single
 * contract, per PROJECT_SPEC.md's 6-indicator design — a direct order-book pressure
 * reading, unlike OI which only reflects already-settled positions. More buy quantity
 * waiting than sell quantity is read as bullish FOR THIS INSTRUMENT'S own price, and vice
 * versa; see [IndicatorEngine] for how ATM CE/PE are combined into one NIFTY-direction vote
 * (with PE inverted, same reasoning as [OiPriceQuadrant]).
 */
data class OrderFlowResult(
    val totalBuyQuantity: Double,
    val totalSellQuantity: Double,
    val imbalanceRatio: Double // (tbq - tsq) / (tbq + tsq), -1..+1
) {
    /** true = bullish for this instrument's OWN price, false = bearish, null = balanced/no read. */
    val bullishForOwnPrice: Boolean? = when {
        imbalanceRatio > IMBALANCE_THRESHOLD -> true
        imbalanceRatio < -IMBALANCE_THRESHOLD -> false
        else -> null
    }

    companion object {
        /** Below this, buy/sell pressure is treated as roughly balanced rather than a real tilt. */
        const val IMBALANCE_THRESHOLD = 0.15
    }
}

object OrderFlowImbalance {
    fun latest(ticks: List<LiveTickEntity>): OrderFlowResult? {
        val tick = ticks.lastOrNull { it.totalBuyQuantity != null && it.totalSellQuantity != null }
            ?: return null
        val tbq = tick.totalBuyQuantity!!
        val tsq = tick.totalSellQuantity!!
        val total = tbq + tsq
        if (total <= 0.0) return null
        return OrderFlowResult(tbq, tsq, (tbq - tsq) / total)
    }
}
