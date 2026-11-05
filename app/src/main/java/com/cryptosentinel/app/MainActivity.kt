package com.cryptosentinel.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.cryptosentinel.app.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sentinelService: SentinelForegroundService? = null
    private var isBound = false

    private val coinsList = mutableListOf<CoinState>()
    private val alertsList = mutableListOf<AlertItem>()
    private val gainersList = mutableListOf<GainerItem>()
    private val storiesList = mutableListOf<TradeStory>()

    private lateinit var coinAdapter: CoinAdapter
    private lateinit var alertAdapter: AlertAdapter
    private lateinit var gainerAdapter: GainerAdapter
    private lateinit var storyAdapter: TradeStoryAdapter

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as SentinelForegroundService.LocalBinder
            sentinelService = binder.getService()
            isBound = true

            sentinelService?.onStateChangedListener = {
                runOnUiThread {
                    updateUiFromService()
                }
            }
            updateUiFromService()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            sentinelService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()

        // Setup Top Gainers Horizontal List
        gainerAdapter = GainerAdapter(gainersList)
        binding.rvGainers.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvGainers.adapter = gainerAdapter

        // Setup Trade Stories List
        storyAdapter = TradeStoryAdapter(storiesList) { story ->
            // On story item clicked -> show candlestick chart!
            sentinelService?.let { s ->
                ChartDialogHelper.showChart(
                    context = this,
                    symbol = story.symbol,
                    side = story.side,
                    entryPrice = story.entryPrice,
                    tpPrice = story.tpPrice,
                    slPrice = story.slPrice,
                    tpPct = Math.abs(story.pnlPct),
                    slPct = Math.abs(story.pnlPct),
                    positionId = null,
                    onClosePosition = null,
                    candlesProvider = { cb -> s.fetchKlines(story.symbol, cb) }
                )
            }
        }
        binding.rvStories.layoutManager = LinearLayoutManager(this)
        binding.rvStories.adapter = storyAdapter

        // Setup Active Position Click -> Opens Live Chart with Close Button
        binding.tvActivePositions.setOnClickListener {
            val s = sentinelService ?: return@setOnClickListener
            val pos = synchronized(s.paperStats.openPositions) { s.paperStats.openPositions.firstOrNull() } ?: return@setOnClickListener
            ChartDialogHelper.showChart(
                context = this,
                symbol = pos.symbol,
                side = pos.side,
                entryPrice = pos.entryPrice,
                tpPrice = pos.tpPrice,
                slPrice = pos.slPrice,
                tpPct = pos.tpPct,
                slPct = pos.slPct,
                positionId = pos.id,
                onClosePosition = { id -> s.closePositionManually(id) },
                candlesProvider = { cb -> s.fetchKlines(pos.symbol, cb) }
            )
        }

        // Setup Coins List
        coinAdapter = CoinAdapter(coinsList)
        binding.rvCoins.layoutManager = LinearLayoutManager(this)
        binding.rvCoins.adapter = coinAdapter

        // Setup Alerts List
        alertAdapter = AlertAdapter(alertsList)
        binding.rvAlerts.layoutManager = LinearLayoutManager(this)
        binding.rvAlerts.adapter = alertAdapter

        // Service Toggle Switch
        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startSentinelService()
            } else {
                stopSentinelService()
            }
        }

        startSentinelService()
    }

    private fun startSentinelService() {
        val intent = Intent(this, SentinelForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopSentinelService() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        val intent = Intent(this, SentinelForegroundService::class.java)
        stopService(intent)
    }

    private fun updateUiFromService() {
        try {
            val s = sentinelService ?: return

            // 1. Update top gainers
            gainersList.clear()
            gainersList.addAll(s.topGainers)
            gainerAdapter.notifyDataSetChanged()

            // 2. Update trade stories
            storiesList.clear()
            storiesList.addAll(s.tradeStories)
            storyAdapter.notifyDataSetChanged()
            val hasStories = storiesList.isNotEmpty()
            binding.tvStoriesHeader.visibility = if (hasStories) View.VISIBLE else View.GONE
            binding.rvStories.visibility = if (hasStories) View.VISIBLE else View.GONE

            // 3. Update coins
            coinsList.clear()
            coinsList.addAll(s.trackedCoins.values.sortedByDescending { Math.abs(it.cvd5m) })
            coinAdapter.notifyDataSetChanged()

            // 4. Update alerts
            alertsList.clear()
            alertsList.addAll(s.alertsList)
            alertAdapter.notifyDataSetChanged()

            // 5. Update Paper Trading Scorecard (Safe Formatting)
            val stats = s.paperStats
            val pnlSign = if (stats.pnlUsd >= 0) "+" else ""
            binding.tvPaperPnl.text = String.format(Locale.US, "💼 PAPER PnL: %s$%,.2f (%+.1f%%)", pnlSign, stats.pnlUsd, stats.pnlPct)
            binding.tvPaperWinRate.text = String.format(Locale.US, "Win Rate: %.1f%% (%dW / %dL) | Open: %d", stats.winRate, stats.wins, stats.losses, stats.openCount)

            // 6. Active Positions Display with Tap to View Chart
            if (stats.openPositions.isNotEmpty()) {
                val openStr = stats.openPositions.joinToString("\n\n") { pos ->
                    val sym = pos.symbol.replace("1000", "").replace("USDT", "")
                    val curPrice = s.trackedCoins[pos.symbol]?.price ?: pos.entryPrice
                    val diffPct = if (pos.side == "LONG") ((curPrice - pos.entryPrice) / pos.entryPrice) * 100.0
                                  else ((pos.entryPrice - curPrice) / pos.entryPrice) * 100.0
                    val pnlEst = (diffPct / 100.0) * pos.sizeUsd
                    val sideIcon = if (pos.side == "LONG") "🟢" else "🔴"
                    val pFormat = if (pos.entryPrice < 1.0) "$%,.4f" else "$%,.2f"
                    val curPFormat = if (curPrice < 1.0) "$%,.4f" else "$%,.2f"

                    val entryStr = String.format(Locale.US, pFormat, pos.entryPrice)
                    val curStr = String.format(Locale.US, curPFormat, curPrice)
                    val pnlSignStr = if (pnlEst >= 0) "+" else ""
                    val pnlEstStr = String.format(Locale.US, "%s$%,.2f", pnlSignStr, pnlEst)
                    val diffStr = String.format(Locale.US, "%+.2f%%", diffPct)

                    val tpStr = String.format(Locale.US, pFormat, pos.tpPrice)
                    val slStr = String.format(Locale.US, pFormat, pos.slPrice)

                    "$sideIcon [${pos.side} $sym] Entry: $entryStr -> Now: $curStr ($diffStr / $pnlEstStr)\n🎯 TP: $tpStr (+${pos.tpPct}%) | 🛑 SL: $slStr (-${pos.slPct}%)\n👉 Tap to View Candlestick Chart & Manual Exit"
                }
                binding.tvActivePositions.text = openStr
            } else {
                binding.tvActivePositions.text = "⚡ Adaptive 3-Tier Auto-Trading Active (Waiting for Whale Flow)..."
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
