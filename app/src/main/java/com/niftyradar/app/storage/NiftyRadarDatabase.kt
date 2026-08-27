package com.niftyradar.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Phase 5: the app's only Room database so far — just [LiveTickEntity].
 * `exportSchema = false` on purpose: this is still an early, single-developer
 * phase of the app (no migration history to preserve yet, per PROJECT_SPEC.md's
 * "build incrementally, don't over-engineer ahead of need" approach); revisit
 * once real migrations matter.
 */
@Database(entities = [LiveTickEntity::class], version = 1, exportSchema = false)
abstract class NiftyRadarDatabase : RoomDatabase() {

    abstract fun liveTickDao(): LiveTickDao

    companion object {
        @Volatile private var instance: NiftyRadarDatabase? = null

        fun getInstance(context: Context): NiftyRadarDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NiftyRadarDatabase::class.java,
                    "nifty_radar.db"
                ).build().also { instance = it }
            }
    }
}
