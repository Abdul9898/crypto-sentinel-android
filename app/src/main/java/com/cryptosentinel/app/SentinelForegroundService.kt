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
    var proEngineState = ProEngineState()
    private var focalSymbol = "BTCUSDT"

    private val alertedGainers = ConcurrentHashMap<String, Long>()

    // Triple-Timeframe MACD(5,13,1) Engine
    val coinMacdStatus = ConcurrentHashMap<String, ConcurrentHashMap<String, MacdStatus>>()
    private val lastMacdTradeTime = ConcurrentHashMap<String, Long>()

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

    inner class LocalBinder : Binder() {
        fun getService(): SentinelForegroundService = this@SentinelForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(1001, buildServiceNotification("Connecting to exchange feeds..."))

        // Load persisted settings
        settings = SettingsManager.loadSettings(this)

        // Restore persisted paper positions and trade stories (survives app close & reboot)
        val (savedStats, savedStories) = StateManager.loadState(this, settings.startingBalance)
        paperStats.initialCapital = savedStats.initialCapital
        paperStats.balance = savedStats.balance
        paperStats.wins = savedStats.wins
        paperStats.losses = savedStats.losses
        synchronized(paperStats.openPositions) {
            paperStats.openPositions.clear()
            paperStats.openPositions.addAll(savedStats.openPositions)
        }
        synchronized(tradeStories) {
            tradeStories.clear()
            tradeStories.addAll(savedStories)
        }

        for (p in pairs) {
            trackedCoins[p] = CoinState(symbol = p)
        }

        connectWebSocket()
        startGainersScanner()
        startMacdMultiTimeframeScanner()
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
                val prev24h = obj.get("prevPrice24h")?.asString?.toDoubleOrNull() ?: price
                val prev1h = obj.get("prevPrice1h")?.asString?.toDoubleOrNull() ?: price
                val cleanSym = sym.replace("1000", "").replace("USDT", "")

                // 12-Hour baseline change % (Estimated 12h mark)
                val prev12h = (prev24h + price) / 2.0
                val p12h = if (prev12h > 0) ((price - prev12h) / prev12h) * 100.0 else p24h * 0.5

                // Fresh 1-hour price velocity %
                val velocity1h = if (prev1h > 0) ((price - prev1h) / prev1h) * 100.0 else 0.0

                parsedAll.add(GainerItem(cleanSym, price, p12h, turnover, velocity1h, p24h))
            }

            // Fresh Early Ignition Movers:
            // 1. Fresh Early Bullish Ignitions (LONG):
            // 12h baseline is calm (-5% to +10%) AND 1h velocity is positive and accelerating (>= +1.0%)
            val earlyLongs = parsedAll.filter { it.change12hPct in -5.0..10.0 && it.velocity1hPct >= 1.0 }
                .sortedByDescending { it.velocity1hPct }

            // 2. Fresh Early Bearish Breakdowns (SHORT):
            // 12h baseline is calm (-10% to +5%) AND 1h velocity is negative (<= -1.2%)
            val earlyShorts = parsedAll.filter { it.change12hPct in -10.0..5.0 && it.velocity1hPct <= -1.2 }
                .sortedBy { it.velocity1hPct }

            val earlyMovers = earlyLongs + earlyShorts
            val otherMovers = parsedAll.filterNot { it in earlyMovers }
                .sortedByDescending { it.change12hPct }

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

            // All signals and auto-trades are now driven exclusively by the Triple-Timeframe MACD(5,13,1) Engine
            scheduleUiUpdate()
        }
    }

    fun selectFocalSymbol(sym: String) {
        val fullSym = if (sym.endsWith("USDT")) sym else "${sym}USDT"
        focalSymbol = fullSym
        updateProEngineState()
        scheduleUiUpdate(force = true)
    }

    fun updateProEngineState() {
        val fullSym = focalSymbol
        val cleanSym = fullSym.replace("1000", "").replace("USDT", "")
        val price = trackedCoins[fullSym]?.price ?: 0.0
        val gainer = synchronized(topGainers) { topGainers.find { it.symbol == cleanSym } }
        val change12h = gainer?.change12hPct ?: 0.0
        val velocity1h = gainer?.velocity1hPct ?: 0.0

        val m5 = coinMacdStatus[fullSym]?.get("5")
        val m15 = coinMacdStatus[fullSym]?.get("15")
        val m60 = coinMacdStatus[fullSym]?.get("60")

        val bias5 = if (m5 != null) (if (m5.isBullish) "BULLISH" else "BEARISH") else "SCANNING..."
        val bias15 = if (m15 != null) (if (m15.isBullish) "BULLISH" else "BEARISH") else "SCANNING..."
        val bias1h = if (m60 != null) (if (m60.isBullish) "BULLISH" else "BEARISH") else "SCANNING..."

        proEngineState = ProEngineState(
            symbol = cleanSym,
            currentPrice = price,
            change12hPct = change12h,
            velocity1hPct = velocity1h,
            macd5mBias = bias5,
            macd15mBias = bias15,
            macd1hBias = bias1h,
            lastSignalTime = proEngineState.lastSignalTime,
            lastSignalText = proEngineState.lastSignalText
        )
    }

    // =========================================================================
    // TRIPLE-TIMEFRAME MACD(5, 13, 1) SCANNER & AUTO-TRADE ENGINE
    // Monitored Timeframes: 5m, 15m, 1h
    // Trigger Condition: Zero-Line Cross (DIF crosses > 0 for LONG, < 0 for SHORT)
    // =========================================================================
    private fun startMacdMultiTimeframeScanner() {
        serviceScope.launch {
            delay(4000)
            val intervals = listOf("5", "15", "60") // 5m, 15m, 1h
            val tfLabels = mapOf("5" to "5m", "15" to "15m", "60" to "1h")

            while (isActive) {
                try {
                    // Collect candidate coins: dynamic top movers from topGainers + core majors
                    val candidates = mutableListOf<String>()
                    synchronized(topGainers) {
                        for (g in topGainers.take(8)) {
                            val full = if (g.symbol.endsWith("USDT")) g.symbol else "${g.symbol}USDT"
                            if (!candidates.contains(full)) candidates.add(full)
                        }
                    }
                    for (p in listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")) {
                        if (!candidates.contains(p)) candidates.add(p)
                    }

                    for (sym in candidates) {
                        for (interval in intervals) {
                            if (!isActive) break
                            val tfLabel = tfLabels[interval] ?: "${interval}m"
                            try {
                                val request = Request.Builder()
                                    .url("https://api.bybit.com/v5/market/kline?category=linear&symbol=$sym&interval=$interval&limit=25")
                                    .build()

                                client.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val body = response.body?.string()
                                        if (!body.isNullOrEmpty()) {
                                            val root = gson.fromJson(body, JsonObject::class.java)
                                            val result = root.getAsJsonObject("result")
                                            val list = result?.getAsJsonArray("list")
                                            if (list != null && list.size() >= 14) {
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

                                                val macd = computeMacd5_13_1(candles, tfLabel)
                                                if (macd != null) {
                                                    val map = coinMacdStatus.getOrPut(sym) { ConcurrentHashMap() }
                                                    map[interval] = macd

                                                    // Trigger trade directly if Zero-Cross fired on this timeframe
                                                    evaluateMacdTrigger(sym, interval, tfLabel, macd, candles.last().close)
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                            delay(120) // Politeness throttle between requests
                        }
                    }
                    updateProEngineState()
                    scheduleUiUpdate()
                } catch (_: Exception) {}
                delay(8000) // Scan cycle repeats every 8 seconds
            }
        }
    }

    private fun computeMacd5_13_1(candles: List<CandleStick>, tfLabel: String): MacdStatus? {
        if (candles.size < 14) return null
        val mult5 = 2.0 / (5.0 + 1.0)
        val mult13 = 2.0 / (13.0 + 1.0)

        var ema5 = candles[0].close
        var ema13 = candles[0].close
        var prevDif = 0.0
        var currentDif = 0.0

        for (i in candles.indices) {
            val c = candles[i]
            ema5 = (c.close - ema5) * mult5 + ema5
            ema13 = (c.close - ema13) * mult13 + ema13
            val dif = ema5 - ema13
            if (i == candles.size - 2) {
                prevDif = dif
            } else if (i == candles.size - 1) {
                currentDif = dif
            }
        }

        val isCrossUp = prevDif <= 0.0 && currentDif > 0.0
        val isCrossDown = prevDif >= 0.0 && currentDif < 0.0

        return MacdStatus(
            timeframe = tfLabel,
            dif = currentDif,
            prevDif = prevDif,
            isBullish = currentDif > 0.0,
            isCrossUp = isCrossUp,
            isCrossDown = isCrossDown
        )
    }

    private fun evaluateMacdTrigger(
        sym: String,
        interval: String,
        tfLabel: String,
        macd: MacdStatus,
        lastPrice: Double
    ) {
        if (!macd.isCrossUp && !macd.isCrossDown) return

        val isLong = macd.isCrossUp
        val side = if (isLong) "LONG" else "SHORT"
        val cooldownKey = "${sym}_${interval}_$side"
        val now = System.currentTimeMillis()
        val cooldownMs = when (interval) {
            "5" -> 5 * 60 * 1000L      // 5 min cooldown
            "15" -> 15 * 60 * 1000L    // 15 min cooldown
            "60" -> 45 * 60 * 1000L    // 45 min cooldown
            else -> 10 * 60 * 1000L
        }

        val lastTrigger = lastMacdTradeTime[cooldownKey] ?: 0L
        if (now - lastTrigger < cooldownMs) return

        lastMacdTradeTime[cooldownKey] = now

        val cleanSym = sym.replace("1000", "").replace("USDT", "")
        val currentPrice = trackedCoins[sym]?.price.takeIf { it != null && it > 0 } ?: lastPrice
        if (currentPrice <= 0) return

        val pStr = if (currentPrice < 1.0) String.format(Locale.US, "$%.4f", currentPrice) else String.format(Locale.US, "$%.2f", currentPrice)
        val difStr = String.format(Locale.US, "%+.6f", macd.dif)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))

        // Direct Auto-Trade Execution
        val totalOpen = synchronized(paperStats.openPositions) { paperStats.openPositions.size }
        val hasOpenForSym = synchronized(paperStats.openPositions) {
            paperStats.openPositions.any { it.symbol == sym }
        }

        if (totalOpen < 3 && !hasOpenForSym) {
            val tierConfig = TierCalculator.getConfig(sym)
            val targetProfit = if (settings.targetProfitUsd > 0.0) settings.targetProfitUsd else 2.0
            val notional = settings.positionSizeUsd * settings.leverage
            val quickTpPct = if (notional > 0.0) (targetProfit / notional) * 100.0 else tierConfig.tpPct
            val tp = if (isLong) currentPrice * (1.0 + quickTpPct / 100.0) else currentPrice * (1.0 - quickTpPct / 100.0)
            val sl = if (isLong) currentPrice * (1.0 - tierConfig.slPct / 100.0) else currentPrice * (1.0 + tierConfig.slPct / 100.0)

            val pos = PaperPosition(
                symbol = sym,
                side = side,
                entryPrice = currentPrice,
                sizeUsd = settings.positionSizeUsd,
                leverage = settings.leverage,
                tpPrice = tp,
                slPrice = sl,
                tpPct = quickTpPct,
                slPct = tierConfig.slPct,
                openTimeStr = timeStr,
                triggerReason = "MACD(5,13,1) $tfLabel Cross (${if (isLong) "Bullish > 0" else "Bearish < 0"})"
            )
            synchronized(paperStats.openPositions) {
                paperStats.openPositions.add(pos)
            }
            StateManager.saveState(this, paperStats, tradeStories)
        }

        // Update PRO Engine status
        proEngineState = proEngineState.copy(
            lastSignalTime = now,
            lastSignalText = "🎯 $side on $tfLabel: $cleanSym @ $pStr"
        )

        // Dedicated Signal Alert & Notification specifying timeframe
        val alert = AlertItem(
            timeStr = timeStr,
            symbol = cleanSym,
            type = "MACD_TRADE",
            conviction = "HIGH",
            message = "🎯 MACD $side ($tfLabel): $cleanSym @ $pStr | DIF: $difStr | 0-Cross"
        )
        addAlert(alert)
        sendAlertNotification(
            "🎯 MACD Signal: $side $cleanSym ($tfLabel)",
            "Fired on $tfLabel chart! DIF crossed ${if (isLong) "> 0 (Bullish)" else "< 0 (Bearish)"} @ $pStr (${settings.leverage}x)"
        )
        scheduleUiUpdate(force = true)
    }

    fun executeRadarTrade(side: String) = executeManualTrade(side)

    fun executeManualTrade(side: String) {
        val targetSym = proEngineState.symbol.ifEmpty { "BTCUSDT" }
        val fullSym = if (targetSym.endsWith("USDT")) targetSym else "${targetSym}USDT"
        val tierConfig = TierCalculator.getConfig(fullSym)
        val curPrice = trackedCoins[fullSym]?.price.takeIf { it != null && it > 0 } ?: proEngineState.currentPrice
        if (curPrice <= 0) return

        val isLong = side == "LONG"
        val targetProfit = if (settings.targetProfitUsd > 0.0) settings.targetProfitUsd else 2.0
        val notional = settings.positionSizeUsd * settings.leverage
        val quickTpPct = if (notional > 0.0) (targetProfit / notional) * 100.0 else tierConfig.tpPct
        val tp = if (isLong) curPrice * (1.0 + quickTpPct / 100.0) else curPrice * (1.0 - quickTpPct / 100.0)
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
            tpPct = quickTpPct,
            slPct = tierConfig.slPct,
            openTimeStr = timeStr,
            triggerReason = "1-Tap PRO Execution ($side)"
        )
        synchronized(paperStats.openPositions) {
            paperStats.openPositions.add(pos)
        }
        StateManager.saveState(this, paperStats, tradeStories)

        val cleanSym = fullSym.replace("1000", "").replace("USDT", "")
        val pStr = if (curPrice < 1.0) String.format(Locale.US, "$%.4f", curPrice) else String.format(Locale.US, "$%.2f", curPrice)
        val alert = AlertItem(
            timeStr = timeStr,
            symbol = cleanSym,
            type = "MANUAL_TRADE",
            conviction = "HIGH",
            message = "⚡ 1-TAP PRO $side $cleanSym @ $pStr | ${settings.leverage}x"
        )
        addAlert(alert)
        sendAlertNotification("⚡ Trade Entered: $side $cleanSym", "Entered $side @ $pStr (${settings.leverage}x)")
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
                }
                scheduleUiUpdate()
            }
        } catch (_: Exception) {}
    }

    private fun checkOpenPositions(sym: String, currentPrice: Double) {
        if (currentPrice <= 0) return
        val toClose = mutableListOf<Triple<PaperPosition, String, Double>>()
        var stateChanged = false

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
                            stateChanged = true
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
                            if (trailSl > pos.slPrice) {
                                pos.slPrice = trailSl
                                stateChanged = true
                            }
                        } else {
                            val trailSl = pos.lowestPrice * (1.0 + settings.trailingDistancePct / 100.0)
                            if (trailSl < pos.slPrice) {
                                pos.slPrice = trailSl
                                stateChanged = true
                            }
                        }
                    }

                    // 4. Trigger Check: Quick Target Profit (e.g. $2.00) or TP price hit, while preserving SL
                    val targetProfit = if (settings.targetProfitUsd > 0.0) settings.targetProfitUsd else 2.0
                    val curPnlUsd = (diffPct / 100.0) * pos.sizeUsd * pos.leverage
                    val isProfitHit = curPnlUsd >= targetProfit || (if (isLong) currentPrice >= pos.tpPrice else currentPrice <= pos.tpPrice)

                    if (isProfitHit) {
                        val tpOutcome = if (Math.abs(targetProfit - 2.0) < 0.05) "WIN (+$2 TP)" else String.format(Locale.US, "WIN (+%.1f$ TP)", targetProfit)
                        toClose.add(Triple(pos, tpOutcome, currentPrice))
                        it.remove()
                    } else if (if (isLong) currentPrice <= pos.slPrice else currentPrice >= pos.slPrice) {
                        val outcome = if (pos.isTrailingActive && ((isLong && currentPrice > pos.entryPrice) || (!isLong && currentPrice < pos.entryPrice))) "WIN (TRAIL)"
                                      else if (pos.isBreakEvenSet && ((isLong && currentPrice >= pos.entryPrice * 0.999) || (!isLong && currentPrice <= pos.entryPrice * 1.001))) "BREAK-EVEN"
                                      else "LOSS (SL)"
                        toClose.add(Triple(pos, outcome, currentPrice))
                        it.remove()
                    }
                }
            }
        }

        if (stateChanged && toClose.isEmpty()) {
            StateManager.saveState(this, paperStats, tradeStories)
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

            val icon = when {
                outcome == "WIN (TRAIL)" -> "🚀"
                outcome.startsWith("WIN") -> "🏆"
                outcome == "BREAK-EVEN" -> "🛡️"
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

        if (toClose.isNotEmpty()) {
            StateManager.saveState(this, paperStats, tradeStories)
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
        StateManager.saveState(this, paperStats, tradeStories)

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
        StateManager.saveState(this, paperStats, tradeStories)
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
        StateManager.saveState(this, paperStats, tradeStories)
        webSocket?.close(1000, "Service stopped")
        serviceScope.cancel()
    }
}
