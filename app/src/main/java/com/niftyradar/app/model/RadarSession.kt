package com.niftyradar.app.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The result of Phases 2+3: 11 strikes (5 below ATM + ATM + 5 above), CE+PE
 * resolved to real Upstox instrument keys = 22 locked contracts, plus enough
 * metadata to know whether "today's radar" already exists.
 *
 * Per spec section 3 and 16: once this is built for a session date, it is
 * LOCKED — later phases must load it back rather than rebuild it, even if
 * spot has since moved outside [strikes.min(), strikes.max()].
 */
data class LockedContract(
    val instrumentKey: String,
    val tradingSymbol: String,
    val lotSize: Int
)

data class RadarSession(
    val sessionDate: String, // yyyy-MM-dd, device-local trading day this radar was locked for
    val expiry: String,
    val spotAtLock: Double,
    val atmStrike: Double,
    val strikes: List<Double>, // ascending, size ideally 11
    // key format: "<strike>_CE" / "<strike>_PE", e.g. "24200.0_CE"
    val contracts: Map<String, LockedContract>,
    val warnings: List<String>
) {
    companion object {
        fun contractKey(strike: Double, instrumentType: String) = "${strike}_$instrumentType"

        fun fromJson(raw: String): RadarSession {
            val j = JSONObject(raw)
            val strikesArr = j.getJSONArray("strikes")
            val strikes = (0 until strikesArr.length()).map { strikesArr.getDouble(it) }

            val contractsJson = j.getJSONObject("contracts")
            val contracts = mutableMapOf<String, LockedContract>()
            contractsJson.keys().forEach { key ->
                val c = contractsJson.getJSONObject(key)
                contracts[key] = LockedContract(
                    instrumentKey = c.getString("instrumentKey"),
                    tradingSymbol = c.getString("tradingSymbol"),
                    lotSize = c.getInt("lotSize")
                )
            }

            val warningsArr = j.optJSONArray("warnings") ?: JSONArray()
            val warnings = (0 until warningsArr.length()).map { warningsArr.getString(it) }

            return RadarSession(
                sessionDate = j.getString("sessionDate"),
                expiry = j.getString("expiry"),
                spotAtLock = j.getDouble("spotAtLock"),
                atmStrike = j.getDouble("atmStrike"),
                strikes = strikes,
                contracts = contracts,
                warnings = warnings
            )
        }
    }

    fun toJson(): String {
        val j = JSONObject()
        j.put("sessionDate", sessionDate)
        j.put("expiry", expiry)
        j.put("spotAtLock", spotAtLock)
        j.put("atmStrike", atmStrike)
        j.put("strikes", JSONArray(strikes))

        val contractsJson = JSONObject()
        contracts.forEach { (key, c) ->
            val cj = JSONObject()
            cj.put("instrumentKey", c.instrumentKey)
            cj.put("tradingSymbol", c.tradingSymbol)
            cj.put("lotSize", c.lotSize)
            contractsJson.put(key, cj)
        }
        j.put("contracts", contractsJson)
        j.put("warnings", JSONArray(warnings))

        return j.toString()
    }
}
