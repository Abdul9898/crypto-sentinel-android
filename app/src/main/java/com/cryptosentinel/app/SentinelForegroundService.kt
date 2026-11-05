package com.cryptosentinel.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SentinelForegroundService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val gson = Gson()

    val trackedCoins = ConcurrentHashMap<String, CoinState>()
    val alertsList = Collections.synchronizedList(mutableListOf<AlertItem>())
    val topGainers = Collections.synchronizedList(mutableListOf<GainerItem>())
    val tradeStories = Collections.synchronizedList(mutableListOf<TradeStory>())
    val paperStats = PaperStats()

    private val recentWhaleTrades = Collections.synchronizedList(mutableListOf<WhaleRecord>())
    private val alertedGainers = ConcurrentHashMap<String, Long>()

    var onStateChangedListener: (() -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val pairs = listOf(
        "BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT",
        "XRPUSDT", "SUIUSDT", "1000PEPEUSDT", "NEARUSDT"
    )

    data class WhaleRecord(
        val timestamp: Long,
        val symbol: String,
        val isBuy: Boolean,
        val usdVal: Double,
        val price: Double
    )

    inner class LocalBinder : Binder() {
        fun getService(): SentinelForegroundService = this@SentinelForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(1001, buildServiceNotification("Connecting to exchange feeds..."))

        for (p in pairs) {
            trackedCoins[p] = CoinState(symbol = p)
        }

        connectWebSocket()
        startGainersScanner()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                "sentinel_service_channel",
                "Sentinel Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Crypto Sentinel running 24/7 in background"
                setShowBadge(false)
            }
            manager.createNotificationChannel(serviceChannel)

            val alertChannel = NotificationChannel(
                "sentinel_alerts_channel",
                "Sentinel High Conviction Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Heads-up notification for Absorptions, Squeezes & Listings"
                enableVibration(true)
                enableLights(true)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildServiceNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, "sentinel_service_channel")
            .setContentTitle("⚡ Crypto Sentinel Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun sendAlertNotification(title: String, message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "sentinel_alerts_channel")
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 250, 150, 250))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun connectWebSocket() {
        val request = Request.Builder()
            .url("wss://stream.bybit.com/v5/public/linear")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val topics = mutableListOf<String>()
                for (s in trackedCoins.keys) {
                    topics.add("publicTrade.$s")
                    topics.add("tickers.$s")
                }
                val subMsg = mapOf("op" to "subscribe", "args" to topics)
                ws.send(gson.toJson(subMsg))
                updateServiceNotification("Monitoring active order flow & alts")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                serviceScope.launch {
                    delay(3000)
                    connectWebSocket()
                }
            }
        })
    }

    private fun startGainersScanner() {
        serviceScope.launch {
            while (isActive) {
                try {
                    fetchTopGainers()
                } catch (_: Exception) {}
                delay(15_000)
            }
        }
    }

    private fun fetchTopGainers() {
        val request = Request.Builder()
            .url("https://api.bybit.com/v5/market/tickers?category=linear")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val bodyStr = response.body?.string() ?: return
            val root = gson.fromJson(bodyStr, JsonObject::class.java) ?: return
            val result = root.getAsJsonObject("result") ?: return
            val list = result.getAsJsonArray("list") ?: return

            val parsed = mutableListOf<GainerItem>()
            for (item in list) {
                val obj = item.asJsonObject
                val sym = obj.get("symbol")?.asString ?: continue
                if (!sym.endsWith("USDT")) continue

                // Exclude US equity / stock perpetuals (SOXX, SOXS, STOCK)
                if (sym.contains("STOCK") || sym in listOf("SOXXUSDT", "SOXSUSDT")) continue

                val turnover = obj.get("turnover24h")?.asString?.toDoubleOrNull() ?: 0.0
                if (turnover < 250_000.0) continue // Skip zero-liquidity tokens

                val pct = (obj.get("price24hPcnt")?.asString?.toDoubleOrNull() ?: 0.0) * 100.0
                val price = obj.get("lastPrice")?.asString?.toDoubleOrNull() ?: 0.0
                val cleanSym = sym.replace("1000", "").replace("USDT", "")

                parsed.add(GainerItem(cleanSym, price, pct, turnover))
            }

            parsed.sortByDescending { it.change24hPct }
            val top20 = parsed.take(20)

            synchronized(topGainers) {
                topGainers.clear()
                topGainers.addAll(top20)
            }

            // Dynamically subscribe to top 3 breakout small alts so we catch their tape live!
            for (g in top20.take(3)) {
                val fullSym = if (g.symbol.endsWith("USDT")) g.symbol else "${g.symbol}USDT"
                if (!trackedCoins.containsKey(fullSym)) {
                    trackedCoins[fullSym] = CoinState(symbol = fullSym, price = g.price)
                    val subMsg = mapOf("op" to "subscribe", "args" to listOf("publicTrade.$fullSym", "tickers.$fullSym"))
                    webSocket?.send(gson.toJson(subMsg))
                }
            }

            // Check for breakout surge alerts (>= +15% 24h change with high volume)
            val now = System.currentTimeMillis()
            for (g in top20) {
                if (g.change24hPct >= 15.0) {
                    val lastAlert = alertedGainers[g.symbol] ?: 0L
                    if (now - lastAlert > 20 * 60 * 1000) { // 20 min cooldown per symbol
                        alertedGainers[g.symbol] = now
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                        val volStr = if (g.turnoverUsd >= 1_000_000) String.format(Locale.US, "$%.1fM", g.turnoverUsd / 1_000_000)
                        else String.format(Locale.US, "$%.0fK", g.turnoverUsd / 1_000)
                        val pStr = if (g.price < 1.0) String.format(Locale.US, "$%.4f", g.price) else String.format(Locale.US, "$%.2f", g.price)
                        val alert = AlertItem(
                            timeStr = timeStr,
                            symbol = g.symbol,
                            type = "BREAKOUT",
                            conviction = "HIGH",
                            message = "🚀 TOP BREAKOUT: ${g.symbol} +${String.format(Locale.US, "%.1f", g.change24hPct)}% (Vol: $volStr) @ $pStr"
                        )
                        addAlert(alert)
                        sendAlertNotification("🚀 Breakout: ${g.symbol} +${String.format(Locale.US, "%.1f", g.change24hPct)}%", "Surging on Bybit with $volStr volume @ $pStr")
                    }
                }
            }

            onStateChangedListener?.invoke()
        }
    }

    private fun handleMessage(jsonText: String) {
        try {
            val root = gson.fromJson(jsonText, JsonObject::class.java) ?: return
            val topic = root.get("topic")?.asString ?: return
            val data = root.get("data") ?: return

            if (topic.startsWith("tickers.")) {
                val sym = topic.substringAfter("tickers.")
                val coin = trackedCoins[sym] ?: return
                val obj = data.asJsonObject

                if (obj.has("lastPrice")) {
                    coin.price = obj.get("lastPrice").asDouble
                    checkOpenPositions(sym, coin.price)
                }
                onStateChangedListener?.invoke()
            } else if (topic.startsWith("publicTrade.")) {
                val sym = topic.substringAfter("publicTrade.")
                val coin = trackedCoins[sym] ?: return
                val arr = data.asJsonArray

                val now = System.currentTimeMillis()
                val tierConfig = TierCalculator.getConfig(sym)

                for (item in arr) {
                    val trade = item.asJsonObject
                    val p = trade.get("p").asDouble
                    val v = trade.get("v").asDouble
                    val side = trade.get("S").asString
                    val isBuy = side == "Buy"

                    coin.price = p
                    val usdVal = p * v
                    val deltaUsd = if (isBuy) usdVal else -usdVal
                    coin.cvd5m += deltaUsd

                    // Check active positions on every trade execution
                    checkOpenPositions(sym, p)

                    // Relative Whale order check based on Coin Tier
                    if (usdVal >= tierConfig.minWhaleOrder) {
                        recentWhaleTrades.add(WhaleRecord(now, sym, isBuy, usdVal, p))
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                        val sideIcon = if (isBuy) "🟢 BUY" else "🔴 SELL"
                        val cleanSym = sym.replace("1000", "").replace("USDT", "")
                        val pStr = if (p < 1.0) String.format(Locale.US, "$%.4f", p) else String.format(Locale.US, "$%.2f", p)
                        val msg = "$sideIcon Whale: $${String.format(Locale.US, "%,.0f", usdVal)} @ $pStr"
                        val alert = AlertItem(
                            timeStr = timeStr,
                            symbol = cleanSym,
                            type = "WHALE_TRADE",
                            conviction = if (usdVal >= tierConfig.clusterThreshold) "HIGH" else "MEDIUM",
                            message = msg
                        )
                        addAlert(alert)
                        if (usdVal >= tierConfig.clusterThreshold) {
                            sendAlertNotification("🐋 Whale Order ($cleanSym)", msg)
                        }

                        // Evaluate Whale Cluster Auto-Trading
                        evaluateWhaleCluster(sym, p, now, timeStr, tierConfig)
                    }
                }
                onStateChangedListener?.invoke()
            }
        } catch (_: Exception) {}
    }

    private fun evaluateWhaleCluster(sym: String, currentPrice: Double, now: Long, timeStr: String, config: CoinTierConfig) {
        // 1. Clean up trades older than 6 seconds
        synchronized(recentWhaleTrades) {
            val cutoff = now - 6000
            recentWhaleTrades.removeAll { it.timestamp < cutoff }
        }

        // 2. Sum recent 6s whale volume for this coin
        var buyVol = 0.0
        var sellVol = 0.0
        synchronized(recentWhaleTrades) {
            for (w in recentWhaleTrades) {
                if (w.symbol == sym) {
                    if (w.isBuy) buyVol += w.usdVal else sellVol += w.usdVal
                }
            }
        }

        val cleanSym = sym.replace("1000", "").replace("USDT", "")

        // Avoid opening multiple positions on same coin
        val hasOpenPos = synchronized(paperStats.openPositions) {
            paperStats.openPositions.any { it.symbol == sym }
        }
        if (hasOpenPos) return

        val clusterThreshold = config.clusterThreshold

        if (buyVol >= clusterThreshold && buyVol > sellVol * 1.5) {
            // Auto ENTER LONG with Tier-Specific TP/SL
            val entry = currentPrice
            val tp = entry * (1.0 + config.tpPct / 100.0)
            val sl = entry * (1.0 - config.slPct / 100.0)
            val pos = PaperPosition(
                symbol = sym,
                side = "LONG",
                entryPrice = entry,
                sizeUsd = 1000.0,
                tpPrice = tp,
                slPrice = sl,
                tpPct = config.tpPct,
                slPct = config.slPct,
                openTimeStr = timeStr,
                triggerReason = "Whale Buy Cluster: $${String.format(Locale.US, "%,.0f", buyVol)}"
            )
            synchronized(paperStats.openPositions) {
                paperStats.openPositions.add(pos)
            }
            val pStr = if (entry < 1.0) String.format(Locale.US, "$%.4f", entry) else String.format(Locale.US, "$%.2f", entry)
            val alert = AlertItem(
                timeStr = timeStr,
                symbol = cleanSym,
                type = "AUTO_TRADE",
                conviction = "HIGH",
                message = "🤖 AUTO LONG $cleanSym @ $pStr | TP: +${config.tpPct}% | SL: -${config.slPct}% [Whale: $${String.format(Locale.US, "%,.0f", buyVol)}]"
            )
            addAlert(alert)
            sendAlertNotification("🤖 Auto-Trade: LONG $cleanSym", "Entered $1,000 LONG @ $pStr | Whale: $${String.format(Locale.US, "%,.0f", buyVol)}")
        } else if (sellVol >= clusterThreshold && sellVol > buyVol * 1.5) {
            // Auto ENTER SHORT with Tier-Specific TP/SL
            val entry = currentPrice
            val tp = entry * (1.0 - config.tpPct / 100.0)
            val sl = entry * (1.0 + config.slPct / 100.0)
            val pos = PaperPosition(
                symbol = sym,
                side = "SHORT",
                entryPrice = entry,
                sizeUsd = 1000.0,
                tpPrice = tp,
                slPrice = sl,
                tpPct = config.tpPct,
                slPct = config.slPct,
                openTimeStr = timeStr,
                triggerReason = "Whale Sell Dump: $${String.format(Locale.US, "%,.0f", sellVol)}"
            )
            synchronized(paperStats.openPositions) {
                paperStats.openPositions.add(pos)
            }
            val pStr = if (entry < 1.0) String.format(Locale.US, "$%.4f", entry) else String.format(Locale.US, "$%.2f", entry)
            val alert = AlertItem(
                timeStr = timeStr,
                symbol = cleanSym,
                type = "AUTO_TRADE",
                conviction = "HIGH",
                message = "🤖 AUTO SHORT $cleanSym @ $pStr | TP: -${config.tpPct}% | SL: +${config.slPct}% [Whale: $${String.format(Locale.US, "%,.0f", sellVol)}]"
            )
            addAlert(alert)
            sendAlertNotification("🤖 Auto-Trade: SHORT $cleanSym", "Entered $1,000 SHORT @ $pStr | Dump: $${String.format(Locale.US, "%,.0f", sellVol)}")
        }
    }

    private fun checkOpenPositions(sym: String, currentPrice: Double) {
        if (currentPrice <= 0) return
        val toClose = mutableListOf<Pair<PaperPosition, Boolean>>()

        synchronized(paperStats.openPositions) {
            val it = paperStats.openPositions.iterator()
            while (it.hasNext()) {
                val pos = it.next()
                if (pos.symbol == sym) {
                    if (pos.side == "LONG") {
                        if (currentPrice >= pos.tpPrice) {
                            toClose.add(Pair(pos, true))
                            it.remove()
                        } else if (currentPrice <= pos.slPrice) {
                            toClose.add(Pair(pos, false))
                            it.remove()
                        }
                    } else if (pos.side == "SHORT") {
                        if (currentPrice <= pos.tpPrice) {
                            toClose.add(Pair(pos, true))
                            it.remove()
                        } else if (currentPrice >= pos.slPrice) {
                            toClose.add(Pair(pos, false))
                            it.remove()
                        }
                    }
                }
            }
        }

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val cleanSym = sym.replace("1000", "").replace("USDT", "")

        for ((pos, isWin) in toClose) {
            val pnlPct = if (isWin) pos.tpPct else -pos.slPct
            val pnlUsd = (pnlPct / 100.0) * pos.sizeUsd
            paperStats.balance += pnlUsd
            val pStr = if (currentPrice < 1.0) String.format(Locale.US, "$%.4f", currentPrice) else String.format(Locale.US, "$%.2f", currentPrice)
            val pnlStr = String.format(Locale.US, "$%,.2f", Math.abs(pnlUsd))

            if (isWin) {
                paperStats.wins++
                val msg = "🏆 [WIN +${pos.tpPct}%] Closed ${pos.side} $cleanSym @ $pStr (+$pnlStr)"
                addAlert(AlertItem(
                    timeStr = timeStr,
                    symbol = cleanSym,
                    type = "PAPER_WIN",
                    conviction = "HIGH",
                    message = msg
                ))
                sendAlertNotification("🏆 Trade Win! +$pnlStr on $cleanSym", msg)
            } else {
                paperStats.losses++
                val msg = "🛑 [SL -${pos.slPct}%] Closed ${pos.side} $cleanSym @ $pStr (-$pnlStr)"
                addAlert(AlertItem(
                    timeStr = timeStr,
                    symbol = cleanSym,
                    type = "PAPER_LOSS",
                    conviction = "MEDIUM",
                    message = msg
                ))
                sendAlertNotification("🛑 Stop-Loss Hit (-$pnlStr) on $cleanSym", msg)
            }

            // Record into Trade Story
            val story = TradeStory(
                symbol = cleanSym,
                side = pos.side,
                entryPrice = pos.entryPrice,
                exitPrice = currentPrice,
                pnlUsd = pnlUsd,
                pnlPct = pnlPct,
                outcome = if (isWin) "WIN (TP)" else "LOSS (SL)",
                entryTime = pos.openTimeStr,
                exitTime = timeStr,
                triggerReason = pos.triggerReason,
                tpPrice = pos.tpPrice,
                slPrice = pos.slPrice
            )
            tradeStories.add(0, story)
            if (tradeStories.size > 40) tradeStories.removeAt(tradeStories.size - 1)
        }
    }

    fun closePositionManually(positionId: String) {
        var closedPos: PaperPosition? = null
        var curPrice = 0.0
        synchronized(paperStats.openPositions) {
            val it = paperStats.openPositions.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.id == positionId) {
                    closedPos = p
                    curPrice = trackedCoins[p.symbol]?.price ?: p.entryPrice
                    it.remove()
                    break
                }
            }
        }

        val pos = closedPos ?: return
        val isLong = pos.side == "LONG"
        val diffPct = if (isLong) ((curPrice - pos.entryPrice) / pos.entryPrice) * 100.0
                      else ((pos.entryPrice - curPrice) / pos.entryPrice) * 100.0
        val pnlUsd = (diffPct / 100.0) * pos.sizeUsd
        paperStats.balance += pnlUsd
        if (pnlUsd >= 0) paperStats.wins++ else paperStats.losses++

        val nowStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val cleanSym = pos.symbol.replace("1000", "").replace("USDT", "")
        val pStr = if (curPrice < 1.0) String.format(Locale.US, "$%.4f", curPrice) else String.format(Locale.US, "$%.2f", curPrice)
        val pnlSign = if (pnlUsd >= 0) "+" else "-"
        val pnlStr = String.format(Locale.US, "%s$%,.2f", pnlSign, Math.abs(pnlUsd))

        val story = TradeStory(
            symbol = cleanSym,
            side = pos.side,
            entryPrice = pos.entryPrice,
            exitPrice = curPrice,
            pnlUsd = pnlUsd,
            pnlPct = diffPct,
            outcome = "MANUAL EXIT",
            entryTime = pos.openTimeStr,
            exitTime = nowStr,
            triggerReason = pos.triggerReason,
            tpPrice = pos.tpPrice,
            slPrice = pos.slPrice
        )
        tradeStories.add(0, story)
        if (tradeStories.size > 40) tradeStories.removeAt(tradeStories.size - 1)

        val msg = "✋ [MANUAL CLOSE] Closed ${pos.side} $cleanSym @ $pStr ($pnlStr)"
        addAlert(AlertItem(
            timeStr = nowStr,
            symbol = cleanSym,
            type = "MANUAL_CLOSE",
            conviction = "HIGH",
            message = msg
        ))
        sendAlertNotification("✋ Position Closed: $cleanSym", msg)
        onStateChangedListener?.invoke()
    }

    fun fetchKlines(symbol: String, callback: (List<CandleStick>) -> Unit) {
        serviceScope.launch {
            try {
                val fullSym = if (symbol.endsWith("USDT")) symbol else "${symbol}USDT"
                val request = Request.Builder()
                    .url("https://api.bybit.com/v5/market/kline?category=linear&symbol=$fullSym&interval=1&limit=35")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) { callback(emptyList()) }
                        return@launch
                    }
                    val body = response.body?.string() ?: return@launch
                    val root = gson.fromJson(body, JsonObject::class.java)
                    val result = root.getAsJsonObject("result")
                    val list = result.getAsJsonArray("list")
                    val candles = mutableListOf<CandleStick>()
                    for (item in list) {
                        val arr = item.asJsonArray
                        val t = arr[0].asString.toLongOrNull() ?: 0L
                        val o = arr[1].asString.toDoubleOrNull() ?: 0.0
                        val h = arr[2].asString.toDoubleOrNull() ?: 0.0
                        val l = arr[3].asString.toDoubleOrNull() ?: 0.0
                        val c = arr[4].asString.toDoubleOrNull() ?: 0.0
                        val v = arr[5].asString.toDoubleOrNull() ?: 0.0
                        candles.add(CandleStick(t, o, h, l, c, v))
                    }
                    candles.reverse() // Chronological order
                    withContext(Dispatchers.Main) {
                        callback(candles)
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { callback(emptyList()) }
            }
        }
    }

    private fun addAlert(alert: AlertItem) {
        alertsList.add(0, alert)
        if (alertsList.size > 50) alertsList.removeAt(alertsList.size - 1)
        onStateChangedListener?.invoke()
    }

    private fun updateServiceNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, buildServiceNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Service stopped")
        serviceScope.cancel()
    }
}
