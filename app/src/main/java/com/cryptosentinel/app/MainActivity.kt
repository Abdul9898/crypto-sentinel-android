package com.cryptosentinel.app

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.cryptosentinel.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
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

    // Smooth UI debouncer to prevent main thread choke
    private var lastUiRender = 0L
    private val renderHandler = Handler(Looper.getMainLooper())
    private val renderRunnable = Runnable {
        updateUiFromService()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as SentinelForegroundService.LocalBinder
            sentinelService = binder.getService()
            isBound = true

            sentinelService?.onStateChangedListener = {
                val now = System.currentTimeMillis()
                if (now - lastUiRender >= 350L) {
                    lastUiRender = now
                    renderHandler.post(renderRunnable)
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
                    statusNote = story.outcome,
                    onClosePosition = null,
                    candlesProvider = { interval, cb -> s.fetchKlines(story.symbol, interval, cb) }
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
                statusNote = "${pos.side} (${pos.statusNote})",
                onClosePosition = { id -> s.closePositionManually(id) },
                candlesProvider = { interval, cb -> s.fetchKlines(pos.symbol, interval, cb) }
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

        // Open Settings Dialog
        binding.btnOpenSettings.setOnClickListener {
            showSettingsDialog()
        }

        // 1-Tap Radar Execution Buttons
        binding.btnRadarLong.setOnClickListener {
            val s = sentinelService ?: return@setOnClickListener
            s.executeRadarTrade("LONG")
            Toast.makeText(this, "⚡ 1-Tap LONG on ${s.depthRadarState.symbol}!", Toast.LENGTH_SHORT).show()
        }

        binding.btnRadarShort.setOnClickListener {
            val s = sentinelService ?: return@setOnClickListener
            s.executeRadarTrade("SHORT")
            Toast.makeText(this, "⚡ 1-Tap SHORT on ${s.depthRadarState.symbol}!", Toast.LENGTH_SHORT).show()
        }

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

    private fun showSettingsDialog() {
        val s = sentinelService ?: return
        val current = s.settings
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)

        val etStartingBalance: EditText = view.findViewById(R.id.etStartingBalance)
        val etPositionSize: EditText = view.findViewById(R.id.etPositionSize)
        val switchBreakEven: SwitchMaterial = view.findViewById(R.id.switchBreakEven)
        val switchTrailing: SwitchMaterial = view.findViewById(R.id.switchTrailing)
        val btnResetPaper: MaterialButton = view.findViewById(R.id.btnResetPaper)
        val btnSaveSettings: MaterialButton = view.findViewById(R.id.btnSaveSettings)
        val btnCancelSettings: MaterialButton = view.findViewById(R.id.btnCancelSettings)

        val btnLev1: TextView = view.findViewById(R.id.btnLev1)
        val btnLev3: TextView = view.findViewById(R.id.btnLev3)
        val btnLev5: TextView = view.findViewById(R.id.btnLev5)
        val btnLev10: TextView = view.findViewById(R.id.btnLev10)
        val btnLev20: TextView = view.findViewById(R.id.btnLev20)

        etStartingBalance.setText(String.format(Locale.US, "%.0f", current.startingBalance))
        etPositionSize.setText(String.format(Locale.US, "%.0f", current.positionSizeUsd))
        switchBreakEven.isChecked = current.isBreakEvenEnabled
        switchTrailing.isChecked = current.isTrailingEnabled

        var selectedLev = current.leverage

        fun updateLevUi(lev: Int) {
            selectedLev = lev
            val activeBg = 0xFF00E5FF.toInt()
            val activeText = 0xFF0B0E14.toInt()
            val inactiveBg = 0xFF232B3A.toInt()
            val inactiveText = Color.WHITE

            btnLev1.setBackgroundColor(if (lev == 1) activeBg else inactiveBg)
            btnLev1.setTextColor(if (lev == 1) activeText else inactiveText)

            btnLev3.setBackgroundColor(if (lev == 3) activeBg else inactiveBg)
            btnLev3.setTextColor(if (lev == 3) activeText else inactiveText)

            btnLev5.setBackgroundColor(if (lev == 5) activeBg else inactiveBg)
            btnLev5.setTextColor(if (lev == 5) activeText else inactiveText)

            btnLev10.setBackgroundColor(if (lev == 10) activeBg else inactiveBg)
            btnLev10.setTextColor(if (lev == 10) activeText else inactiveText)

            btnLev20.setBackgroundColor(if (lev == 20) activeBg else inactiveBg)
            btnLev20.setTextColor(if (lev == 20) activeText else inactiveText)
        }

        updateLevUi(selectedLev)

        btnLev1.setOnClickListener { updateLevUi(1) }
        btnLev3.setOnClickListener { updateLevUi(3) }
        btnLev5.setOnClickListener { updateLevUi(5) }
        btnLev10.setOnClickListener { updateLevUi(10) }
        btnLev20.setOnClickListener { updateLevUi(20) }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnResetPaper.setOnClickListener {
            s.resetPaperStats()
            Toast.makeText(this, "Paper Trading Balance & History Reset", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnSaveSettings.setOnClickListener {
            val startBal = etStartingBalance.text.toString().toDoubleOrNull() ?: current.startingBalance
            val posSize = etPositionSize.text.toString().toDoubleOrNull() ?: current.positionSizeUsd
            val newSettings = current.copy(
                startingBalance = startBal,
                positionSizeUsd = posSize,
                leverage = selectedLev,
                isBreakEvenEnabled = switchBreakEven.isChecked,
                isTrailingEnabled = switchTrailing.isChecked
            )
            s.updateSettings(newSettings)
            Toast.makeText(this, "Trading Settings Saved! (${selectedLev}x Leverage)", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnCancelSettings.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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
            binding.tvPaperWinRate.text = String.format(Locale.US, "Win Rate: %.1f%% (%dW / %dL) | Capital: $%,.0f | Open: %d", stats.winRate, stats.wins, stats.losses, stats.balance, stats.openCount)

            // 6. Update Whale Depth Wall Radar
            val radar = s.depthRadarState
            binding.tvRadarSymbol.text = "🎯 RADAR: ${radar.symbol}"
            binding.tvRadarStatus.text = radar.status

            // Update 1-tap buttons text with radar symbol
            binding.btnRadarLong.text = "🟢 LONG ${radar.symbol}"
            binding.btnRadarShort.text = "🔴 SHORT ${radar.symbol}"

            val bidRatio = Math.max(5.0, Math.min(95.0, radar.bidRatioPct))
            val askRatio = 100.0 - bidRatio
            (binding.barBids.layoutParams as LinearLayout.LayoutParams).weight = bidRatio.toFloat()
            (binding.barAsks.layoutParams as LinearLayout.LayoutParams).weight = askRatio.toFloat()
            binding.barBids.requestLayout()
            binding.barAsks.requestLayout()
            binding.barBids.text = String.format(Locale.US, "%.0f%% Bids", bidRatio)
            binding.barAsks.text = String.format(Locale.US, "%.0f%% Asks", askRatio)

            val bidWallStr = if (radar.topBidWallUsd >= 1000) String.format(Locale.US, "$%.0fK", radar.topBidWallUsd / 1000) else "$0"
            val askWallStr = if (radar.topAskWallUsd >= 1000) String.format(Locale.US, "$%.0fK", radar.topAskWallUsd / 1000) else "$0"
            val pFormat = if (radar.topBidWallPrice < 1.0) "$%.4f" else "$%.2f"
            val pAskFormat = if (radar.topAskWallPrice < 1.0) "$%.4f" else "$%.2f"

            binding.tvBidWall.text = "🟢 Wall: $bidWallStr @ " + String.format(Locale.US, pFormat, radar.topBidWallPrice)
            binding.tvAskWall.text = "🔴 Wall: $askWallStr @ " + String.format(Locale.US, pAskFormat, radar.topAskWallPrice)

            // 7. Active Positions Display with Trailing/Break-Even note
            if (stats.openPositions.isNotEmpty()) {
                val openStr = stats.openPositions.joinToString("\n\n") { pos ->
                    val sym = pos.symbol.replace("1000", "").replace("USDT", "")
                    val curPrice = s.trackedCoins[pos.symbol]?.price ?: pos.entryPrice
                    val rawDiffPct = if (pos.side == "LONG") ((curPrice - pos.entryPrice) / pos.entryPrice) * 100.0
                                     else ((pos.entryPrice - curPrice) / pos.entryPrice) * 100.0
                    val pnlEst = (rawDiffPct / 100.0) * pos.sizeUsd * pos.leverage
                    val sideIcon = if (pos.side == "LONG") "🟢" else "🔴"
                    val pFmt = if (pos.entryPrice < 1.0) "$%,.4f" else "$%,.2f"
                    val curPFmt = if (curPrice < 1.0) "$%,.4f" else "$%,.2f"

                    val entryStr = String.format(Locale.US, pFmt, pos.entryPrice)
                    val curStr = String.format(Locale.US, curPFmt, curPrice)
                    val pnlSignStr = if (pnlEst >= 0) "+" else ""
                    val pnlEstStr = String.format(Locale.US, "%s$%,.2f", pnlSignStr, pnlEst)
                    val diffStr = String.format(Locale.US, "%+.2f%%", rawDiffPct * pos.leverage)

                    val tpStr = String.format(Locale.US, pFmt, pos.tpPrice)
                    val slStr = String.format(Locale.US, pFmt, pos.slPrice)

                    "$sideIcon [${pos.side} $sym ${pos.leverage}x] Entry: $entryStr -> Now: $curStr ($diffStr / $pnlEstStr)\n🎯 TP: $tpStr | 🛑 SL: $slStr [${pos.statusNote}]\n👉 Tap to View Candlestick Chart (1m/5m/15m) & Manual Exit"
                }
                binding.tvActivePositions.text = openStr
            } else {
                binding.tvActivePositions.text = "⚡ Adaptive 3-Tier Auto-Trading Active (${s.settings.leverage}x Leverage | Waiting for Whale Flow)..."
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
