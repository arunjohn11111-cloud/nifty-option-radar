package com.niftyradar.app.storage

import android.content.Context
import com.niftyradar.app.model.RadarSession

/**
 * Persists LOCKED radars (11 strikes / 22 instrument keys) — one per trading
 * day — in plain (unencrypted) SharedPreferences: this is public market
 * structure data (strikes, instrument keys, trading symbols), not a secret,
 * unlike the access token in SecureTokenStore.
 *
 * Phase 10: before this, only the single most-recently-locked day was ever
 * kept (each new lock silently discarded the previous day's). Now every
 * locked day gets its own key, plus an index of known dates, so Phase 10's
 * history screen can list and reload any past day. [loadForDate] behaves
 * exactly as before for the "today" callers added in Phases 2-9 — nothing
 * about looking up today's session changes; only that it's no longer the
 * only day this can ever remember. A one-time migration in [loadForDate]
 * picks up whatever was stored under the old single-slot key before this
 * change, so an already-locked "today" isn't silently lost by the upgrade.
 *
 * The whole point (spec section 3 + 16) is still: once a session is locked
 * for a given date, it must be loaded back as-is, never silently rebuilt,
 * even across app restarts on the same day.
 */
class RadarSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    fun save(session: RadarSession) {
        prefs.edit()
            .putString(sessionKey(session.sessionDate), session.toJson())
            .apply()
        addKnownDate(session.sessionDate)
    }

    /** Returns the locked session for [date], or null if none was ever locked for that date. */
    fun loadForDate(date: String): RadarSession? {
        val raw = prefs.getString(sessionKey(date), null)
        if (raw != null) {
            return try {
                RadarSession.fromJson(raw)
            } catch (e: Exception) {
                null
            }
        }

        // Legacy fallback: before Phase 10, only one session was ever stored,
        // under a single shared key. Migrate it forward into the new
        // per-date storage if it matches this date, so an already-locked
        // "today" from before this update isn't silently lost.
        val legacyRaw = prefs.getString(KEY_LEGACY_SESSION_JSON, null) ?: return null
        return try {
            val legacySession = RadarSession.fromJson(legacyRaw)
            if (legacySession.sessionDate == date) {
                save(legacySession)
                legacySession
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Every date that has a locked session, most recent first — for Phase 10's history screen. */
    fun listLockedDates(): List<String> {
        val dates = prefs.getStringSet(KEY_KNOWN_DATES, emptySet()) ?: emptySet()
        return dates.sortedDescending()
    }

    private fun addKnownDate(date: String) {
        val dates = (prefs.getStringSet(KEY_KNOWN_DATES, emptySet()) ?: emptySet()).toMutableSet()
        if (dates.add(date)) {
            prefs.edit().putStringSet(KEY_KNOWN_DATES, dates).apply()
        }
    }

    private fun sessionKey(date: String) = "$KEY_SESSION_JSON_PREFIX$date"

    companion object {
        private const val PREFS_FILE_NAME = "niftyradar_session_prefs"
        private const val KEY_SESSION_JSON_PREFIX = "locked_radar_session_"
        private const val KEY_KNOWN_DATES = "locked_radar_session_dates"

        /** Old single-slot key from before Phase 10 — read-only now, for migration. */
        private const val KEY_LEGACY_SESSION_JSON = "locked_radar_session"
    }
}
