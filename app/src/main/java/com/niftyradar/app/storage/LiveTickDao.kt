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
}
