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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.cryptosentinel.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sentinelService: SentinelForegroundService? = null
    private var isBound = false

    private val coinsList = mutableListOf<CoinState>()
    private val alertsList = mutableListOf<AlertItem>()
    private val gainersList = mutableListOf<GainerItem>()

    private lateinit var coinAdapter: CoinAdapter
    private lateinit var alertAdapter: AlertAdapter
    private lateinit var gainerAdapter: GainerAdapter

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
        val s = sentinelService ?: return

        // Update top gainers
        gainersList.clear()
        gainersList.addAll(s.topGainers)
        gainerAdapter.notifyDataSetChanged()

        // Update coins
        coinsList.clear()
        coinsList.addAll(s.trackedCoins.values.sortedByDescending { Math.abs(it.cvd5m) })
        coinAdapter.notifyDataSetChanged()

        // Update alerts
        alertsList.clear()
        alertsList.addAll(s.alertsList)
        alertAdapter.notifyDataSetChanged()

        // Update Paper Trading Banner
        val stats = s.paperStats
        val pnlSign = if (stats.pnlUsd >= 0) "+" else ""
        binding.tvPaperPnl.text = String.format("💼 PAPER PnL: %s$%,.2f (%+.1f%%)", pnlSign, stats.pnlUsd, stats.pnlPct)
        binding.tvPaperWinRate.text = String.format("Win Rate: %.1f%% (%dW / %dL) | Open Positions: %d", stats.winRate, stats.wins, stats.losses, stats.openCount)

        if (stats.openPositions.isNotEmpty()) {
            val openStr = stats.openPositions.joinToString("\n") { pos ->
                val sym = pos.symbol.replace("1000", "").replace("USDT", "")
                val curPrice = s.trackedCoins[pos.symbol]?.price ?: pos.entryPrice
                val diffPct = if (pos.side == "LONG") ((curPrice - pos.entryPrice) / pos.entryPrice) * 100
                              else ((pos.entryPrice - curPrice) / pos.entryPrice) * 100
                val pnlEst = (diffPct / 100.0) * pos.sizeUsd
                val sideIcon = if (pos.side == "LONG") "🟢" else "🔴"
                String.format("%s [%s %s] Entry: $%,.2f -> Now: $%,.2f (%+.2f%% / %+$%,.2f)", sideIcon, pos.side, sym, pos.entryPrice, curPrice, diffPct, pnlEst)
            }
            binding.tvActivePositions.text = openStr
        } else {
            binding.tvActivePositions.text = "Waiting for whale clusters (> $250k) to auto-trade..."
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
