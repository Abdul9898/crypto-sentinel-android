package com.cryptosentinel.app

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object BybitApiClient {

    private const val BASE_URL = "https://api.bybit.com"
    private const val RECV_WINDOW = "5000"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun generateSignature(secret: String, paramStr: String): String {
        return try {
            val sha256Hmac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            sha256Hmac.init(secretKey)
            val hash = sha256Hmac.doFinal(paramStr.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Test connection to Bybit V5 using API Key and Secret.
     * Verifies valid credentials, permissions, and confirms withdrawals are disabled.
     */
    fun testConnection(apiKey: String, apiSecret: String, callback: (Boolean, String) -> Unit) {
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            callback(false, "API Key and Secret Key cannot be empty.")
            return
        }

        val timestamp = System.currentTimeMillis().toString()
        val paramStr = timestamp + apiKey + RECV_WINDOW
        val signature = generateSignature(apiSecret, paramStr)

        val request = Request.Builder()
            .url("$BASE_URL/v5/user/query-api")
            .addHeader("X-BAPI-API-KEY", apiKey)
            .addHeader("X-BAPI-TIMESTAMP", timestamp)
            .addHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            .addHeader("X-BAPI-SIGN", signature)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    callback(false, "Network error connecting to Bybit: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                try {
                    val json = JSONObject(body)
                    val retCode = json.optInt("retCode", -1)
                    val retMsg = json.optString("retMsg", "Unknown error")

                    if (retCode == 0) {
                        val result = json.optJSONObject("result")
                        val permissions = result?.optJSONObject("permissions")
                        val isWithdrawalEnabled = permissions?.optJSONArray("Withdraw")?.length() ?: 0 > 0
                        val contractPerm = permissions?.optJSONArray("ContractTrade")

                        val withdrawNote = if (isWithdrawalEnabled) {
                            "⚠️ WARNING: Withdrawal is enabled on this key! Recommend disabling withdrawal for security."
                        } else {
                            "🛡️ Security verified: Withdrawals DISABLED ✅"
                        }

                        val msg = "✅ Connected to Bybit successfully!\n• Key Validated: OK\n• $withdrawNote"
                        mainHandler.post { callback(true, msg) }
                    } else {
                        val errorDesc = when (retCode) {
                            10003 -> "Invalid API Key. Please verify your Bybit API Key."
                            10004 -> "Invalid Signature. Please verify your Bybit API Secret Key."
                            10005 -> "Permission denied. Check API key permissions on Bybit."
                            else -> "Bybit Error ($retCode): $retMsg"
                        }
                        mainHandler.post { callback(false, errorDesc) }
                    }
                } catch (e: Exception) {
                    mainHandler.post { callback(false, "Error parsing Bybit response: ${e.message}") }
                }
            }
        })
    }

    /**
     * Fetch wallet balance for Unified or Contract account.
     */
    fun fetchWalletBalance(apiKey: String, apiSecret: String, callback: (Boolean, Double, String) -> Unit) {
        val timestamp = System.currentTimeMillis().toString()
        val qs = "accountType=UNIFIED"
        val paramStr = timestamp + apiKey + RECV_WINDOW + qs
        val signature = generateSignature(apiSecret, paramStr)

        val request = Request.Builder()
            .url("$BASE_URL/v5/account/wallet-balance?$qs")
            .addHeader("X-BAPI-API-KEY", apiKey)
            .addHeader("X-BAPI-TIMESTAMP", timestamp)
            .addHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            .addHeader("X-BAPI-SIGN", signature)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, 0.0, "Balance fetch failed: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                try {
                    val json = JSONObject(body)
                    val retCode = json.optInt("retCode", -1)
                    if (retCode == 0) {
                        val list = json.optJSONObject("result")?.optJSONArray("list")
                        var totalEquity = 0.0
                        if (list != null && list.length() > 0) {
                            val acc = list.getJSONObject(0)
                            totalEquity = acc.optString("totalEquity", "0.0").toDoubleOrNull() ?: 0.0
                        }
                        mainHandler.post { callback(true, totalEquity, "Balance fetched: $$totalEquity") }
                    } else {
                        mainHandler.post { callback(false, 0.0, json.optString("retMsg", "Unknown error")) }
                    }
                } catch (e: Exception) {
                    mainHandler.post { callback(false, 0.0, "Parsing error: ${e.message}") }
                }
            }
        })
    }

    /**
     * Set Leverage for a Linear Perpetual symbol on Bybit V5.
     */
    fun setLeverage(apiKey: String, apiSecret: String, symbol: String, leverage: Int, callback: (Boolean, String) -> Unit) {
        val timestamp = System.currentTimeMillis().toString()
        val levStr = leverage.coerceIn(1, 50).toString()
        val jsonBody = JSONObject().apply {
            put("category", "linear")
            put("symbol", symbol)
            put("buyLeverage", levStr)
            put("sellLeverage", levStr)
        }.toString()

        val paramStr = timestamp + apiKey + RECV_WINDOW + jsonBody
        val signature = generateSignature(apiSecret, paramStr)

        val request = Request.Builder()
            .url("$BASE_URL/v5/position/set-leverage")
            .addHeader("X-BAPI-API-KEY", apiKey)
            .addHeader("X-BAPI-TIMESTAMP", timestamp)
            .addHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            .addHeader("X-BAPI-SIGN", signature)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, "Leverage setup failed: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                try {
                    val json = JSONObject(body)
                    val retCode = json.optInt("retCode", -1)
                    // 0 = Success, 110043 = Leverage not modified (already set)
                    if (retCode == 0 || retCode == 110043) {
                        mainHandler.post { callback(true, "Leverage set to ${leverage}x") }
                    } else {
                        mainHandler.post { callback(false, json.optString("retMsg", "Leverage error")) }
                    }
                } catch (e: Exception) {
                    mainHandler.post { callback(false, "Error: ${e.message}") }
                }
            }
        })
    }

    /**
     * Place Live Bybit Linear Market Order with native Take Profit & Stop Loss.
     */
    fun placeLinearMarketOrder(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        side: String, // "Buy" (Long) or "Sell" (Short)
        qty: Double,
        leverage: Int,
        takeProfitPrice: Double,
        stopLossPrice: Double,
        callback: (Boolean, String?, String) -> Unit
    ) {
        // First ensure leverage is set, then place market order
        setLeverage(apiKey, apiSecret, symbol, leverage) { _, _ ->
            val timestamp = System.currentTimeMillis().toString()
            val fullSide = if (side.equals("LONG", ignoreCase = true) || side.equals("Buy", ignoreCase = true)) "Buy" else "Sell"

            // Format qty string appropriately
            val qtyStr = when {
                qty >= 1000 -> String.format(Locale.US, "%.0f", qty)
                qty >= 10 -> String.format(Locale.US, "%.1f", qty)
                qty >= 1 -> String.format(Locale.US, "%.2f", qty)
                qty >= 0.1 -> String.format(Locale.US, "%.3f", qty)
                else -> String.format(Locale.US, "%.4f", qty)
            }

            val tpStr = if (takeProfitPrice < 1.0) String.format(Locale.US, "%.4f", takeProfitPrice) else String.format(Locale.US, "%.2f", takeProfitPrice)
            val slStr = if (stopLossPrice < 1.0) String.format(Locale.US, "%.4f", stopLossPrice) else String.format(Locale.US, "%.2f", stopLossPrice)

            val orderJson = JSONObject().apply {
                put("category", "linear")
                put("symbol", symbol)
                put("side", fullSide)
                put("orderType", "Market")
                put("qty", qtyStr)
                put("takeProfit", tpStr)
                put("stopLoss", slStr)
                put("tpslMode", "Full")
                put("tpTriggerBy", "MarkPrice")
                put("slTriggerBy", "MarkPrice")
                put("positionIdx", 0) // One-Way mode
            }.toString()

            val paramStr = timestamp + apiKey + RECV_WINDOW + orderJson
            val signature = generateSignature(apiSecret, paramStr)

            val request = Request.Builder()
                .url("$BASE_URL/v5/order/create")
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-TIMESTAMP", timestamp)
                .addHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .addHeader("X-BAPI-SIGN", signature)
                .post(orderJson.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    mainHandler.post { callback(false, null, "Order failed: ${e.message}") }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string().orEmpty()
                    try {
                        val json = JSONObject(body)
                        val retCode = json.optInt("retCode", -1)
                        val retMsg = json.optString("retMsg", "Unknown error")
                        if (retCode == 0) {
                            val orderId = json.optJSONObject("result")?.optString("orderId")
                            mainHandler.post { callback(true, orderId, "Live Order Placed: $orderId") }
                        } else {
                            mainHandler.post { callback(false, null, "Bybit Error ($retCode): $retMsg") }
                        }
                    } catch (e: Exception) {
                        mainHandler.post { callback(false, null, "Order parsing error: ${e.message}") }
                    }
                }
            })
        }
    }

    /**
     * Close a Linear Perpetual position on Bybit with a reduce-only market order.
     */
    fun closeLinearPosition(
        apiKey: String,
        apiSecret: String,
        symbol: String,
        entrySide: String, // Original side ("LONG" or "SHORT")
        qty: Double,
        callback: (Boolean, String) -> Unit
    ) {
        val timestamp = System.currentTimeMillis().toString()
        val closeSide = if (entrySide.equals("LONG", ignoreCase = true) || entrySide.equals("Buy", ignoreCase = true)) "Sell" else "Buy"

        val qtyStr = when {
            qty >= 1000 -> String.format(Locale.US, "%.0f", qty)
            qty >= 10 -> String.format(Locale.US, "%.1f", qty)
            qty >= 1 -> String.format(Locale.US, "%.2f", qty)
            qty >= 0.1 -> String.format(Locale.US, "%.3f", qty)
            else -> String.format(Locale.US, "%.4f", qty)
        }

        val orderJson = JSONObject().apply {
            put("category", "linear")
            put("symbol", symbol)
            put("side", closeSide)
            put("orderType", "Market")
            put("qty", qtyStr)
            put("reduceOnly", true)
            put("positionIdx", 0)
        }.toString()

        val paramStr = timestamp + apiKey + RECV_WINDOW + orderJson
        val signature = generateSignature(apiSecret, paramStr)

        val request = Request.Builder()
            .url("$BASE_URL/v5/order/create")
            .addHeader("X-BAPI-API-KEY", apiKey)
            .addHeader("X-BAPI-TIMESTAMP", timestamp)
            .addHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            .addHeader("X-BAPI-SIGN", signature)
            .post(orderJson.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, "Close order failed: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                try {
                    val json = JSONObject(body)
                    val retCode = json.optInt("retCode", -1)
                    if (retCode == 0) {
                        mainHandler.post { callback(true, "Position closed successfully on Bybit") }
                    } else {
                        mainHandler.post { callback(false, "Bybit Error: ${json.optString("retMsg")}") }
                    }
                } catch (e: Exception) {
                    mainHandler.post { callback(false, "Close parsing error: ${e.message}") }
                }
            }
        })
    }
}
