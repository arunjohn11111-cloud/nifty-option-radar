package com.niftyradar.app.domain

/**
 * One of the 6-indicator dashboard's votes (PROJECT_SPEC.md's 6-indicator design). The user
 * explicitly wants BOTH a colored arrow AND a short text reason together, never one instead
 * of the other — [direction] drives the arrow, [reason] is the text. ATR is deliberately NOT
 * represented here: it never votes bullish/bearish (see [AverageTrueRange]'s doc comment),
 * it only sizes Target/SL, so it's shown separately, not as one of these signals.
 */
enum class SignalDirection { BULLISH, BEARISH, NEUTRAL }

data class IndicatorSignal(
    val name: String,
    val direction: SignalDirection,
    val reason: String
)
