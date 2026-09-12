package com.niftyradar.app.storage

import android.content.Context
import com.niftyradar.app.feed.TickEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 5: thin app-facing wrapper around [LiveTickDao] — Phase4ViewModel
 * (and later, chart-reading code) talks to this, not to Room directly, same
 * pattern as [RadarSessionStore] / [com.niftyradar.app.security.SecureTokenStore].
 *
 * RETENTION was missing entirely until now, and that was a real defect rather than an
 * unbuilt feature: this app inserts one row per tick for 23 instruments in Upstox's "full"
 * mode, every trading day, and nothing ever deleted any of them. The database could only
 * grow, for as long as the app stayed installed, until it started competing with the
 * user's photos for storage. [trimToRecentSessions] closes that off, and it is also
 * exactly the rolling window the tick-recorder phase is specified around.
 */
class LiveTickStore(context: Context) {

    private val appContext = context.applicationContext
    private val database = NiftyRadarDatabase.getInstance(context)
    private val dao = database.liveTickDao()

    suspend fun recordTick(sessionDate: String, event: TickEvent) = withContext(Dispatchers.IO) {
        dao.insert(
            LiveTickEntity(
                sessionDate = sessionDate,
                instrumentKey = event.instrumentKey,
                receivedAtMillis = event.receivedAtMillis,
                ltp = event.quote.ltp,
                closePrice = event.quote.closePrice,
                lastTradeTimeMillis = event.quote.lastTradeTimeMillis,
                openInterest = event.quote.openInterest,
                volumeTradedToday = event.quote.volumeTradedToday,
                impliedVolatility = event.quote.impliedVolatility,
                totalBuyQuantity = event.quote.totalBuyQuantity,
                totalSellQuantity = event.quote.totalSellQuantity,
                delta = event.quote.delta,
                theta = event.quote.theta,
                gamma = event.quote.gamma,
                vega = event.quote.vega,
                rho = event.quote.rho,
                bestBidPrice = event.quote.bestBidPrice,
                bestAskPrice = event.quote.bestAskPrice,
                bestBidQuantity = event.quote.bestBidQuantity,
                bestAskQuantity = event.quote.bestAskQuantity,
                averageTradedPrice = event.quote.averageTradedPrice
            )
        )
    }

    suspend fun countForSession(sessionDate: String): Int =
        withContext(Dispatchers.IO) { dao.countForSession(sessionDate) }

    suspend fun instrumentCountForSession(sessionDate: String): Int =
        withContext(Dispatchers.IO) { dao.instrumentCountForSession(sessionDate) }

    suspend fun ticksFor(sessionDate: String, instrumentKey: String): List<LiveTickEntity> =
        withContext(Dispatchers.IO) { dao.ticksFor(sessionDate, instrumentKey) }

    /** What [trimToRecentSessions] actually did, so the UI can say it plainly. */
    data class TrimResult(
        val deletedTicks: Int,
        val deletedDays: List<String>,
        val keptDays: Int
    ) {
        val didAnything: Boolean get() = deletedTicks > 0 || deletedDays.isNotEmpty()
    }

    /** A readout of everything on disk, not just today. */
    data class StorageSummary(
        val totalTicks: Int,
        val recordedDays: Int,
        val oldestDay: String?,
        val newestDay: String?,
        val onDiskBytes: Long
    )

    /**
     * Keeps the [keepSessions] most recent RECORDED trading days and deletes the rest.
     *
     * Counted in recorded days, not calendar days: a week off, a holiday or a day the app
     * simply wasn't opened must not silently eat into the window, which is what any
     * "delete older than N days" rule would do.
     *
     * Deletion is by whole day (see [LiveTickDao.deleteSessionsBefore]). Note that SQLite
     * frees the pages for reuse but does not shrink the file — so after a trim the app
     * stops growing, but the file only gets smaller when [compact] is run.
     */
    suspend fun trimToRecentSessions(keepSessions: Int = RETENTION_SESSIONS): TrimResult =
        withContext(Dispatchers.IO) {
            require(keepSessions > 0) { "keepSessions must be at least 1." }
            val days = dao.recordedSessionDates() // newest first, weekends included
            // The window is counted in WEEKDAYS, and that is a correction rather than a
            // refinement. Opening the app on a Saturday still records a row per instrument per
            // heartbeat, so a weekend used to consume a slot in the window and evict a real
            // trading day — on a device that had four recorded days, two of them were a Sunday
            // and a Saturday. Weekend days are not deleted (a rare special session is still
            // real data); they simply stop pushing trading days out.
            val tradingDays = days.filter { isWeekday(it) }
            if (tradingDays.size <= keepSessions) {
                return@withContext TrimResult(
                    deletedTicks = 0,
                    deletedDays = emptyList(),
                    keptDays = tradingDays.size
                )
            }
            val oldestToKeep = tradingDays[keepSessions - 1]
            val dropped = days.filter { it < oldestToKeep }
            val deleted = dao.deleteSessionsBefore(oldestToKeep)
            TrimResult(deletedTicks = deleted, deletedDays = dropped, keptDays = keepSessions)
        }

    /**
     * Is this "yyyy-MM-dd" key a Monday-to-Friday date in IST?
     *
     * An unparseable key answers true on purpose: the only use of this is deciding what may be
     * deleted, and the safe direction for a value this cannot understand is to keep it.
     */
    private fun isWeekday(sessionDate: String): Boolean {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        val parsed = runCatching { fmt.parse(sessionDate) }.getOrNull() ?: return true
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        calendar.time = parsed
        val dow = calendar.get(Calendar.DAY_OF_WEEK)
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY
    }

    /**
     * How fast the feed is actually snapshotting, measured over [windowMillis] of wall clock.
     *
     * This exists because the obvious number is the wrong one. "39,843 ticks on disk" divided
     * by days and instruments looks like a snapshot rate and is not: the feed in this app lives
     * only as long as a screen holding it, so that quotient measures how long the app happened
     * to be open. Every decision that depends on the real rate — how often a live screen can
     * afford to re-read storage, whether per-second candles carry any information, whether a
     * volume-delta flow proxy has enough samples to mean anything, how many megabytes a day of
     * recording costs — needs ticks over a KNOWN number of seconds, with a divisor taken from
     * the data. So: measure it, once, on a live day, and stop estimating.
     */
    data class SnapshotRate(
        val windowSeconds: Int,
        val ticks: Int,
        val instruments: Int
    ) {
        /** Snapshots per instrument per minute, or null when nothing arrived to divide by. */
        val perInstrumentPerMinute: Double?
            get() = if (ticks == 0 || instruments == 0) null
            else (ticks.toDouble() / instruments) * (60.0 / windowSeconds)

        /** The same thing read the way it is usually asked: "a snapshot every how many seconds?" */
        val secondsPerSnapshot: Double?
            get() = perInstrumentPerMinute?.takeIf { it > 0.0 }?.let { 60.0 / it }
    }

    suspend fun snapshotRate(windowMillis: Long = RATE_WINDOW_MS): SnapshotRate =
        withContext(Dispatchers.IO) {
            val since = System.currentTimeMillis() - windowMillis
            SnapshotRate(
                windowSeconds = (windowMillis / 1000L).toInt().coerceAtLeast(1),
                ticks = dao.countSince(since),
                instruments = dao.instrumentCountSince(since)
            )
        }

    /** Per-day tick counts, newest first — makes a heartbeat-only day obvious as one. */
    suspend fun ticksPerRecordedDay(): List<DayTickCount> =
        withContext(Dispatchers.IO) { dao.ticksPerRecordedDay() }

    /**
     * Runs the trim at most once per process, so opening a screen twice does not repeat the
     * work. Returns null when this process has already done it.
     *
     * Called before the feed connects rather than during recording: a large DELETE competing
     * with tick inserts would stall them, and the whole point of the inserts is that they
     * keep up with the market.
     */
    suspend fun trimOnceThisProcess(): TrimResult? {
        if (!trimmedThisProcess.compareAndSet(false, true)) return null
        return trimToRecentSessions()
    }

    suspend fun storageSummary(): StorageSummary = withContext(Dispatchers.IO) {
        val days = dao.recordedSessionDates() // newest first
        StorageSummary(
            totalTicks = dao.totalCount(),
            recordedDays = days.size,
            oldestDay = days.lastOrNull(),
            newestDay = days.firstOrNull(),
            onDiskBytes = onDiskBytes()
        )
    }

    /**
     * SQLite VACUUM: rebuilds the file so the space a trim freed is actually returned to the
     * phone. Returns the bytes recovered.
     *
     * Deliberately NEVER automatic. VACUUM rewrites the whole database, which is slow and
     * temporarily needs free space roughly equal to the current file — the last thing to do
     * unasked on a phone that may be short of both. The trim alone is enough to stop the
     * growth; this is the user's explicit "now give me the space back".
     */
    suspend fun compact(): Long = withContext(Dispatchers.IO) {
        val before = onDiskBytes()
        // execSQL directly, not runInTransaction: VACUUM cannot run inside a transaction.
        database.openHelper.writableDatabase.execSQL("VACUUM")
        (before - onDiskBytes()).coerceAtLeast(0L)
    }

    /**
     * The real footprint, counting SQLite's write-ahead log and shared-memory siblings.
     * The .db file alone under-reports it, sometimes badly, mid-session.
     */
    private fun onDiskBytes(): Long =
        listOf("", "-wal", "-shm")
            .map { appContext.getDatabasePath(NiftyRadarDatabase.FILE_NAME + it) }
            .filter { it.exists() }
            .sumOf { it.length() }

    companion object {
        /**
         * How many recorded WEEKDAYS of ticks to keep.
         *
         * Deliberately cut from 20 to 5, and the reason is arithmetic rather than taste. A
         * measured device held 39,843 ticks in 12.6 MB — about 316 bytes per row once SQLite's
         * indices and write-ahead log are counted. That was cheap only because the app had not
         * been left open: at one snapshot per instrument per second, 23 instruments over a
         * 6h15m session is ~517,000 rows, ~163 MB, and twenty of those days is over 3 GB. The
         * very next change to this app keeps a live screen open all session, which is exactly
         * the condition that makes the large numbers real. Five days is a week of context, it
         * bounds the worst case near 800 MB rather than 3 GB, and it can be raised deliberately
         * once [snapshotRate] has reported what a day actually costs — which is the honest
         * order: measure, then size the window.
         */
        const val RETENTION_SESSIONS = 5

        /**
         * The window [snapshotRate] measures over. Sixty seconds is long enough that a single
         * slow round trip does not distort it, and short enough to describe the market as it
         * is right now rather than an average over a lull.
         */
        const val RATE_WINDOW_MS = 60_000L

        private val trimmedThisProcess = AtomicBoolean(false)
    }
}
