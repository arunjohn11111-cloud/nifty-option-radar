package com.niftyradar.app.storage

import android.content.Context
import com.niftyradar.app.feed.TickEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                rho = event.quote.rho
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
            val days = dao.recordedSessionDates() // newest first
            if (days.size <= keepSessions) {
                return@withContext TrimResult(
                    deletedTicks = 0,
                    deletedDays = emptyList(),
                    keptDays = days.size
                )
            }
            val oldestToKeep = days[keepSessions - 1]
            val dropped = days.drop(keepSessions)
            val deleted = dao.deleteSessionsBefore(oldestToKeep)
            TrimResult(deletedTicks = deleted, deletedDays = dropped, keptDays = keepSessions)
        }

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
         * How many recorded trading days of ticks to keep. 20 is the window the tick-recorder
         * phase is specified around — roughly a trading month, enough to ask "what happened
         * the last few times the board looked like this" without the database becoming the
         * largest thing on the phone.
         */
        const val RETENTION_SESSIONS = 20

        private val trimmedThisProcess = AtomicBoolean(false)
    }
}
