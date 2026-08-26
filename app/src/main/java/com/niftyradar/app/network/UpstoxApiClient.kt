package com.niftyradar.app.network

import com.niftyradar.app.model.OptionContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * REST client for the (small) set of official Upstox endpoints this app needs
 * before the Market Data Feed V3 WebSocket takes over in Phase 4:
 *  - Get Profile (Phase 1): is this token valid?
 *  - LTP Quotes V3 (Phase 3): one-shot NIFTY 50 spot price to compute ATM at
 *    session start — cheaper than standing up the WebSocket just for this.
 *  - Option Contracts (Phase 2): the strikes/instrument keys for an expiry.
 *
 * Endpoints/fields below were verified against the live Upstox developer docs
 * on 2026-08-26; re-check before relying on this in production, per the
 * project spec's own warning that these can drift.
 */
class UpstoxApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class ProfileResult {
        data class Success(
            val userName: String,
            val userId: String,
            val email: String,
            val broker: String,
            val exchanges: List<String>,
            val isActive: Boolean
        ) : ProfileResult()

        data class Failure(val message: String, val httpCode: Int? = null) : ProfileResult()
    }

    sealed class SpotResult {
        data class Success(val lastPrice: Double) : SpotResult()
        data class Failure(val message: String, val httpCode: Int? = null) : SpotResult()
    }

    sealed class ContractsResult {
        data class Success(val contracts: List<OptionContract>) : ContractsResult()
        data class Failure(val message: String, val httpCode: Int? = null) : ContractsResult()
    }

    /**
     * Calls GET /v2/user/profile with the given bearer token. Runs on Dispatchers.IO;
     * safe to call from a Compose coroutine scope / ViewModel without extra wrapping.
     */
    suspend fun verifyToken(accessToken: String): ProfileResult = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) {
            return@withContext ProfileResult.Failure("No token entered.")
        }

        val request = Request.Builder()
            .url("$BASE_URL/v2/user/profile")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val errorMessage = extractErrorMessage(bodyString)
                        ?: "Upstox returned HTTP ${response.code}."
                    return@withContext ProfileResult.Failure(errorMessage, response.code)
                }

                val json = JSONObject(bodyString)
                val data = json.getJSONObject("data")

                val exchanges = mutableListOf<String>()
                data.optJSONArray("exchanges")?.let { arr ->
                    for (i in 0 until arr.length()) exchanges.add(arr.getString(i))
                }

                ProfileResult.Success(
                    userName = data.optString("user_name", "(unknown)"),
                    userId = data.optString("user_id", "(unknown)"),
                    email = data.optString("email", "(unknown)"),
                    broker = data.optString("broker", "UPSTOX"),
                    exchanges = exchanges,
                    isActive = data.optBoolean("is_active", true)
                )
            }
        } catch (io: IOException) {
            ProfileResult.Failure("Network error: ${io.message ?: "could not reach Upstox"}.")
        } catch (parse: Exception) {
            ProfileResult.Failure("Could not parse Upstox response: ${parse.message}")
        }
    }

    /**
     * GET /v3/market-quote/ltp?instrument_key=NSE_INDEX|Nifty 50
     *
     * NOTE (important quirk, confirmed from Upstox docs): the response's
     * `data` object is keyed by something like `"NSE_INDEX:Nifty 50"` — a
     * colon-separated exchange:trading-symbol string — which is NOT the same
     * string as the `instrument_key` (pipe-separated) used in the request. We
     * only ever ask for one instrument here, so rather than guess the exact
     * key format, we just read whichever single entry `data` contains.
     */
    suspend fun getNiftySpotLtp(accessToken: String): SpotResult = withContext(Dispatchers.IO) {
        val url = "$BASE_URL_V3/market-quote/ltp".toHttpUrl().newBuilder()
            .addQueryParameter("instrument_key", NIFTY_50_INSTRUMENT_KEY)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val errorMessage = extractErrorMessage(bodyString)
                        ?: "Upstox returned HTTP ${response.code}."
                    return@withContext SpotResult.Failure(errorMessage, response.code)
                }

                val json = JSONObject(bodyString)
                val data = json.getJSONObject("data")
                val keys = data.keys()
                if (!keys.hasNext()) {
                    return@withContext SpotResult.Failure("Upstox returned no quote for NIFTY 50.")
                }
                val entry = data.getJSONObject(keys.next())
                SpotResult.Success(entry.getDouble("last_price"))
            }
        } catch (io: IOException) {
            SpotResult.Failure("Network error: ${io.message ?: "could not reach Upstox"}.")
        } catch (parse: Exception) {
            SpotResult.Failure("Could not parse Upstox spot quote: ${parse.message}")
        }
    }

    /**
     * GET /v2/option/contract?instrument_key=NSE_INDEX|Nifty 50&expiry_date=yyyy-MM-dd
     */
    suspend fun getOptionContracts(accessToken: String, expiryDate: String): ContractsResult =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/v2/option/contract".toHttpUrl().newBuilder()
                .addQueryParameter("instrument_key", NIFTY_50_INSTRUMENT_KEY)
                .addQueryParameter("expiry_date", expiryDate)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        val errorMessage = extractErrorMessage(bodyString)
                            ?: "Upstox returned HTTP ${response.code}."
                        return@withContext ContractsResult.Failure(errorMessage, response.code)
                    }

                    val json = JSONObject(bodyString)
                    val dataArr = json.getJSONArray("data")
                    val contracts = mutableListOf<OptionContract>()
                    for (i in 0 until dataArr.length()) {
                        val c = dataArr.getJSONObject(i)
                        contracts += OptionContract(
                            strikePrice = c.getDouble("strike_price"),
                            instrumentKey = c.getString("instrument_key"),
                            instrumentType = c.getString("instrument_type"),
                            expiry = c.optString("expiry", expiryDate),
                            tradingSymbol = c.optString("trading_symbol", ""),
                            lotSize = c.optInt("lot_size", 0)
                        )
                    }

                    if (contracts.isEmpty()) {
                        return@withContext ContractsResult.Failure(
                            "Upstox returned zero contracts for expiry $expiryDate. Check the " +
                                "expiry date is a valid, currently-listed NIFTY expiry."
                        )
                    }

                    ContractsResult.Success(contracts)
                }
            } catch (io: IOException) {
                ContractsResult.Failure("Network error: ${io.message ?: "could not reach Upstox"}.")
            } catch (parse: Exception) {
                ContractsResult.Failure("Could not parse Upstox option contracts: ${parse.message}")
            }
        }

    private fun extractErrorMessage(bodyString: String): String? {
        return try {
            val json = JSONObject(bodyString)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val first = errors.getJSONObject(0)
                first.optString("message").ifBlank { null }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val BASE_URL = "https://api.upstox.com"
        const val BASE_URL_V3 = "https://api.upstox.com/v3"
        const val NIFTY_50_INSTRUMENT_KEY = "NSE_INDEX|Nifty 50"
    }
}
