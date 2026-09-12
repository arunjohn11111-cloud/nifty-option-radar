package com.niftyradar.app.domain

import com.niftyradar.app.storage.LiveTickEntity
import java.util.Calendar
import java.util.TimeZone

/**
 * Which slice of a day's recorded ticks a chart should draw.
 *
 * This exists because "everything recorded" was the wrong default and the cost was visible: a
 * device that had the app open from 10:55 in the morning until nearly midnight produced a chart
 * whose time axis ran 10:55 to 23:51. Almost thirteen hours, of which six and a half were the
 * trading session and the rest was a closed market sending the occasional heartbeat. The whole
 * day's real price action was squeezed into under half the width, and the flat dead stretch got
 * the same space as the part anyone wanted to look at.
 *
 * No selector can fix that on its own, because a selector has a default and the default is what
 * almost every glance actually sees. So the default here is [Session] — the trading day, not the
 * recording — and the shorter windows exist for the question a live screen usually asks, which
 * is "what has happened in the last few minutes", not "what happened since I opened the app".
 */
enum class ChartWindow {
    Last15m,
    LastHour,

    /** The trading session only: ticks whose IST clock time falls inside market hours. */
    Session;

    val label: String
        get() = when (this) {
            Last15m -> "15m"
            LastHour -> "1h"
            Session -> "Session"
        }
}

/**
 * The result of narrowing a series, carrying WHY it came out the way it did.
 *
 * [note] is the reason this is a type rather than a bare list. When a window cannot be honoured
 * — a Saturday where no tick falls inside market hours, a fifteen-minute window over a series
 * that spans one minute — the tempting move is to quietly widen it and draw something. That is
 * exactly how the thirteen-hour axis came to look reasonable. A chart that had to widen its
 * window says so instead.
 */
data class WindowedTicks(
    val ticks: List<LiveTickEntity>,
    val note: String?
)

object ChartWindows {

    /**
     * NSE equity-derivatives session in minutes past IST midnight: 09:15 to 15:40.
     *
     * 15:40 rather than 15:30 — NSE extended the derivatives close by ten minutes on
     * 3 August 2026 to align with the cash segment's new closing auction. Pre-open (09:00 to
     * 09:15) is deliberately OUTSIDE this range: the auction's indicative prices are not trades
     * and putting them on the same line as traded prices would misrepresent both.
     */
    const val MARKET_OPEN_MINUTE = 9 * 60 + 15
    const val MARKET_CLOSE_MINUTE = 15 * 60 + 40

    private const val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L
    private const val ONE_HOUR_MS = 60 * 60 * 1000L

    /** Below two points there is no line to draw, so no window is worth applying. */
    private const val MIN_USABLE = 2

    private fun minuteOfDayIst(millis: Long): Int {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        calendar.timeInMillis = millis
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    /** True when this tick arrived between the open and the close, IST. */
    fun isDuringMarketHours(tick: LiveTickEntity): Boolean {
        val minute = minuteOfDayIst(tick.receivedAtMillis)
        return minute >= MARKET_OPEN_MINUTE && minute <= MARKET_CLOSE_MINUTE
    }

    /**
     * Narrows [ticks] to [window], anchored on the NEWEST TICK rather than on the current time.
     *
     * Anchoring on the newest tick is what makes the short windows usable at all outside market
     * hours. Anchored on "now", "last 15 minutes" on a Saturday evening selects a stretch in
     * which nothing arrived and draws an empty chart — technically correct and useless. Anchored
     * on the last tick, it always shows the most recent fifteen minutes that contain something,
     * and during live trading the two anchors are the same thing anyway.
     */
    fun apply(window: ChartWindow, ticks: List<LiveTickEntity>): WindowedTicks {
        if (ticks.size < MIN_USABLE) return WindowedTicks(ticks, null)

        val sessionTicks = ticks.filter { isDuringMarketHours(it) }
        // Everything starts from the session, including the short windows: fifteen minutes of a
        // closed market is fifteen minutes of nothing happening, and no one asking for the last
        // fifteen minutes wants that.
        // Two separate notes, because they are not equally disposable.
        //
        // [fallbackNote] says the series is not session data at all. That survives every
        // narrowing below: a viewer looking at a fifteen-minute window of a closed market must
        // still be told that is what they are looking at. An earlier version dropped it as soon
        // as a short window was applied, which is precisely the case where it matters most.
        //
        // [droppedNote] is only a count, and only interesting when the whole session is on
        // screen. Once a short window is active it is the window that decides what is shown,
        // and repeating how many out-of-hours ticks exist would explain the wrong thing.
        val base: List<LiveTickEntity>
        val fallbackNote: String?
        val droppedNote: String?
        if (sessionTicks.size >= MIN_USABLE) {
            base = sessionTicks
            fallbackNote = null
            val dropped = ticks.size - sessionTicks.size
            droppedNote = if (dropped > 0) {
                "Market hours only — $dropped tick(s) from outside 09:15–15:40 left out."
            } else {
                null
            }
        } else {
            base = ticks
            fallbackNote = "No ticks inside market hours yet, so this shows everything recorded."
            droppedNote = null
        }

        fun note(vararg parts: String?): String? =
            parts.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" ")

        if (window == ChartWindow.Session) return WindowedTicks(base, note(fallbackNote, droppedNote))

        val spanMillis = if (window == ChartWindow.Last15m) FIFTEEN_MINUTES_MS else ONE_HOUR_MS
        val newest = base.maxOf { it.receivedAtMillis }
        val narrowed = base.filter { it.receivedAtMillis >= newest - spanMillis }
        if (narrowed.size < MIN_USABLE) {
            return WindowedTicks(
                base,
                note(
                    fallbackNote,
                    "Not enough ticks in the last ${window.label} to draw — showing everything above instead."
                )
            )
        }
        return WindowedTicks(narrowed, note(fallbackNote))
    }
}
