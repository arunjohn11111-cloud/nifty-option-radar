package com.niftyradar.app.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LiveTickDao {

    @Insert
    suspend fun insert(tick: LiveTickEntity)

    /** Full intra-day tick history for one instrument — what a Phase 6+ chart will read. */
    @Query(
        "SELECT * FROM live_ticks WHERE sessionDate = :sessionDate AND instrumentKey = :instrumentKey " +
            "ORDER BY receivedAtMillis ASC"
    )
    suspend fun ticksFor(sessionDate: String, instrumentKey: String): List<LiveTickEntity>

    /** Cheap proof-of-life count for the Phase 4/5 screen: is anything actually being saved? */
    @Query("SELECT COUNT(*) FROM live_ticks WHERE sessionDate = :sessionDate")
    suspend fun countForSession(sessionDate: String): Int

    @Query("SELECT COUNT(DISTINCT instrumentKey) FROM live_ticks WHERE sessionDate = :sessionDate")
    suspend fun instrumentCountForSession(sessionDate: String): Int

    /**
     * Every trading day that has at least one recorded tick, NEWEST FIRST.
     *
     * Ordering the "yyyy-MM-dd" strings directly is safe: that format sorts
     * lexicographically the same way it sorts chronologically.
     */
    @Query("SELECT DISTINCT sessionDate FROM live_ticks ORDER BY sessionDate DESC")
    suspend fun recordedSessionDates(): List<String>

    /**
     * Drops whole trading days, never part of one — a half-deleted day would leave a chart
     * or an OI comparison reading a window it cannot see the start of, which is worse than
     * not having the day at all. See [LiveTickStore.trimToRecentSessions].
     */
    @Query("DELETE FROM live_ticks WHERE sessionDate < :oldestDateToKeep")
    suspend fun deleteSessionsBefore(oldestDateToKeep: String): Int

    /** Across every recorded day, not just today — what the retention readout reports. */
    @Query("SELECT COUNT(*) FROM live_ticks")
    suspend fun totalCount(): Int

    /**
     * How many ticks landed since [sinceMillis]. With its sibling below, this is the app's
     * only honest measurement of how fast the feed actually snapshots.
     *
     * The total tick count cannot answer that question, and reading it as if it could was a
     * mistake: this app's feed only runs while a screen holding it is open, so the total
     * measures how long the app was used, not what the exchange sends. A count over a known
     * recent window divides out by a known number of seconds, which is the whole point.
     */
    @Query("SELECT COUNT(*) FROM live_ticks WHERE receivedAtMillis >= :sinceMillis")
    suspend fun countSince(sinceMillis: Long): Int

    /**
     * How many distinct instruments reported in that same window — the divisor. Taken from
     * the data rather than assumed to be 23, because a subscription that silently dropped
     * instruments would otherwise inflate the per-instrument rate instead of showing up.
     */
    @Query("SELECT COUNT(DISTINCT instrumentKey) FROM live_ticks WHERE receivedAtMillis >= :sinceMillis")
    suspend fun instrumentCountSince(sinceMillis: Long): Int

    /**
     * Per-day tick counts WITH the wall-clock span they arrived over, newest day first.
     *
     * The span is the whole point, and leaving it out the first time was a wasted opportunity.
     * A per-day count alone still cannot be read as a rate — a device holding four recorded
     * days turned out to hold 39,613 ticks on one Monday and 253 across the other three, which
     * a total or an average hides completely. But first-to-last on a single day IS a known
     * number of seconds, so every past day already on disk can be converted into the rate that
     * was otherwise going to need a fresh live session to measure.
     */
    @Query(
        "SELECT sessionDate AS day, COUNT(*) AS ticks, " +
            "MIN(receivedAtMillis) AS firstMillis, MAX(receivedAtMillis) AS lastMillis " +
            "FROM live_ticks GROUP BY sessionDate ORDER BY sessionDate DESC"
    )
    suspend fun ticksPerRecordedDay(): List<DayTickCount>

    /**
     * The newest tick's arrival time for a day, or null when the day has none.
     *
     * One aggregate over an indexed column, and it exists to make a fast refresh loop cheap
     * instead of merely frequent. The measured feed sends roughly one snapshot per instrument
     * every fifteen seconds, so a loop running every two seconds finds nothing new on most
     * passes — and re-reading every instrument's whole day to discover that is the expensive
     * way to learn it. Asking this first turns those passes into a single scalar read.
     */
    @Query("SELECT MAX(receivedAtMillis) FROM live_ticks WHERE sessionDate = :sessionDate")
    suspend fun latestTickMillis(sessionDate: String): Long?

    /**
     * How many stored ticks claim to have arrived after [nowMillis] — that is, in the future.
     *
     * A diagnostic rather than a feature, and it exists because a device turned up with its
     * newest tick timestamped about twelve hours ahead of its own clock. The write path stamps
     * rows with System.currentTimeMillis() and nothing else touches the column, so the code
     * cannot explain that on its own — which is exactly when a measurement beats a theory.
     */
    @Query("SELECT COUNT(*) FROM live_ticks WHERE receivedAtMillis > :nowMillis")
    suspend fun countTicksAfter(nowMillis: Long): Int

    /** The furthest-future timestamp on disk, or null when none is ahead of [nowMillis]. */
    @Query("SELECT MAX(receivedAtMillis) FROM live_ticks WHERE receivedAtMillis > :nowMillis")
    suspend fun maxTickAfter(nowMillis: Long): Long?

    /** Newest and oldest stored timestamps overall, whatever day they claim to belong to. */
    @Query("SELECT MIN(receivedAtMillis) FROM live_ticks")
    suspend fun earliestTickMillisOverall(): Long?

    @Query("SELECT MAX(receivedAtMillis) FROM live_ticks")
    suspend fun latestTickMillisOverall(): Long?
}

/** One row of [LiveTickDao.ticksPerRecordedDay]. */
data class DayTickCount(
    val day: String,
    val ticks: Int,
    val firstMillis: Long,
    val lastMillis: Long
) {
    /** Seconds between the first and last tick recorded that day. */
    val spanSeconds: Long get() = ((lastMillis - firstMillis) / 1000L).coerceAtLeast(0L)
}
