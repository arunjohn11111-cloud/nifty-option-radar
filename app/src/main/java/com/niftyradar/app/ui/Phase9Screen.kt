package com.niftyradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalConfiguration
import com.niftyradar.app.network.UpstoxApiClient
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.niftyradar.app.domain.DashboardResult
import com.niftyradar.app.domain.IndicatorSignal
import com.niftyradar.app.domain.PanicAlertResult
import com.niftyradar.app.domain.SignalDirection
import com.niftyradar.app.storage.LiveTickEntity
import kotlin.math.abs
import kotlinx.coroutines.delay

private val BULLISH_COLOR = Color(0xFF2E7D32)
private val BEARISH_COLOR = Color(0xFFC62828)
private val NEUTRAL_COLOR = Color(0xFF757575)

/**
 * How often the auto-refresh loop below re-reads stored ticks while this screen is open.
 *
 * NOT yet reduced toward one second, and that is a measurement problem rather than a decision
 * not taken. Each pass re-reads every instrument's ENTIRE day from Room, so its cost grows
 * through the session — at one snapshot per instrument per second, by the close that is over
 * half a million rows per pass. Cutting the interval before the queries are windowed would
 * make a late-afternoon screen worse, not more live. The windowed version needs each
 * instrument's day-open baseline fetched separately, or "OI change since open" would quietly
 * become "OI change over the last few minutes" — a signal changing meaning without saying so.
 * See LiveTickStore.snapshotRate: measure the rate first, then size the window.
 */
private const val AUTO_REFRESH_INTERVAL_MS = 5_000L

/** Newer than this and the feed is unambiguously live. */
private const val LIVE_WITHIN_MS = 15_000L

/**
 * Older than this and something is probably wrong. Well above the roughly one-a-minute
 * heartbeat the exchange sends outside market hours, so a quiet Saturday does not raise it.
 */
private const val STALE_AFTER_MS = 150_000L

/**
 * PHASE 9 SCREEN: PROJECT_SPEC.md section 20 step 10 — the final combined
 * radar view. NIFTY 50 spot (Phase 8) plus all 22 locked option contracts
 * (Phase 7), all 23 charts on one screen. Reuses [LiveTickChart] unchanged.
 *
 * TV support: charts render via [ChartGrid] instead of one long vertical
 * list — a single column on a phone-width screen (unchanged), several
 * columns side by side on a wide TV screen, so the whole radar is visible
 * with much less scrolling.
 *
 * Also adds a [ChartDisplayModeToggle] (Both/Price/OI, applied to every
 * chart at once) and an auto-refresh loop — every [AUTO_REFRESH_INTERVAL_MS]
 * this screen re-reads whatever's newest in storage on its own. There is
 * deliberately NO refresh button: see [FeedFreshness] for why an age counter
 * replaced it.
 */
/**
 * Below this width the single-scroll chain ladder is used; at or above it, the TV's
 * fit-everything grid. Deliberately a plain dp number rather than ChartGrid's own card-width
 * constant, which is private to that file — and the choice here is about which ARRANGEMENT
 * suits the screen, not about how many cards happen to fit across it.
 */
private const val LADDER_MAX_WIDTH_DP = 700

@Composable
fun Phase9Screen(viewModel: Phase9ViewModel, onBack: () -> Unit, onContinueToPhase10: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val ticksByInstrument by viewModel.ticksByInstrument.collectAsState()
    val dailyLevels by viewModel.dailyLevels.collectAsState()
    val dashboard by viewModel.dashboard.collectAsState()
    val panicAlert by viewModel.panicAlert.collectAsState()
    var displayMode by remember { mutableStateOf(ChartDisplayMode.Both) }
    var ladderSort by remember { mutableStateOf(LadderSort.Ladder) }
    var expandedStrike by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(AUTO_REFRESH_INTERVAL_MS)
            viewModel.refreshAll()
        }
    }

    // ONE scroll for the whole screen, and a LAZY one.
    //
    // This was a Column inside verticalScroll, which meant all twenty-three chart canvases were
    // composed and drawn whether or not they were anywhere near the viewport — the phone got
    // warm holding a screen the user could only see a fifth of. A LazyColumn composes what is
    // visible and nothing else. It is also the only way to get the arrangement asked for: a
    // single continuous scroll, as many screens long as it needs to be, with no scrollable
    // nested inside another scrollable (which Compose cannot measure anyway).
    val narrow = LocalConfiguration.current.screenWidthDp < LADDER_MAX_WIDTH_DP
    val spotTicks = ticksByInstrument[UpstoxApiClient.NIFTY_50_INSTRUMENT_KEY] ?: emptyList()
    val livePivots = (dailyLevels as? DailyLevelsUiState.Ready)?.pivots

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "back") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
            }
        }

        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Phase 9 — Full Radar View", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Spot, the at-the-money pair, and every locked strike as one chain ladder — " +
                        "tap a strike to open its charts in place. Same chart component as every " +
                        "phase before it.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        when (val state = uiState) {
            is Phase9UiState.NoRadarLocked -> {
                item(key = "no-radar") {
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("No radar locked for today yet.", style = MaterialTheme.typography.titleMedium)
                            Text("Go back, lock today's radar, connect Phase 4's feed, and let a few ticks arrive first.")
                        }
                    }
                }
            }
            is Phase9UiState.Ready -> {
                item(key = "panic") { PanicAlertCard(panicAlert) }
                item(key = "freshness") { FeedFreshness(ticksByInstrument) }
                item(key = "levels") { DailyLevelsCard(dailyLevels) }
                item(key = "dashboard") { DashboardCard(dashboard) }
                item(key = "mode") {
                    ChartDisplayModeToggle(current = displayMode, onSelect = { displayMode = it })
                }

                if (narrow) {
                    // The at-the-money strike is taken from LIVE spot, never from the one that
                    // was at the money when the session locked. Reading the locked value was a
                    // real bug in this app, not a hypothetical one.
                    val atm = spotTicks.maxByOrNull { it.receivedAtMillis }?.ltp?.let { spot ->
                        state.rungs.minByOrNull { abs(it.strike - spot) }?.strike
                    }
                    strikeLadder(
                        rungs = state.rungs,
                        spotTicks = spotTicks,
                        ticksByInstrument = ticksByInstrument,
                        pivots = livePivots,
                        atmStrike = atm,
                        displayMode = displayMode,
                        sort = ladderSort,
                        onSortChange = { ladderSort = it },
                        expandedStrike = expandedStrike,
                        onToggleStrike = { strike ->
                            expandedStrike = if (expandedStrike == strike) null else strike
                        }
                    )
                } else {
                    // Wide screens (the TV) keep the fit-everything grid: there, all of them
                    // visible at once with no scrolling is the point.
                    item(key = "grid") {
                        ChartGrid(
                            items = state.items,
                            label = { it.label },
                            instrumentKey = { it.instrumentKey },
                            ticksByInstrument = ticksByInstrument,
                            displayMode = displayMode
                        )
                    }
                }
            }
        }

        item(key = "next") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider()
                Button(onClick = onContinueToPhase10, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue to Phase 10 — Session History →")
                }
            }
        }
    }
}

/**
 * How old the newest tick on screen is, counting up in place.
 *
 * Replaces a "Refresh now" button, and the removal is the point rather than a side effect: an
 * action the user can take to see current data implies the data on screen might not be
 * current, which turns every glance into a question. This screen re-reads storage on its own,
 * so the honest thing to show is not a button but the answer — how old what you are looking
 * at actually is.
 *
 * It also closes a real hole. Phase 9 had NO indication of whether the feed was connected, so
 * a chart frozen because the WebSocket had dropped looked exactly like a chart of a quiet
 * strike. The age of the newest tick distinguishes them, and nothing else on this screen did.
 *
 * Three bands, not a single threshold, because a single one would cry wolf: outside market
 * hours the exchange still sends roughly one heartbeat a minute, so "older than 15 seconds"
 * is perfectly normal on a Saturday and says nothing about the connection. Only a gap longer
 * than [STALE_AFTER_MS] is worth calling out, and even then it is worded as a likelihood.
 */
@Composable
private fun FeedFreshness(ticksByInstrument: Map<String, List<LiveTickEntity>>) {
    val newestTickMillis = remember(ticksByInstrument) {
        ticksByInstrument.values
            .mapNotNull { list -> list.maxOfOrNull { it.receivedAtMillis } }
            .maxOrNull()
    }

    // Counts up on its own once a second. Without this the label would only change when new
    // ticks arrived — so a dead feed would show a reassuring "3s ago" forever, which is the
    // exact failure this is here to catch.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    if (newestTickMillis == null) {
        Text(
            "No ticks stored for today yet — connect the feed in Phase 4.",
            style = MaterialTheme.typography.bodySmall,
            color = NEUTRAL_COLOR
        )
        return
    }

    val ageMillis = (nowMillis - newestTickMillis).coerceAtLeast(0L)
    val ageSeconds = ageMillis / 1000L
    val ageText = when {
        ageSeconds < 60L -> "${ageSeconds}s ago"
        ageSeconds < 3600L -> "${ageSeconds / 60L}m ago"
        else -> "%.1fh ago".format(ageSeconds / 3600.0)
    }

    when {
        ageMillis <= LIVE_WITHIN_MS -> Text(
            "● Live — newest tick $ageText",
            style = MaterialTheme.typography.bodySmall,
            color = BULLISH_COLOR
        )
        ageMillis <= STALE_AFTER_MS -> Text(
            "Newest tick $ageText",
            style = MaterialTheme.typography.bodySmall,
            color = NEUTRAL_COLOR
        )
        else -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "⚠ No new ticks for $ageText",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "Everything below is that old. If the market is open, the feed has most " +
                        "likely dropped — go back to Phase 4 and reconnect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * Market-wide panic alert card: NIFTY 50 spot itself making a sudden, large move in a short
 * window, independent of any active trade or the 6-indicator dashboard below — see
 * [com.niftyradar.app.domain.PanicAlert]'s doc comment for why this is separate. Renders nothing
 * at all when there's no panic right now (the common case), so it never clutters the screen;
 * when triggered, an urgent red card appears above everything else, matching the notification +
 * longer vibration [com.niftyradar.app.notification.PanicAlertNotifier] fires at the same time.
 */
@Composable
private fun PanicAlertCard(result: PanicAlertResult?) {
    if (result == null || !result.triggered) return
    val arrow = if (result.direction == SignalDirection.BULLISH) "⬆️" else "⬇️"
    val message = if (result.direction == SignalDirection.BULLISH) {
        "PANIC: NIFTY spiked +%.2f%% in the last 5 min".format(result.changePercent)
    } else {
        "PANIC: NIFTY dropped %.2f%% in the last 5 min".format(abs(result.changePercent))
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "$arrow $message",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "Sudden market-wide move — check before acting on any open position.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * Step 2's dashboard card: every indicator gets a colored arrow AND a short text reason
 * together (the user was explicit that both are wanted, not either/or), plus an aggregate
 * "X of N bullish/bearish/neutral" line. Currently 5 of the eventual 6 indicators — see
 * [com.niftyradar.app.domain.IndicatorEngine]'s doc comment (only ATR is left out, since it
 * never votes). The "great indication" notification (5-6/6 agreeing) is intentionally NOT
 * built here — it needs its own Android notification-channel + vibration + overlay-flash
 * wiring, which is its own increment, not part of proving the indicator math itself.
 */
@Composable
private fun DashboardCard(dashboard: DashboardResult?) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("6-Indicator Dashboard (5 of 6 so far)", style = MaterialTheme.typography.titleSmall)
            if (dashboard == null) {
                Text(
                    "Waiting for pivot levels + live ticks...",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                for (signal in dashboard.signals) {
                    IndicatorRow(signal)
                }
                HorizontalDivider()
                Text(
                    "${dashboard.bullishCount} of ${dashboard.total} Bullish, " +
                        "${dashboard.bearishCount} of ${dashboard.total} Bearish, " +
                        "${dashboard.neutralCount} of ${dashboard.total} Neutral",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun IndicatorRow(signal: IndicatorSignal) {
    val (arrow, color) = when (signal.direction) {
        SignalDirection.BULLISH -> "⬆️" to BULLISH_COLOR
        SignalDirection.BEARISH -> "⬇️" to BEARISH_COLOR
        SignalDirection.NEUTRAL -> "➡️" to NEUTRAL_COLOR
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, color = color, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Text(signal.name, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        Text(
            signal.reason,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 28.dp)
        )
    }
}

/**
 * Step 1b's "prove it visually" checkpoint (same idea as the per-contract Greeks readout
 * row from Step 1a): NIFTY 50 spot's previous-day classic pivot levels and daily-candle
 * ATR(14), fetched once via [Phase9ViewModel.dailyLevels] — see that ViewModel's doc comment.
 * Just a numeric readout for now; the 6-indicator dashboard (Step 2) is what actually turns
 * these into a Pivot Points arrow/vote and an ATR-sized Target/SL suggestion.
 */
@Composable
private fun DailyLevelsCard(state: DailyLevelsUiState) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pivot Points & ATR (NIFTY 50, previous day)", style = MaterialTheme.typography.titleSmall)
            when (state) {
                is DailyLevelsUiState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fetching historical candles...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is DailyLevelsUiState.Failed -> {
                    Text("Could not load: ${state.message}", style = MaterialTheme.typography.bodySmall)
                }
                is DailyLevelsUiState.Ready -> {
                    val p = state.pivots
                    Text(
                        "Pivot: %.2f   R1: %.2f   S1: %.2f".format(p.pivot, p.r1, p.s1),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "R2: %.2f   S2: %.2f".format(p.r2, p.s2),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (state.atr14 != null) "ATR(14): %.2f pts".format(state.atr14)
                        else "ATR(14): not enough daily candles yet",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
