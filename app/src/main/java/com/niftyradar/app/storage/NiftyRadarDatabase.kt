package com.niftyradar.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Phase 5: the app's only Room database so far — just [LiveTickEntity].
 * `exportSchema = false` on purpose: this is still an early, single-developer
 * phase of the app (no migration history to preserve yet, per PROJECT_SPEC.md's
 * "build incrementally, don't over-engineer ahead of need" approach); revisit
 * once real migrations matter.
 *
 * version 2 (buy/sell pressure): added [LiveTickEntity.totalBuyQuantity] /
 * [LiveTickEntity.totalSellQuantity]. This uses a real [Migration], not
 * `fallbackToDestructiveMigration()`, on purpose — Phase 10 added multi-day
 * history (every locked day's ticks are kept, not just today's), so wiping
 * the database on every schema change would silently delete real history
 * the moment this update installs over an older build.
 *
 * version 3 (option Greeks): added [LiveTickEntity.delta]/[theta]/[gamma]/
 * [vega]/[rho] — Upstox's own server-computed Greeks, read straight off the
 * feed (see [com.niftyradar.app.feed.MarketFeedClient]), no Black-Scholes
 * math in this app. Same real-[Migration] discipline as version 2, for the
 * same reason.
 */
@Database(entities = [LiveTickEntity::class], version = 3, exportSchema = false)
abstract class NiftyRadarDatabase : RoomDatabase() {

    abstract fun liveTickDao(): LiveTickDao

    companion object {
        @Volatile private var instance: NiftyRadarDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE live_ticks ADD COLUMN totalBuyQuantity REAL")
                db.execSQL("ALTER TABLE live_ticks ADD COLUMN totalSellQuantity REAL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE live_ticks ADD COLUMN delta REAL")
                db.execSQL("ALTER TABLE live_ticks ADD COLUMN theta REAL")
                db.execSQL("ALTER TABLE live_ticks ADD COLUMN gamma REAL")
                db.execSQL("ALTER TABLE live_ticks ADD COLUMN vega REAL")
                db.execSQL("ALTER TABLE live_ticks ADD COLUMN rho REAL")
            }
        }

        fun getInstance(context: Context): NiftyRadarDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NiftyRadarDatabase::class.java,
                    "nifty_radar.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }
    }
}
