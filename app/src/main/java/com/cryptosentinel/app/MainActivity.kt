package com.cryptosentinel.app

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private val activePositionsList = mutableListOf<ActivePositionItem>()

    private lateinit var coinAdapter: CoinAdapter
    private lateinit var alertAdapter: AlertAdapter
    private lateinit var gainerAdapter: GainerAdapter
    private lateinit var storyAdapter: TradeStoryAdapter
    private lateinit var activePositionAdapter: ActivePositionAdapter

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
        requestBatteryOptimizationExemption()

        // Setup Top Gainers Horizontal List (Tapping opens 3-Timeframe MACD Chart)
        gainerAdapter = GainerAdapter(gainersList) { gainer ->
            val s = sentinelService ?: return@GainerAdapter
            s.selectFocalSymbol(gainer.symbol)
            val fullSym = if (gainer.symbol.endsWith("USDT")) gainer.symbol else "${gainer.symbol}USDT"
            val tier = TierCalculator.getConfig(fullSym)
            val curP = gainer.price
            val isLong = gainer.velocity1hPct >= 0
            val notional = s.settings.positionSizeUsd * s.settings.leverage
            val quickTpPct = if (notional > 0.0) (s.settings.targetProfitUsd / notional) * 100.0 else tier.tpPct
            val tpPrice = if (isLong) curP * (1.0 + quickTpPct / 100.0) else curP * (1.0 - quickTpPct / 100.0)
            val slPrice = if (isLong) curP * (1.0 - tier.slPct / 100.0) else curP * (1.0 + tier.slPct / 100.0)
            ChartDialogHelper.showChart(
                context = this,
                symbol = gainer.symbol,
                side = if (isLong) "LONG" else "SHORT",
                entryPrice = curP,
                tpPrice = tpPrice,
                slPrice = slPrice,
                tpPct = quickTpPct,
                slPct = tier.slPct,
                positionId = null,
                statusNote = "12h: ${String.format(Locale.US, "%+.1f%%", gainer.change12hPct)}",
                onClosePosition = null,
                macdStatusProvider = { sym -> s.coinMacdStatus[sym] },
                candlesProvider = { interval, cb -> s.fetchKlines(gainer.symbol, interval, cb) }
            )
        }
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
                    macdStatusProvider = { sym -> s.coinMacdStatus[sym] },
                    candlesProvider = { interval, cb -> s.fetchKlines(story.symbol, interval, cb) }
                )
            }
        }
        binding.rvStories.layoutManager = LinearLayoutManager(this)
        binding.rvStories.adapter = storyAdapter

        // Setup Active Positions List (Each position has its own card with Chart & Close buttons)
        activePositionAdapter = ActivePositionAdapter(
            items = activePositionsList,
            onChartClick = { pos ->
                val s = sentinelService ?: return@ActivePositionAdapter
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
                    macdStatusProvider = { sym -> s.coinMacdStatus[sym] },
                    candlesProvider = { interval, cb -> s.fetchKlines(pos.symbol, interval, cb) }
                )
            },
            onCloseClick = { pos ->
                val s = sentinelService ?: return@ActivePositionAdapter
                MaterialAlertDialogBuilder(this)
                    .setTitle("Close Position")
                    .setMessage("Manually exit ${pos.side} ${pos.symbol} at current market price?")
                    .setPositiveButton("Close Now") { _, _ ->
                        s.closePositionManually(pos.id)
                        Toast.makeText(this, "Closing ${pos.side} ${pos.symbol}...", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        binding.rvActivePositions.layoutManager = LinearLayoutManager(this)
        binding.rvActivePositions.adapter = activePositionAdapter

        // Setup Coins List (Tapping opens 3-Timeframe MACD Chart)
        coinAdapter = CoinAdapter(coinsList) { coin ->
            val s = sentinelService ?: return@CoinAdapter
            s.selectFocalSymbol(coin.symbol)
            val tier = TierCalculator.getConfig(coin.symbol)
            val curP = coin.price
            val isLong = coin.cvd5m >= 0
            val notional = s.settings.positionSizeUsd * s.settings.leverage
            val quickTpPct = if (notional > 0.0) (s.settings.targetProfitUsd / notional) * 100.0 else tier.tpPct
            val tpPrice = if (isLong) curP * (1.0 + quickTpPct / 100.0) else curP * (1.0 - quickTpPct / 100.0)
            val slPrice = if (isLong) curP * (1.0 - tier.slPct / 100.0) else curP * (1.0 + tier.slPct / 100.0)
            ChartDialogHelper.showChart(
                context = this,
                symbol = coin.symbol,
                side = if (isLong) "LONG" else "SHORT",
                entryPrice = curP,
                tpPrice = tpPrice,
                slPrice = slPrice,
                tpPct = quickTpPct,
                slPct = tier.slPct,
                positionId = null,
                statusNote = "CVD: ${String.format(Locale.US, "$%,.0f", coin.cvd5m)}",
                onClosePosition = null,
                macdStatusProvider = { sym -> s.coinMacdStatus[sym] },
                candlesProvider = { interval, cb -> s.fetchKlines(coin.symbol, interval, cb) }
            )
        }
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

        // PRO MACD Engine Buttons: 3-TF Chart and 1-Tap Execution
        binding.btnProChart.setOnClickListener {
            val s = sentinelService ?: return@setOnClickListener
            val targetSym = s.proEngineState.symbol.ifEmpty { "BTCUSDT" }
            val fullSym = if (targetSym.endsWith("USDT")) targetSym else "${targetSym}USDT"
            val tier = TierCalculator.getConfig(fullSym)
            val curP = s.trackedCoins[fullSym]?.price.takeIf { it != null && it > 0 } ?: s.proEngineState.currentPrice
            val isBullish = s.proEngineState.macd15mBias == "BULLISH"
            val notional = s.settings.positionSizeUsd * s.settings.leverage
            val quickTpPct = if (notional > 0.0) (s.settings.targetProfitUsd / notional) * 100.0 else tier.tpPct
            val tpPrice = if (isBullish) curP * (1.0 + quickTpPct / 100.0) else curP * (1.0 - quickTpPct / 100.0)
            val slPrice = if (isBullish) curP * (1.0 - tier.slPct / 100.0) else curP * (1.0 + tier.slPct / 100.0)
            ChartDialogHelper.showChart(
                context = this,
                symbol = targetSym,
                side = if (isBullish) "LONG" else "SHORT",
                entryPrice = curP,
                tpPrice = tpPrice,
                slPrice = slPrice,
                tpPct = quickTpPct,
                slPct = tier.slPct,
                positionId = null,
                statusNote = "PRO MACD: ${s.proEngineState.macd15mBias}",
                onClosePosition = null,
                macdStatusProvider = { sym -> s.coinMacdStatus[sym] },
                candlesProvider = { interval, cb -> s.fetchKlines(fullSym, interval, cb) }
            )
        }

        binding.btnProLong.setOnClickListener {
            val s = sentinelService ?: return@setOnClickListener
            s.executeManualTrade("LONG")
            Toast.makeText(this, "⚡ 1-Tap LONG on ${s.proEngineState.symbol}!", Toast.LENGTH_SHORT).show()
        }

        binding.btnProShort.setOnClickListener {
            val s = sentinelService ?: return@setOnClickListener
            s.executeManualTrade("SHORT")
            Toast.makeText(this, "⚡ 1-Tap SHORT on ${s.proEngineState.symbol}!", Toast.LENGTH_SHORT).show()
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
        val etTargetProfit: EditText = view.findViewById(R.id.etTargetProfit)
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
        etTargetProfit.setText(String.format(Locale.US, "%.1f", current.targetProfitUsd))
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
            val targetProfit = etTargetProfit.text.toString().toDoubleOrNull() ?: current.targetProfitUsd
            val newSettings = current.copy(
                startingBalance = startBal,
                positionSizeUsd = posSize,
                targetProfitUsd = targetProfit,
                leverage = selectedLev,
                isBreakEvenEnabled = switchBreakEven.isChecked,
                isTrailingEnabled = switchTrailing.isChecked
            )
            s.updateSettings(newSettings)
            Toast.makeText(this, "Trading Settings Saved! (Quick TP: $${String.format(Locale.US, "%.2f", targetProfit)} | ${selectedLev}x)", Toast.LENGTH_SHORT).show()
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

            // 6. Update PRO MACD Triple-Timeframe Engine Card
            val pro = s.proEngineState
            val symClean = pro.symbol.ifEmpty { "BTC" }
            binding.tvProSymbol.text = "⚡ PRO MACD(5,13,1): $symClean"

            val pStr = if (pro.currentPrice < 1.0) String.format(Locale.US, "$%.4f", pro.currentPrice) else String.format(Locale.US, "$%,.2f", pro.currentPrice)
            val sign12h = if (pro.change12hPct >= 0) "+" else ""
            binding.tvProPrice.text = String.format(Locale.US, "%s (%s%.2f%% 12h)", pStr, sign12h, pro.change12hPct)

            val vSign = if (pro.velocity1hPct >= 0) "+" else ""
            binding.tvProVelocity.text = String.format(Locale.US, "1h Velocity: %s%.2f%% | %s", vSign, pro.velocity1hPct, pro.lastSignalText)

            updateProBadge(binding.tvProBadge5m, "5M", pro.macd5mBias)
            updateProBadge(binding.tvProBadge15m, "15M", pro.macd15mBias)
            updateProBadge(binding.tvProBadge1h, "1H", pro.macd1hBias)

            binding.btnProLong.text = "🟢 LONG $symClean"
            binding.btnProShort.text = "🔴 SHORT $symClean"

            // 7. Active Positions Display (Individual Cards)
            val currentPositions = synchronized(stats.openPositions) { stats.openPositions.toList() }
            if (currentPositions.isNotEmpty()) {
                binding.tvPositionsEmpty.visibility = View.GONE
                binding.rvActivePositions.visibility = View.VISIBLE

                val updatedList = currentPositions.map { pos ->
                    val curPrice = s.trackedCoins[pos.symbol]?.price ?: pos.entryPrice
                    val rawDiffPct = if (pos.side == "LONG") ((curPrice - pos.entryPrice) / pos.entryPrice) * 100.0
                                     else ((pos.entryPrice - curPrice) / pos.entryPrice) * 100.0
                    val pnlEst = (rawDiffPct / 100.0) * pos.sizeUsd * pos.leverage
                    val pnlPct = rawDiffPct * pos.leverage
                    ActivePositionItem(
                        position = pos,
                        currentPrice = curPrice,
                        pnlEst = pnlEst,
                        pnlPct = pnlPct
                    )
                }

                activePositionsList.clear()
                activePositionsList.addAll(updatedList)
                activePositionAdapter.notifyDataSetChanged()
            } else {
                binding.rvActivePositions.visibility = View.GONE
                binding.tvPositionsEmpty.visibility = View.VISIBLE
                binding.tvPositionsEmpty.text = "⚡ Triple-TF MACD(5,13,1) Auto-Trading Active (${s.settings.leverage}x Leverage | 5m, 15m, 1h Zero-Cross Scan)..."
                if (activePositionsList.isNotEmpty()) {
                    activePositionsList.clear()
                    activePositionAdapter.notifyDataSetChanged()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateProBadge(tv: TextView, tfLabel: String, bias: String) {
        when (bias) {
            "BULLISH" -> {
                tv.setBackgroundResource(R.drawable.bg_pro_badge_green)
                tv.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
                tv.text = "$tfLabel: BULLISH"
            }
            "BEARISH" -> {
                tv.setBackgroundResource(R.drawable.bg_pro_badge_red)
                tv.setTextColor(ContextCompat.getColor(this, R.color.neon_red))
                tv.text = "$tfLabel: BEARISH"
            }
            else -> {
                tv.setBackgroundResource(R.drawable.bg_pro_badge_neutral)
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                tv.text = "$tfLabel: SCANNING..."
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
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
