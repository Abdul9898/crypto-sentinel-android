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
    val paperStats = PaperStats()

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
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Silent ongoing foreground service channel
            val serviceChannel = NotificationChannel(
                "sentinel_service_channel",
                "Sentinel Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Crypto Sentinel running 24/7 in background"
                setShowBadge(false)
            }
            manager.createNotificationChannel(serviceChannel)

            // 2. High priority alert channel with sound & vibration
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
                for (s in pairs) {
                    topics.add("publicTrade.$s")
                    topics.add("tickers.$s")
                }
                val subMsg = mapOf("op" to "subscribe", "args" to topics)
                ws.send(gson.toJson(subMsg))
                updateServiceNotification("Monitoring 8 pairs on Bybit Linear")
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
                }
                onStateChangedListener?.invoke()
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

                    // Whale order check
                    if (usdVal >= 100_000) {
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        val sideIcon = if (isBuy) "🟢 BUY" else "🔴 SELL"
                        val msg = "$sideIcon Whale: $${String.format("%,.0f", usdVal)} @ $${String.format("%,.2f", p)}"
                        val alert = AlertItem(
                            timeStr = timeStr,
                            symbol = sym.replace("1000", "").replace("USDT", ""),
                            type = "WHALE_TRADE",
                            conviction = "HIGH",
                            message = msg
                        )
                        addAlert(alert)
                        if (usdVal >= 250_000) {
                            sendAlertNotification("🐋 Whale Order ($sym)", msg)
                        }
                    }
                }
                onStateChangedListener?.invoke()
            }
        } catch (_: Exception) {}
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
