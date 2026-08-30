package com.niftyradar.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niftyradar.app.storage.LiveTickEntity

/** Narrowest a card is allowed to get on the TV "fit everything" layout before we'd rather add
 *  another row than squeeze columns further. */
private val MIN_CARD_WIDTH_TV = 150.dp

/** Never go past this many columns even on a very wide screen — beyond this a chart is too
 *  thin to read at all, so we accept another row instead. */
private const val MAX_TV_COLUMNS = 8

/** Rough estimate of everything in a card BESIDES the chart canvas itself — the label line, the
 *  High/Low (and OI High/OI Low) text rows above and below the canvas, the buy/sell pressure
 *  strip (label + bar) most option-contract cards also show, and the card's own padding. Used
 *  only to guess how tall each row's chart canvas can be while still making all rows fit the
 *  screen with no scrolling — see the TV branch in [ChartGrid] below. This is a planning
 *  estimate, not a hard layout constraint, so a slightly-off guess just means the grid is a
 *  little short of or past a perfect edge-to-edge fit (or, for the one NIFTY 50 spot card that
 *  has no buy/sell strip, a little extra blank space), never a crash. */
private val CARD_CHROME_HEIGHT = 180.dp // bumped 160->180dp for the added Greeks readout row

/** Smallest and largest a chart canvas is allowed to shrink/grow to on the TV "fit everything"
 *  layout. Below the minimum a chart stops being readable; above the maximum (the same 160dp
 *  every other screen already uses) there's no point growing further. */
private val MIN_TV_CHART_HEIGHT = 60.dp
private val MAX_TV_CHART_HEIGHT = 160.dp

/** How tall the single enlarged chart is when a card has been selected — see [ChartDetailView]. */
private val DETAIL_CHART_HEIGHT = 360.dp

/**
 * TV support: lays a list of (label, instrument key) chart entries out as a
 * responsive grid of small chart cards instead of one long vertical list —
 * used by Phase 7, Phase 9, and Phase 10's screens in place of their old
 * "for (item in items) { HorizontalDivider(); Text(...); LiveTickChart(...) }"
 * loop.
 *
 * Two different layouts, chosen purely from the available width:
 *
 * - Narrow (phone): a single scrollable column, one full-size chart per row — exactly the
 *   original phone behavior, completely untouched by anything below. No selection, no
 *   detail view; phone already reaches every chart fine by touch-scrolling.
 *
 * - Wide (TV): instead of scrolling, every row is sized to fit the screen's actual height at
 *   once — see the per-card chart height math below — so all 22/23 charts are visible
 *   simultaneously with no scrolling needed at all, matching how a "radar" screen is meant to
 *   be glanced at on a big screen. Tapping/selecting a card (via the TV remote's D-pad or the
 *   Google TV phone app) swaps the whole grid for one enlarged [ChartDetailView] of just that
 *   contract; the remote's Back button (or an on-screen "← Back to full radar") returns to the
 *   full grid.
 *
 * [displayMode] is forwarded to every chart unchanged — one screen-level toggle switches
 * price/OI/both for the whole grid (and the detail view) at once.
 */
@Composable
fun <T> ChartGrid(
    items: List<T>,
    label: (T) -> String,
    instrumentKey: (T) -> String,
    ticksByInstrument: Map<String, List<LiveTickEntity>>,
    displayMode: ChartDisplayMode = ChartDisplayMode.Both
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let { items.getOrNull(it) }

    if (selected != null) {
        // A hardware/remote Back press while a detail chart is open should return to the grid,
        // not leave the whole phase screen — there's no other BackHandler anywhere above this
        // in the app (screen-to-screen navigation is a plain onBack lambda, not a back stack),
        // so this is safe to install unconditionally while selected != null.
        BackHandler { selectedIndex = null }
        ChartDetailView(
            label = label(selected),
            ticks = ticksByInstrument[instrumentKey(selected)] ?: emptyList(),
            displayMode = displayMode,
            onBack = { selectedIndex = null }
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val colsByWidth = (maxWidth / MIN_CARD_WIDTH_TV).toInt().coerceIn(1, MAX_TV_COLUMNS)

        if (colsByWidth <= 1) {
            // Phone: unchanged from before this feature — single column, full-size charts,
            // relies on the screen's own outer verticalScroll to reach every row.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (entry in items) {
                    ChartCard(
                        label = label(entry),
                        ticks = ticksByInstrument[instrumentKey(entry)] ?: emptyList(),
                        displayMode = displayMode,
                        chartHeight = MAX_TV_CHART_HEIGHT,
                        onClick = null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            // TV: pick a chart height that makes every row fit maxHeight with no scrolling.
            val columnCount = colsByWidth
            val rows = items.withIndex().toList().chunked(columnCount)
            val rowSpacing = 12.dp
            val totalSpacing = rowSpacing * (rows.size - 1).coerceAtLeast(0)
            val availableForCharts = (maxHeight - totalSpacing - CARD_CHROME_HEIGHT * rows.size)
                .coerceAtLeast(0.dp)
            val chartHeight = if (rows.isEmpty()) {
                MAX_TV_CHART_HEIGHT
            } else {
                (availableForCharts / rows.size).coerceIn(MIN_TV_CHART_HEIGHT, MAX_TV_CHART_HEIGHT)
            }

            Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
                for (row in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        for ((index, entry) in row) {
                            ChartCard(
                                label = label(entry),
                                ticks = ticksByInstrument[instrumentKey(entry)] ?: emptyList(),
                                displayMode = displayMode,
                                chartHeight = chartHeight,
                                onClick = { selectedIndex = index },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Pad a short last row with empty weighted spacers so
                        // earlier full rows' columns still line up underneath it.
                        if (row.size < columnCount) {
                            repeat(columnCount - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One chart card — used for both the phone single-column list and every cell of the TV grid.
 *
 * TV D-pad reachability: [Modifier.focusable] (always applied) is what lets a TV remote's D-pad
 * land on a card at all — without it, focus has nowhere to go once it runs out of actual buttons
 * above the grid, which is exactly what caused the original "only the first few charts are
 * reachable" bug this replaced. When [onClick] is non-null (the TV grid path), the whole card is
 * also selectable — pressing the remote's center/select button opens [ChartDetailView] for it.
 * The border color swap is just a visible "this card has focus" cue for the TV viewer.
 */
@Composable
private fun ChartCard(
    label: String,
    ticks: List<LiveTickEntity>,
    displayMode: ChartDisplayMode,
    chartHeight: Dp,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusBorder = if (isFocused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null

    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            LiveTickChart(
                ticks = ticks,
                modifier = Modifier.fillMaxWidth(),
                displayMode = displayMode,
                chartHeight = chartHeight
            )
        }
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            border = focusBorder,
            interactionSource = interactionSource
        ) { content() }
    } else {
        Card(
            modifier = modifier.focusable(interactionSource = interactionSource),
            border = focusBorder
        ) { content() }
    }
}

/**
 * The enlarged single-chart view shown after selecting a card in the TV grid — see [ChartGrid].
 * [onBack] is wired to both the on-screen text button and (by the caller) a [BackHandler], so
 * either the remote's own Back key or this button return to the full grid the same way.
 */
@Composable
private fun ChartDetailView(
    label: String,
    ticks: List<LiveTickEntity>,
    displayMode: ChartDisplayMode,
    onBack: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back to full radar") }
        }
        Text(label, style = MaterialTheme.typography.titleLarge)
        LiveTickChart(
            ticks = ticks,
            modifier = Modifier.fillMaxWidth(),
            displayMode = displayMode,
            chartHeight = DETAIL_CHART_HEIGHT
        )
    }
}

/**
 * The "Both / Price / OI" row shown above a [ChartGrid] on Phase 7, 9, and
 * 10 — one shared toggle switches every chart on the screen together.
 * [current] and [onSelect] are hoisted so each screen keeps its own
 * `remember { mutableStateOf(...) }` state (this composable holds none).
 */
@Composable
fun ChartDisplayModeToggle(current: ChartDisplayMode, onSelect: (ChartDisplayMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = listOf(
            ChartDisplayMode.Both to "Both",
            ChartDisplayMode.PriceOnly to "Price",
            ChartDisplayMode.OiOnly to "OI"
        )
        for ((mode, label) in options) {
            if (mode == current) {
                Button(onClick = { onSelect(mode) }) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(mode) }) { Text(label) }
            }
        }
    }
}
