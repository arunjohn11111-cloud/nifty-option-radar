package com.niftyradar.app.network

import com.niftyradar.app.model.Candle
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

    sealed class FeedAuthorizeResult {
        data class Success(val webSocketUrl: String) : FeedAuthorizeResult()
        data class Failure(val message: String, val httpCode: Int? = null) : FeedAuthorizeResult()
    }

    sealed class CandlesResult {
        data class Success(val candles: List<Candle>) : CandlesResult()
        data class Failure(val message: String, val httpCode: Int? = null) : CandlesResult()
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

    /**
     * GET /v3/feed/market-data-feed/authorize — returns a one-time-use `wss://`
     * URL (`data.authorized_redirect_uri`) for opening the actual Market Data
     * Feed V3 WebSocket (see [com.niftyradar.app.feed.MarketFeedClient]).
     *
     * IMPORTANT: Market Data Feed V3 is a gated scope — Upstox requires
     * "Market Data Feed V3 – Read" to be manually enabled per app (post your
     * app's Client ID/API Key on community.upstox.com asking for it to be
     * enabled) before this call will succeed. A 403 here almost always means
     * that scope isn't enabled yet, not a bug in this code.
     */
    suspend fun getMarketDataFeedAuthorizeUrl(accessToken: String): FeedAuthorizeResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE_URL_V3/feed/market-data-feed/authorize")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        var errorMessage = extractErrorMessage(bodyString)
                            ?: "Upstox returned HTTP ${response.code}."
                        if (response.code == 403) {
                            errorMessage += " If this is a permission error: Market Data Feed V3 " +
                                "must be manually enabled for your app — post your app's Client " +
                                "ID on community.upstox.com asking for the 'Market Data Feed V3 " +
                                "– Read' scope."
                        }
                        return@withContext FeedAuthorizeResult.Failure(errorMessage, response.code)
                    }

                    val json = JSONObject(bodyString)
                    val data = json.getJSONObject("data")
                    FeedAuthorizeResult.Success(data.getString("authorized_redirect_uri"))
                }
            } catch (io: IOException) {
                FeedAuthorizeResult.Failure("Network error: ${io.message ?: "could not reach Upstox"}.")
            } catch (parse: Exception) {
                FeedAuthorizeResult.Failure("Could not parse Upstox feed-authorize response: ${parse.message}")
            }
        }

    /**
     * GET /v3/historical-candle/{instrument_key}/{unit}/{interval}/{to_date}/{from_date}
     *
     * [unit]/[interval] follow Upstox's V3 allowed combinations (verified against the live
     * docs on 2026-08-30, per this file's standing "docs can drift" warning): "days"/"1",
     * "weeks"/"1", "months"/"1" or "minutes" with 1/3/5/10/15/30/60, or "hours" with 1/2/4.
     * [toDate]/[fromDate] are "yyyy-MM-dd". This endpoint only ever returns COMPLETED
     * candles — never an in-progress "today" candle — which is exactly what pivot points
     * (previous day's H/L/C) and ATR (a clean closed-candle series) both need.
     */
    suspend fun getHistoricalCandles(
        accessToken: String,
        instrumentKey: String,
        unit: String,
        interval: String,
        toDate: String,
        fromDate: String
    ): CandlesResult = withContext(Dispatchers.IO) {
        val url = "$BASE_URL_V3/historical-candle".toHttpUrl().newBuilder()
            .addPathSegment(instrumentKey)
            .addPathSegment(unit)
            .addPathSegment(interval)
            .addPathSegment(toDate)
            .addPathSegment(fromDate)
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
                    return@withContext CandlesResult.Failure(errorMessage, response.code)
                }

                CandlesResult.Success(parseCandles(bodyString))
            }
        } catch (io: IOException) {
            CandlesResult.Failure("Network error: ${io.message ?: "could not reach Upstox"}.")
        } catch (parse: Exception) {
            CandlesResult.Failure("Could not parse Upstox historical candles: ${parse.message}")
        }
    }

    /**
     * GET /v3/historical-candle/intraday/{instrument_key}/{unit}/{interval}
     *
     * Same [unit]/[interval] rules as [getHistoricalCandles], but for TODAY's still-forming
     * candles — Upstox's historical endpoint never includes an in-progress candle, so Trend
     * (9/21 EMA), which needs to react to what's happening RIGHT NOW rather than only up to
     * yesterday, combines this with a [getHistoricalCandles] call for the warm-up history
     * before today (see [com.niftyradar.app.ui.Phase9ViewModel.loadTrendCandles]).
     */
    suspend fun getIntradayCandles(
        accessToken: String,
        instrumentKey: String,
        unit: String,
        interval: String
    ): CandlesResult = withContext(Dispatchers.IO) {
        val url = "$BASE_URL_V3/historical-candle/intraday".toHttpUrl().newBuilder()
            .addPathSegment(instrumentKey)
            .addPathSegment(unit)
            .addPathSegment(interval)
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
                    return@withContext CandlesResult.Failure(errorMessage, response.code)
                }

                CandlesResult.Success(parseCandles(bodyString))
            }
        } catch (io: IOException) {
            CandlesResult.Failure("Network error: ${io.message ?: "could not reach Upstox"}.")
        } catch (parse: Exception) {
            CandlesResult.Failure("Could not parse Upstox intraday candles: ${parse.message}")
        }
    }

    /**
     * Each candle arrives as a JSON array: [timestamp, open, high, low, close, volume,
     * open_interest] (the last field is 0 for an index like NIFTY 50, which has no OI).
     * Upstox's own return order for the outer `candles` array is undocumented and has been
     * seen to vary, so this always re-sorts oldest-first by the ISO timestamp string itself
     * rather than trusting it — safe because Upstox always uses the same fixed "+05:30" IST
     * offset, so a plain string sort is already a correct chronological sort.
     */
    private fun parseCandles(bodyString: String): List<Candle> {
        val json = JSONObject(bodyString)
        val data = json.getJSONObject("data")
        val candlesArr = data.getJSONArray("candles")
        val candles = mutableListOf<Candle>()
        for (i in 0 until candlesArr.length()) {
            val c = candlesArr.getJSONArray(i)
            candles += Candle(
                timestampIso = c.getString(0),
                open = c.getDouble(1),
                high = c.getDouble(2),
                low = c.getDouble(3),
                close = c.getDouble(4),
                volume = c.optLong(5, 0L),
                openInterest = if (c.length() > 6) c.optDouble(6, 0.0) else 0.0
            )
        }
        return candles.sortedBy { it.timestampIso }
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
