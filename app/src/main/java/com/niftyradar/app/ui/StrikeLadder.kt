package com.niftyradar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.niftyradar.app.domain.PivotLevels
import com.niftyradar.app.storage.LiveTickEntity
import kotlin.math.abs

/** One strike's pair of contracts, as the ladder reads them. */
data class LadderRung(
    val strike: Double,
    val ceKey: String?,
    val peKey: String?
)

/**
 * How the ladder is ordered. [Ladder] is the default and the reason is not inertia: strikes
 * ascending is the spatial map an options trader has already internalised from every option
 * chain they have ever read, and breaking it to be clever costs more than it gains. The other
 * two exist to answer one question the ladder order cannot — "which of these matters right
 * now" — without reading all twenty-two of them.
 */
enum class LadderSort { Ladder, MostActive, BiggestOiChange }

private val ATM_TINT = Color(0x146750A4)
private val ATM_EDGE = Color(0xFF6750A4)
private val ROW_MUTED = Color(0xFF8A8A93)
private val UP_COLOR = Color(0xFF2E7D32)
private val DOWN_COLOR = Color(0xFFC62828)
private val SPARK_COLOR = Color(0xFF6750A4)

private val SPARK_HEIGHT = 26.dp
private val STRIKE_COLUMN_WIDTH = 62.dp

/** Percent change of a series from its first reading to its last, or null without both. */
private fun percentChange(first: Double?, last: Double?): Double? {
    if (first == null || last == null || first == 0.0) return null
    return (last - first) / abs(first) * 100.0
}

private fun firstOi(ticks: List<LiveTickEntity>): Double? =
    ticks.firstNotNullOfOrNull { it.openInterest }

private fun lastOi(ticks: List<LiveTickEntity>): Double? =
    ticks.asReversed().firstNotNullOfOrNull { it.openInterest }

private fun formatMillions(value: Double): String = "%.2fM".format(value / 1_000_000.0)

/**
 * The ladder's compact sparkline: shape only, no axis, no labels.
 *
 * Deliberately unlabelled, unlike [LiveTickChart]. A row's job is to answer "is this one moving
 * at all, and which way" in a glance; the moment a precise number is wanted, the row is tapped
 * and a real chart with real axes opens underneath it. Putting half an axis here would imply a
 * precision 26dp of height cannot deliver.
 */
@Composable
private fun Sparkline(ticks: List<LiveTickEntity>, modifier: Modifier = Modifier) {
    if (ticks.size < 2) {
        Row(modifier = modifier.height(SPARK_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            Text("—", style = MaterialTheme.typography.labelSmall, color = ROW_MUTED)
        }
        return
    }
    val low = ticks.minOf { it.ltp }
    val high = ticks.maxOf { it.ltp }
    // A price that has not moved is the common case out of hours and in a quiet strike, and it
    // used to render at y = height: a 2px stroke half of which fell outside the canvas, so the
    // one thing the row most needed to say — "this is flat" — was the one thing it drew worst.
    // A flat series is drawn down the middle instead, which reads as flat and stays visible.
    val flat = high - low <= 0.0
    val span = (high - low).takeIf { it > 0.0 } ?: 1.0
    val firstTime = ticks.minOf { it.receivedAtMillis }
    val lastTime = ticks.maxOf { it.receivedAtMillis }
    val timeSpan = (lastTime - firstTime).takeIf { it > 0L } ?: 1L

    Canvas(modifier = modifier.height(SPARK_HEIGHT)) {
        val path = Path()
        ticks.sortedBy { it.receivedAtMillis }.forEachIndexed { index, tick ->
            val x = size.width * (tick.receivedAtMillis - firstTime).toFloat() / timeSpan.toFloat()
            val y = if (flat) size.height / 2f
            else (size.height * (1.0 - (tick.ltp - low) / span)).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, SPARK_COLOR, style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}

/**
 * One side of a rung: price, its move, OI and the OI move, plus the sparkline.
 *
 * LAYOUT IS LOAD-BEARING HERE, and the first version got it wrong. Side, price and percent
 * were laid out on ONE line; at half a phone's width, with the system font scaled up even
 * slightly, that line had nowhere to go and wrapped mid-number — "CE 337.00+6." on one line
 * and "5%" on the next, "-12.5" above a stray "%". On every row, twenty-two times.
 *
 * So no line here carries more than two short items, and every one of them is [maxLines] = 1.
 * The header line pairs the side label with the percent move (about seven characters between
 * them, which fits at any font scale a phone offers); the price gets a line of its own, which
 * is also the right emphasis, since it is the number a glance is looking for. Nothing here
 * relies on a measurement that can change under a device setting.
 */
@Composable
private fun RungSide(
    side: String,
    ticks: List<LiveTickEntity>,
    modifier: Modifier = Modifier
) {
    val last = ticks.maxByOrNull { it.receivedAtMillis }
    val move = percentChange(last?.closePrice, last?.ltp)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                side,
                style = MaterialTheme.typography.labelSmall,
                color = if (side == "CE") UP_COLOR else DOWN_COLOR,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (move != null) {
                Text(
                    "%+.1f%%".format(move),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (move >= 0) UP_COLOR else DOWN_COLOR,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        Text(
            if (last != null) "%.2f".format(last.ltp) else "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Sparkline(ticks, modifier = Modifier.fillMaxWidth())
        val oi = lastOi(ticks)
        val oiMove = percentChange(firstOi(ticks), oi)
        Text(
            if (oi != null) formatMillions(oi) else "—",
            style = MaterialTheme.typography.labelSmall,
            color = ROW_MUTED,
            maxLines = 1
        )
        if (oiMove != null) {
            Text(
                "%+.1f%%".format(oiMove),
                style = MaterialTheme.typography.labelSmall,
                color = if (oiMove >= 0) UP_COLOR else DOWN_COLOR,
                maxLines = 1
            )
        }
    }
}

/**
 * The anchor strip: NIFTY 50 itself, and how far it is from the levels that matter.
 *
 * Distance rather than only the level. "R1 23,487" needs arithmetic before it means anything;
 * "R1 · +89" is the answer that arithmetic was for, and it is the form the question is actually
 * asked in.
 */
@Composable
private fun SpotStrip(spotTicks: List<LiveTickEntity>, pivots: PivotLevels?) {
    val last = spotTicks.maxByOrNull { it.receivedAtMillis }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ATM_TINT, RoundedCornerShape(10.dp))
            .border(1.dp, ATM_EDGE, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "NIFTY 50",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (last != null) "  %.2f".format(last.ltp) else "  waiting for a tick",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            val change = if (last != null) last.ltp - last.closePrice else null
            val changePct = percentChange(last?.closePrice, last?.ltp)
            if (change != null && changePct != null) {
                Text(
                    "  %+.2f (%+.2f%%)".format(change, changePct),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (change >= 0) UP_COLOR else DOWN_COLOR,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Sparkline(spotTicks, modifier = Modifier.fillMaxWidth())
        if (pivots != null && last != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "S1 %.0f · %+.0f".format(pivots.s1, last.ltp - pivots.s1),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    "P %.0f".format(pivots.pivot),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    "R1 %.0f · %+.0f".format(pivots.r1, pivots.r1 - last.ltp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** The sort chips above the ladder. */
@Composable
private fun LadderSortChips(current: LadderSort, onSelect: (LadderSort) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for (option in LadderSort.values()) {
            val selected = option == current
            Text(
                text = when (option) {
                    LadderSort.Ladder -> "Ladder order"
                    LadderSort.MostActive -> "Most active"
                    LadderSort.BiggestOiChange -> "OI change"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .background(
                        if (selected) ATM_EDGE else Color.Transparent,
                        RoundedCornerShape(50)
                    )
                    .border(
                        1.dp,
                        if (selected) ATM_EDGE else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(50)
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * The whole phone arrangement, emitted as LazyColumn items.
 *
 * A LazyListScope extension rather than one composable on purpose. The screen must be ONE
 * scroll — no scrollable inside a scrollable — and it must not compose twenty-two live charts
 * that are off screen. Both of those need the rows to be real lazy items belonging to the
 * screen's own list, which a composable returning a Column could not be.
 *
 * THREE TIERS, because the charts were never equally important and treating them as a flat
 * list of twenty-three was the actual problem:
 *
 *  1. Spot, first. Every other chart is read as a distance from it.
 *  2. The pair at the LIVE at-the-money strike, as two real charts. That is where a trade
 *     happens, and it is the only place chart detail earns its height.
 *  3. Every strike as one compact rung — call on the left, strike in the middle, put on the
 *     right — tapped to open a full chart in place. This is the option chain's own shape,
 *     which is already familiar, and it puts a strike's call and put side by side: the
 *     comparison most often wanted, and one the old flat list and the TV grid both broke.
 *
 * [atmStrike] is computed by the caller from LIVE spot, never from the strike that was at the
 * money when the session locked. That distinction was a real bug in this app once, where two
 * of five voting indicators spent a morning reading a contract 140 points away from the money.
 */
fun LazyListScope.strikeLadder(
    rungs: List<LadderRung>,
    spotTicks: List<LiveTickEntity>,
    ticksByInstrument: Map<String, List<LiveTickEntity>>,
    pivots: PivotLevels?,
    atmStrike: Double?,
    displayMode: ChartDisplayMode,
    sort: LadderSort,
    onSortChange: (LadderSort) -> Unit,
    expandedStrike: Double?,
    onToggleStrike: (Double) -> Unit
) {
    item(key = "ladder-spot") {
        SpotStrip(spotTicks = spotTicks, pivots = pivots)
    }

    val atmRung = rungs.firstOrNull { it.strike == atmStrike }
    if (atmRung != null) {
        item(key = "ladder-atm-header") {
            Text(
                "At the money · %.0f".format(atmRung.strike),
                style = MaterialTheme.typography.labelSmall,
                color = ROW_MUTED,
                fontWeight = FontWeight.Bold
            )
        }
        item(key = "ladder-atm-pair") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Said once for the pair rather than once per chart. Printed under each chart
                // it took three wrapped lines twice over, and the two charts it was explaining
                // were pushed off the screen by their own legend.
                Text(
                    "Bars below each chart: buy vs sell quantity (green = buy, red = sell). " +
                        "Δ and Θ are this contract's live Greeks.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ROW_MUTED
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CE", style = MaterialTheme.typography.labelSmall, color = UP_COLOR, fontWeight = FontWeight.Bold)
                        LiveTickChart(
                            ticks = atmRung.ceKey?.let { ticksByInstrument[it] } ?: emptyList(),
                            modifier = Modifier.fillMaxWidth(),
                            displayMode = displayMode,
                            chartHeight = 120.dp,
                            compact = true
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PE", style = MaterialTheme.typography.labelSmall, color = DOWN_COLOR, fontWeight = FontWeight.Bold)
                        LiveTickChart(
                            ticks = atmRung.peKey?.let { ticksByInstrument[it] } ?: emptyList(),
                            modifier = Modifier.fillMaxWidth(),
                            displayMode = displayMode,
                            chartHeight = 120.dp,
                            compact = true
                        )
                    }
                }
            }
        }
    }

    item(key = "ladder-sort") {
        LadderSortChips(current = sort, onSelect = onSortChange)
    }

    item(key = "ladder-header") {
        Column {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Call", style = MaterialTheme.typography.labelSmall, color = ROW_MUTED, modifier = Modifier.weight(1f))
                Text(
                    "Strike",
                    style = MaterialTheme.typography.labelSmall,
                    color = ROW_MUTED,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(STRIKE_COLUMN_WIDTH)
                )
                Text("Put", style = MaterialTheme.typography.labelSmall, color = ROW_MUTED, modifier = Modifier.weight(1f))
            }
            // The rows themselves carry no field labels — at half a phone's width a label costs
            // more room than the number it names. Saying the order once, here, is what buys the
            // rows that room.
            Text(
                "Each side, top to bottom: % move since yesterday's close · price · " +
                    "open interest · OI change",
                style = MaterialTheme.typography.labelSmall,
                color = ROW_MUTED
            )
        }
    }

    // "Activity" is measured on the pair, not on one leg: a strike is interesting when either
    // side is moving, and taking the larger of the two is what keeps a busy put from being
    // hidden behind a quiet call.
    fun ticksFor(rung: LadderRung, side: String): List<LiveTickEntity> {
        val key = if (side == "CE") rung.ceKey else rung.peKey
        return key?.let { ticksByInstrument[it] } ?: emptyList()
    }
    fun priceActivity(rung: LadderRung): Double = listOf("CE", "PE").maxOf { side ->
        val ticks = ticksFor(rung, side)
        val last = ticks.maxByOrNull { it.receivedAtMillis }
        abs(percentChange(last?.closePrice, last?.ltp) ?: 0.0)
    }
    fun oiActivity(rung: LadderRung): Double = listOf("CE", "PE").maxOf { side ->
        val ticks = ticksFor(rung, side)
        abs(percentChange(firstOi(ticks), lastOi(ticks)) ?: 0.0)
    }

    val ordered = when (sort) {
        LadderSort.Ladder -> rungs.sortedBy { it.strike }
        LadderSort.MostActive -> rungs.sortedByDescending { priceActivity(it) }
        LadderSort.BiggestOiChange -> rungs.sortedByDescending { oiActivity(it) }
    }

    items(ordered.size, key = { index -> "rung-${ordered[index].strike}" }) { index ->
        val rung = ordered[index]
        val isAtm = rung.strike == atmStrike
        val expanded = rung.strike == expandedStrike
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isAtm) ATM_TINT else Color.Transparent,
                    RoundedCornerShape(8.dp)
                )
                .clickable { onToggleStrike(rung.strike) }
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RungSide("CE", ticksFor(rung, "CE"), modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.width(STRIKE_COLUMN_WIDTH),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "%.0f".format(rung.strike),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isAtm) ATM_EDGE else MaterialTheme.colorScheme.onSurface
                    )
                    if (isAtm) {
                        Text("ATM", style = MaterialTheme.typography.labelSmall, color = ATM_EDGE)
                    }
                }
                RungSide("PE", ticksFor(rung, "PE"), modifier = Modifier.weight(1f))
            }

            // Opened in place rather than on a new screen: the ladder above and below stays
            // put, so the strike being read keeps its context instead of replacing it.
            if (expanded) {
                Text(
                    "%.0f CE".format(rung.strike),
                    style = MaterialTheme.typography.labelSmall,
                    color = UP_COLOR,
                    fontWeight = FontWeight.Bold
                )
                LiveTickChart(
                    ticks = ticksFor(rung, "CE"),
                    modifier = Modifier.fillMaxWidth(),
                    displayMode = displayMode
                )
                Text(
                    "%.0f PE".format(rung.strike),
                    style = MaterialTheme.typography.labelSmall,
                    color = DOWN_COLOR,
                    fontWeight = FontWeight.Bold
                )
                LiveTickChart(
                    ticks = ticksFor(rung, "PE"),
                    modifier = Modifier.fillMaxWidth(),
                    displayMode = displayMode
                )
                Text(
                    "Tap the row again to close it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ROW_MUTED
                )
            }
            HorizontalDivider()
        }
    }
}
