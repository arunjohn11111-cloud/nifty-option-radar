package com.niftyradar.app.storage

import android.content.Context
import com.niftyradar.app.model.RadarSession

/**
 * Persists the LOCKED radar (11 strikes / 22 instrument keys) for the current
 * trading day, in plain (unencrypted) SharedPreferences — this is public
 * market structure data (strikes, instrument keys, trading symbols), not a
 * secret, unlike the access token in SecureTokenStore.
 *
 * The whole point (spec section 3 + 16) is: once a session is locked for a
 * given [sessionDate], it must be loaded back as-is, never silently rebuilt,
 * even across app restarts on the same day. A new day naturally gets a fresh
 * lock because [load] only returns a session whose sessionDate matches.
 */
class RadarSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    fun save(session: RadarSession) {
        prefs.edit().putString(KEY_SESSION_JSON, session.toJson()).apply()
    }

    /** Returns the stored session only if it matches [todaySessionDate]; null if none or stale. */
    fun loadForDate(todaySessionDate: String): RadarSession? {
        val raw = prefs.getString(KEY_SESSION_JSON, null) ?: return null
        return try {
            val session = RadarSession.fromJson(raw)
            if (session.sessionDate == todaySessionDate) session else null
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION_JSON).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "niftyradar_session_prefs"
        private const val KEY_SESSION_JSON = "locked_radar_session"
    }
}
