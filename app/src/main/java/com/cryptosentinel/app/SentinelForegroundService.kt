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
    var settings = TradingSettings()
    var depthRadarState = DepthRadarState()

    private val recentWhaleTrades = Collections.synchronizedList(mutableListOf<WhaleRecord>())
    private val alertedGainers = ConcurrentHashMap<String, Long>()
    private val lastRadarTradeTime = ConcurrentHashMap<String, Long>()

    var onStateChangedListener: (() -> Unit)? = null

    // UI Throttling
    private var lastUiNotifyTime = 0L

    // Notification Throttling
    private var lastNotificationTime = 0L

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

        // Load persisted settings
        settings = SettingsManager.loadSettings(this)
        paperStats.reset(settings.startingBalance)

        for (p in pairs) {
            trackedCoins[p] = CoinState(symbol = p)
        }

        connectWebSocket()
        startGainersScanner()
        startDepthRadarScanner()
    }

    fun scheduleUiUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastUiNotifyTime >= 400L) {
            lastUiNotifyTime = now
            onStateChangedListener?.invoke()
        }
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
        try {
            val now = System.currentTimeMillis()
            if (now - lastNotificationTime < 3000L) return // Rate-limit to 1 per 3 seconds
            lastNotificationTime = now

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
        } catch (_: Exception) {}
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

    private fun startDepthRadarScanner() {
        serviceScope.launch {
            while (isActive) {
                try {
                    fetchDepthRadar()
                } catch (_: Exception) {}
                delay(3500)
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

            val parsedAll = mutableListOf<GainerItem>()
            for (item in list) {
                val obj = item.asJsonObject
                val sym = obj.get("symbol")?.asString ?: continue
                if (!sym.endsWith("USDT")) continue

                // Exclude traditional stock-named perpetuals
                if (sym.contains("STOCK") || sym in listOf("SOXXUSDT", "SOXSUSDT")) continue

                val turnover = obj.get("turnover24h")?.asString?.toDoubleOrNull() ?: 0.0
                if (turnover < 300_000.0) continue

                val p24h = (obj.get("price24hPcnt")?.asString?.toDoubleOrNull() ?: 0.0) * 100.0
                val price = obj.get("lastPrice")?.asString?.toDoubleOrNull() ?: 0.0
                val prev1h = obj.get("prevPrice1h")?.asString?.toDoubleOrNull() ?: price
                val cleanSym = sym.replace("1000", "").replace("USDT", "")

                // Fresh 1-hour price velocity %
                val velocity1h = if (prev1h > 0) ((price - prev1h) / prev1h) * 100.0 else 0.0

                parsedAll.add(GainerItem(cleanSym, price, p24h, turnover, velocity1h))
            }

            // Fresh Early Ignition Movers:
            // 24h change is calm (-8% to +15% -> has NOT already pumped +30%, +50%)
            // and 1h velocity is showing fresh ignition (abs >= 0.8%)
            val earlyMovers = parsedAll.filter { it.change24hPct in -8.0..15.0 && Math.abs(it.velocity1hPct) >= 0.8 }
                .sortedByDescending { Math.abs(it.velocity1hPct) }

            val otherMovers = parsedAll.filterNot { it in earlyMovers }
                .sortedByDescending { it.change24hPct }

            val combined = (earlyMovers + otherMovers).take(20)

            synchronized(topGainers) {
                topGainers.clear()
                topGainers.addAll(combined)
            }

            // Auto-subscribe WebSocket to top early ignition coins
            for (g in combined.take(5)) {
                val fullSym = if (g.symbol.endsWith("USDT")) g.symbol else "${g.symbol}USDT"
                if (!trackedCoins.containsKey(fullSym)) {
                    trackedCoins[fullSym] = CoinState(symbol = fullSym, price = g.price)
                    val subMsg = mapOf("op" to "subscribe", "args" to listOf("publicTrade.$fullSym", "tickers.$fullSym"))
                    webSocket?.send(gson.toJson(subMsg))
                }
            }

            // New Engine Signal Alert: Fresh Ignition Spark (velocity >= 1.5% from calm base)
            val now = System.currentTimeMillis()
            for (g in earlyMovers.take(8)) {
                if (Math.abs(g.velocity1hPct) >= 1.5) {
                    val lastAlert = alertedGainers[g.symbol] ?: 0L
                    if (now - lastAlert > 15 * 60 * 1000L) {
                        alertedGainers[g.symbol] = now
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                        val volStr = if (g.turnoverUsd >= 1_000_000) String.format(Locale.US, "$%.1fM", g.turnoverUsd / 1_000_000)
                        else String.format(Locale.US, "$%.0fK", g.turnoverUsd / 1_000)
                        val pStr = if (g.price < 1.0) String.format(Locale.US, "$%.4f", g.price) else String.format(Locale.US, "$%.2f", g.price)
                        val sideLabel = if (g.velocity1hPct > 0) "🟢 EARLY LONG" else "🔴 EARLY SHORT"

                        val alert = AlertItem(
                            timeStr = timeStr,
                            symbol = g.symbol,
                            type = "EARLY_IGNITION",
                            conviction = "HIGH",
                            message = "🔥 $sideLabel: ${g.symbol} ${String.format(Locale.US, "%+.1f%%", g.velocity1hPct)} (1h Ignition | 24h: ${String.format(Locale.US, "%+.1f%%", g.change24hPct)}) @ $pStr"
                        )
                        addAlert(alert)
                        sendAlertNotification("🔥 Early Ignition: $sideLabel ${g.symbol}", "Surging ${String.format(Locale.US, "%+.1f%%", g.velocity1hPct)} in 1h from quiet base @ $pStr | Vol: $volStr")
                    }
                }
            }

            scheduleUiUpdate()
        }
    }

    private fun fetchDepthRadar() {
        val candidateSyms = mutableListOf<String>()

        // 1. Prioritize Top Early Ignition Movers (fresh coins just beginning to move)
        synchronized(topGainers) {
            for (g in topGainers.take(5)) {
                val s = if (g.symbol.endsWith("USDT")) g.symbol else "${g.symbol}USDT"
                candidateSyms.add(s)
            }
        }

        // 2. Also include any currently open positions to monitor active walls
        val openPosSyms = synchronized(paperStats.openPositions) {
            paperStats.openPositions.map { it.symbol }
        }
        for (sym in openPosSyms) {
            if (!candidateSyms.contains(sym)) {
                candidateSyms.add(sym)
            }
        }

        if (candidateSyms.isEmpty()) candidateSyms.add("BTCUSDT")

        var bestResult: DepthRadarState? = null
        var bestImbalanceScore = -1.0

        for (targetSym in candidateSyms) {
            val fullSym = if (targetSym.endsWith("USDT")) targetSym else "${targetSym}USDT"
            try {
                val request = Request.Builder()
                    .url("https://api.bybit.com/v5/market/orderbook?category=linear&symbol=$fullSym&limit=50")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyStr = response.body?.string() ?: return@use
                    val root = gson.fromJson(bodyStr, JsonObject::class.java) ?: return@use
                    val result = root.getAsJsonObject("result") ?: return@use
                    val bArray = result.getAsJsonArray("b") ?: return@use
                    val aArray = result.getAsJsonArray("a") ?: return@use

                    var bidVol = 0.0
                    var askVol = 0.0
                    var topBidP = 0.0
                    var topBidUsd = 0.0
                    var topAskP = 0.0
                    var topAskUsd = 0.0

                    for (item in bArray) {
                        val arr = item.asJsonArray
                        val p = arr[0].asString.toDoubleOrNull() ?: 0.0
                        val q = arr[1].asString.toDoubleOrNull() ?: 0.0
                        val usd = p * q
                        bidVol += usd
                        if (usd > topBidUsd) {
                            topBidUsd = usd
                            topBidP = p
                        }
                    }

                    for (item in aArray) {
                        val arr = item.asJsonArray
                        val p = arr[0].asString.toDoubleOrNull() ?: 0.0
                        val q = arr[1].asString.toDoubleOrNull() ?: 0.0
                        val usd = p * q
                        askVol += usd
                        if (usd > topAskUsd) {
                            topAskUsd = usd
                            topAskP = p
                        }
                    }

                    val total = bidVol + askVol
                    val ratio = if (total > 0) (bidVol / total) * 100.0 else 50.0
                    val cleanSym = fullSym.replace("1000", "").replace("USDT", "")

                    val statusText = when {
                        ratio >= 60.0 -> "🟢 HEAVY BUY WALLS (+${String.format(Locale.US, "%.0f", ratio - 50)}%)"
                        ratio <= 40.0 -> "🔴 HEAVY SELL WALLS (-${String.format(Locale.US, "%.0f", 50 - ratio)}%)"
                        else -> "⚖️ BALANCED L2 DEPTH"
                    }

                    val radarState = DepthRadarState(
                        symbol = cleanSym,
                        bidVolumeUsd = bidVol,
                        askVolumeUsd = askVol,
                        bidRatioPct = ratio,
                        topBidWallPrice = topBidP,
                        topBidWallUsd = topBidUsd,
                        topAskWallPrice = topAskP,
                        topAskWallUsd = topAskUsd,
                        status = statusText
                    )

                    val imbalanceScore = Math.abs(ratio - 50.0)
                    if (bestResult == null || imbalanceScore > bestImbalanceScore) {
                        bestImbalanceScore = imbalanceScore
                        bestResult = radarState
                    }

                    // Evaluate Radar Signal Auto-Trade (Ratio >= 60% or <= 40%)
                    evaluateRadarAutoTrade(fullSym, cleanSym, ratio, topBidUsd, topBidP, topAskUsd, topAskP)
                }
            } catch (_: Exception) {}
        }

        bestResult?.let {
            depthRadarState = it
            scheduleUiUpdate()
        }
    }

    private fun evaluateRadarAutoTrade(
        fullSym: String,
        cleanSym: String,
        ratio: Double,
        topBidUsd: Double,
        topBidP: Double,
        topAskUsd: Double,
        topAskP: Double
    ) {
        val now = System.currentTimeMillis()
        val lastTraded = lastRadarTradeTime[cleanSym] ?: 0L
        if (now - lastTraded < 180_000L) return // 3-minute cooldown

        val totalOpen = synchronized(paperStats.openPositions) { paperStats.openPositions.size }
        if (totalOpen >= 3) return // Max 3 simultaneous open positions

        val hasOpenPosOnCoin = synchronized(paperStats.openPositions) {
            paperStats.openPositions.any { it.symbol == fullSym }
        }
        if (hasOpenPosOnCoin) return

        val tierConfig = TierCalculator.getConfig(fullSym)
        val minWallSize = tierConfig.minWhaleOrder * 0.7

        if (ratio >= 60.0 && topBidUsd >= minWallSize) {
            // BULLISH RADAR SIGNAL -> AUTO ENTER LONG!
            lastRadarTradeTime[cleanSym] = now
            val currentPrice = trackedCoins[fullSym]?.price.takeIf { it != null && it > 0 } ?: topBidP
            if (currentPrice > 0) {
                val entry = currentPrice
                val tp = entry * (1.0 + tierConfig.tpPct / 100.0)
                val sl = entry * (1.0 - tierConfig.slPct / 100.0)
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                val pos = PaperPosition(
                    symbol = fullSym,
                    side = "LONG",
                    entryPrice = entry,
                    sizeUsd = settings.positionSizeUsd,
                    leverage = settings.leverage,
                    tpPrice = tp,
                    slPrice = sl,
                    tpPct = tierConfig.tpPct,
                    slPct = tierConfig.slPct,
                    openTimeStr = timeStr,
                    triggerReason = "Radar Buy Wall: ${ratio.toInt()}% Bids ($${String.format(Locale.US, "%,.0f", topBidUsd)})"
                )
                synchronized(paperStats.openPositions) {
                    paperStats.openPositions.add(pos)
                }
                val pStr = if (entry < 1.0) String.format(Locale.US, "$%.4f", entry) else String.format(Locale.US, "$%.2f", entry)
                val alert = AlertItem(
                    timeStr = timeStr,
                    symbol = cleanSym,
                    type = "RADAR_TRADE",
                    conviction = "HIGH",
                    message = "🎯 RADAR LONG $cleanSym @ $pStr | Bids: ${ratio.toInt()}% | Wall: $${String.format(Locale.US, "%,.0f", topBidUsd)} | ${settings.leverage}x"
                )
                addAlert(alert)
                sendAlertNotification("🎯 Radar Signal: LONG $cleanSym", "Entered LONG @ $pStr (${settings.leverage}x) | ${ratio.toInt()}% Bids")
                scheduleUiUpdate(force = true)
            }
        } else if (ratio <= 40.0 && topAskUsd >= minWallSize) {
            // BEARISH RADAR SIGNAL -> AUTO ENTER SHORT!
            lastRadarTradeTime[cleanSym] = now
            val currentPrice = trackedCoins[fullSym]?.price.takeIf { it != null && it > 0 } ?: topAskP
            if (currentPrice > 0) {
                val entry = currentPrice
                val tp = entry * (1.0 - tierConfig.tpPct / 100.0)
                val sl = entry * (1.0 + tierConfig.slPct / 100.0)
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                val pos = PaperPosition(
                    symbol = fullSym,
                    side = "SHORT",
                    entryPrice = entry,
                    sizeUsd = settings.positionSizeUsd,
                    leverage = settings.leverage,
                    tpPrice = tp,
                    slPrice = sl,
                    tpPct = tierConfig.tpPct,
                    slPct = tierConfig.slPct,
                    openTimeStr = timeStr,
                    triggerReason = "Radar Sell Wall: ${(100 - ratio).toInt()}% Asks ($${String.format(Locale.US, "%,.0f", topAskUsd)})"
                )
                synchronized(paperStats.openPositions) {
                    paperStats.openPositions.add(pos)
                }
                val pStr = if (entry < 1.0) String.format(Locale.US, "$%.4f", entry) else String.format(Locale.US, "$%.2f", entry)
                val alert = AlertItem(
                    timeStr = timeStr,
                    symbol = cleanSym,
                    type = "RADAR_TRADE",
                    conviction = "HIGH",
                    message = "🎯 RADAR SHORT $cleanSym @ $pStr | Asks: ${(100 - ratio).toInt()}% | Wall: $${String.format(Locale.US, "%,.0f", topAskUsd)} | ${settings.leverage}x"
                )
                addAlert(alert)
                sendAlertNotification("🎯 Radar Signal: SHORT $cleanSym", "Entered SHORT @ $pStr (${settings.leverage}x) | ${(100 - ratio).toInt()}% Asks")
                scheduleUiUpdate(force = true)
            }
        }
    }

    fun executeRadarTrade(side: String) {
        val radar = depthRadarState
        if (radar.symbol.isEmpty()) return
        val fullSym = if (radar.symbol.endsWith("USDT")) radar.symbol else "${radar.symbol}USDT"
        val tierConfig = TierCalculator.getConfig(fullSym)
        val curPrice = trackedCoins[fullSym]?.price.takeIf { it != null && it > 0 }
            ?: if (side == "LONG") radar.topBidWallPrice else radar.topAskWallPrice
        if (curPrice <= 0) return

        val isLong = side == "LONG"
        val tp = if (isLong) curPrice * (1.0 + tierConfig.tpPct / 100.0) else curPrice * (1.0 - tierConfig.tpPct / 100.0)
        val sl = if (isLong) curPrice * (1.0 - tierConfig.slPct / 100.0) else curPrice * (1.0 + tierConfig.slPct / 100.0)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val pos = PaperPosition(
            symbol = fullSym,
            side = side,
            entryPrice = curPrice,
            sizeUsd = settings.positionSizeUsd,
            leverage = settings.leverage,
            tpPrice = tp,
            slPrice = sl,
            tpPct = tierConfig.tpPct,
            slPct = tierConfig.slPct,
            openTimeStr = timeStr,
            triggerReason = "1-Tap Radar Trade (${radar.status})"
        )
        synchronized(paperStats.openPositions) {
            paperStats.openPositions.add(pos)
        }
        val pStr = if (curPrice < 1.0) String.format(Locale.US, "$%.4f", curPrice) else String.format(Locale.US, "$%.2f", curPrice)
        val alert = AlertItem(
            timeStr = timeStr,
            symbol = radar.symbol,
            type = "MANUAL_TRADE",
            conviction = "HIGH",
            message = "⚡ 1-TAP RADAR $side ${radar.symbol} @ $pStr | ${settings.leverage}x"
        )
        addAlert(alert)
        sendAlertNotification("⚡ Trade Entered: $side ${radar.symbol}", "Entered $side @ $pStr (${settings.leverage}x)")
        scheduleUiUpdate(force = true)
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
                scheduleUiUpdate()
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

                    checkOpenPositions(sym, p)

                    if (usdVal >= tierConfig.minWhaleOrder) {
                        recentWhaleTrades.add(WhaleRecord(now, sym, isBuy, usdVal, p))
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                        evaluateWhaleCluster(sym, p, now, timeStr, tierConfig)
                    }
                }
                scheduleUiUpdate()
            }
        } catch (_: Exception) {}
    }

    private fun evaluateWhaleCluster(sym: String, currentPrice: Double, now: Long, timeStr: String, config: CoinTierConfig) {
        synchronized(recentWhaleTrades) {
            val cutoff = now - 6000
            recentWhaleTrades.removeAll { it.timestamp < cutoff }
        }

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

        val hasOpenPos = synchronized(paperStats.openPositions) {
            paperStats.openPositions.any { it.symbol == sym }
        }
        if (hasOpenPos) return

        val totalOpen = synchronized(paperStats.openPositions) { paperStats.openPositions.size }
        if (totalOpen >= 3) return

        val clusterThreshold = config.clusterThreshold

        if (buyVol >= clusterThreshold && buyVol > sellVol * 1.5) {
            val entry = currentPrice
            val tp = entry * (1.0 + config.tpPct / 100.0)
            val sl = entry * (1.0 - config.slPct / 100.0)
            val pos = PaperPosition(
                symbol = sym,
                side = "LONG",
                entryPrice = entry,
                sizeUsd = settings.positionSizeUsd,
                leverage = settings.leverage,
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
                message = "🤖 AUTO LONG $cleanSym @ $pStr | TP: +${config.tpPct}% | SL: -${config.slPct}% | ${settings.leverage}x [$${String.format(Locale.US, "%,.0f", buyVol)}]"
            )
            addAlert(alert)
            sendAlertNotification("🤖 Auto-Trade: LONG $cleanSym", "Entered $${settings.positionSizeUsd.toInt()} (${settings.leverage}x) LONG @ $pStr | Whale: $${String.format(Locale.US, "%,.0f", buyVol)}")
            scheduleUiUpdate(force = true)
        } else if (sellVol >= clusterThreshold && sellVol > buyVol * 1.5) {
            val entry = currentPrice
            val tp = entry * (1.0 - config.tpPct / 100.0)
            val sl = entry * (1.0 + config.slPct / 100.0)
            val pos = PaperPosition(
                symbol = sym,
                side = "SHORT",
                entryPrice = entry,
                sizeUsd = settings.positionSizeUsd,
                leverage = settings.leverage,
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
                message = "🤖 AUTO SHORT $cleanSym @ $pStr | TP: -${config.tpPct}% | SL: +${config.slPct}% | ${settings.leverage}x [$${String.format(Locale.US, "%,.0f", sellVol)}]"
            )
            addAlert(alert)
            sendAlertNotification("🤖 Auto-Trade: SHORT $cleanSym", "Entered $${settings.positionSizeUsd.toInt()} (${settings.leverage}x) SHORT @ $pStr | Dump: $${String.format(Locale.US, "%,.0f", sellVol)}")
            scheduleUiUpdate(force = true)
        }
    }

    private fun checkOpenPositions(sym: String, currentPrice: Double) {
        if (currentPrice <= 0) return
        val toClose = mutableListOf<Triple<PaperPosition, String, Double>>()

        synchronized(paperStats.openPositions) {
            val it = paperStats.openPositions.iterator()
            while (it.hasNext()) {
                val pos = it.next()
                if (pos.symbol == sym) {
                    val isLong = pos.side == "LONG"
                    val diffPct = if (isLong) ((currentPrice - pos.entryPrice) / pos.entryPrice) * 100.0
                                  else ((pos.entryPrice - currentPrice) / pos.entryPrice) * 100.0

                    // 1. Peak tracking
                    if (isLong) {
                        if (currentPrice > pos.highestPrice) pos.highestPrice = currentPrice
                    } else {
                        if (currentPrice < pos.lowestPrice) pos.lowestPrice = currentPrice
                    }

                    // 2. Auto Break-Even Check
                    if (settings.isBreakEvenEnabled && !pos.isBreakEvenSet) {
                        if (diffPct >= settings.beTriggerPct) {
                            pos.isBreakEvenSet = true
                            pos.statusNote = "🛡️ BE LOCKED"
                            pos.slPrice = if (isLong) pos.entryPrice * 1.0006 else pos.entryPrice * 0.9994
                            val cleanSym = sym.replace("1000", "").replace("USDT", "")
                            sendAlertNotification(
                                "🛡️ Break-Even Locked ($cleanSym)",
                                "Stop-loss raised to entry @ $${String.format(Locale.US, "%.4f", pos.slPrice)} (+${String.format(Locale.US, "%.1f", diffPct)}% profit secured)"
                            )
                        }
                    }

                    // 3. Trailing Stop Check
                    if (settings.isTrailingEnabled && diffPct >= settings.trailingTriggerPct) {
                        pos.isTrailingActive = true
                        pos.statusNote = "🚀 TRAILING SL"
                        if (isLong) {
                            val trailSl = pos.highestPrice * (1.0 - settings.trailingDistancePct / 100.0)
                            if (trailSl > pos.slPrice) pos.slPrice = trailSl
                        } else {
                            val trailSl = pos.lowestPrice * (1.0 + settings.trailingDistancePct / 100.0)
                            if (trailSl < pos.slPrice) pos.slPrice = trailSl
                        }
                    }

                    // 4. Trigger Check
                    if (isLong) {
                        if (currentPrice >= pos.tpPrice) {
                            toClose.add(Triple(pos, "WIN (TP)", currentPrice))
                            it.remove()
                        } else if (currentPrice <= pos.slPrice) {
                            val outcome = if (pos.isTrailingActive && currentPrice > pos.entryPrice) "WIN (TRAIL)"
                                          else if (pos.isBreakEvenSet && currentPrice >= pos.entryPrice * 0.999) "BREAK-EVEN"
                                          else "LOSS (SL)"
                            toClose.add(Triple(pos, outcome, currentPrice))
                            it.remove()
                        }
                    } else {
                        if (currentPrice <= pos.tpPrice) {
                            toClose.add(Triple(pos, "WIN (TP)", currentPrice))
                            it.remove()
                        } else if (currentPrice >= pos.slPrice) {
                            val outcome = if (pos.isTrailingActive && currentPrice < pos.entryPrice) "WIN (TRAIL)"
                                          else if (pos.isBreakEvenSet && currentPrice <= pos.entryPrice * 1.001) "BREAK-EVEN"
                                          else "LOSS (SL)"
                            toClose.add(Triple(pos, outcome, currentPrice))
                            it.remove()
                        }
                    }
                }
            }
        }

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val cleanSym = sym.replace("1000", "").replace("USDT", "")

        for ((pos, outcome, exitPrice) in toClose) {
            val isLong = pos.side == "LONG"
            val rawDiffPct = if (isLong) ((exitPrice - pos.entryPrice) / pos.entryPrice) * 100.0
                             else ((pos.entryPrice - exitPrice) / pos.entryPrice) * 100.0
            val pnlUsd = (rawDiffPct / 100.0) * pos.sizeUsd * pos.leverage
            paperStats.balance += pnlUsd

            val isWin = pnlUsd >= 0.0
            if (isWin) paperStats.wins++ else paperStats.losses++

            val pStr = if (exitPrice < 1.0) String.format(Locale.US, "$%.4f", exitPrice) else String.format(Locale.US, "$%.2f", exitPrice)
            val pnlStr = String.format(Locale.US, "%s$%,.2f", if (pnlUsd >= 0) "+" else "-", Math.abs(pnlUsd))
            val signPct = String.format(Locale.US, "%+.1f%%", rawDiffPct * pos.leverage)

            val icon = when (outcome) {
                "WIN (TP)" -> "🏆"
                "WIN (TRAIL)" -> "🚀"
                "BREAK-EVEN" -> "🛡️"
                else -> "🛑"
            }

            val msg = "$icon [$outcome] Closed ${pos.side} $cleanSym @ $pStr ($pnlStr / $signPct)"
            addAlert(AlertItem(
                timeStr = timeStr,
                symbol = cleanSym,
                type = if (isWin) "PAPER_WIN" else "PAPER_LOSS",
                conviction = "HIGH",
                message = msg
            ))
            sendAlertNotification("$icon $outcome: $cleanSym ($pnlStr)", msg)

            val story = TradeStory(
                symbol = cleanSym,
                side = pos.side,
                entryPrice = pos.entryPrice,
                exitPrice = exitPrice,
                pnlUsd = pnlUsd,
                pnlPct = rawDiffPct * pos.leverage,
                outcome = outcome,
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
        val pnlUsd = (diffPct / 100.0) * pos.sizeUsd * pos.leverage
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
            pnlPct = diffPct * pos.leverage,
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
        scheduleUiUpdate(force = true)
    }

    fun fetchKlines(symbol: String, interval: String = "1", callback: (List<CandleStick>) -> Unit) {
        serviceScope.launch {
            try {
                val fullSym = if (symbol.endsWith("USDT")) symbol else "${symbol}USDT"
                val request = Request.Builder()
                    .url("https://api.bybit.com/v5/market/kline?category=linear&symbol=$fullSym&interval=$interval&limit=35")
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

    fun updateSettings(newSettings: TradingSettings) {
        settings = newSettings
        SettingsManager.saveSettings(this, newSettings)
        scheduleUiUpdate(force = true)
    }

    fun resetPaperStats() {
        paperStats.reset(settings.startingBalance)
        tradeStories.clear()
        scheduleUiUpdate(force = true)
    }

    private fun addAlert(alert: AlertItem) {
        alertsList.add(0, alert)
        if (alertsList.size > 50) alertsList.removeAt(alertsList.size - 1)
        scheduleUiUpdate()
    }

    private fun updateServiceNotification(text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(1001, buildServiceNotification(text))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Service stopped")
        serviceScope.cancel()
    }
}
