package com.niftyradar.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the user's Upstox access token ON-DEVICE ONLY, encrypted at rest via the
 * Android Keystore (through Jetpack Security's EncryptedSharedPreferences).
 *
 * Design rules this class exists to enforce:
 *  - The token is typed into the app by the user, once, in [MainActivity]. It is
 *    never hard-coded, never logged (see [redacted]), and never leaves the device
 *    except as the Authorization header of a direct HTTPS call to api.upstox.com.
 *  - There is no server of ours in the loop, so there is nowhere else for the
 *    token to leak to.
 *  - The token is NOT the same as the Upstox API "client secret" used only during
 *    the one-time OAuth code exchange on a browser/server; this app only ever
 *    needs the resulting access token, which the user copies in from wherever
 *    they generated it (e.g. Upstox's developer console / their own login flow).
 */
class SecureTokenStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveAccessToken(token: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, token.trim()).apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun clearAccessToken() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
    }

    fun hasAccessToken(): Boolean = !getAccessToken().isNullOrBlank()

    companion object {
        private const val PREFS_FILE_NAME = "niftyradar_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "upstox_access_token"

        /** Safe-for-logs version of a token: never print the real value anywhere. */
        fun redacted(token: String?): String {
            if (token.isNullOrBlank()) return "(none)"
            val visible = token.takeLast(4)
            return "****$visible"
        }
    }
}
