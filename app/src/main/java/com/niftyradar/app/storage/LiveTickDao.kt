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
}
