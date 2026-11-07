package com.cryptosentinel.app

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFS_NAME = "sentinel_trading_settings"

    private const val KEY_STARTING_BALANCE = "starting_balance"
    private const val KEY_POSITION_SIZE = "position_size"
    private const val KEY_LEVERAGE = "leverage"
    private const val KEY_TARGET_PROFIT = "target_profit_usd"
    private const val KEY_MAX_LOSS_USD = "max_loss_usd"
    private const val KEY_REVERSE_MACD_CUT = "reverse_macd_cut"
    private const val KEY_BE_ENABLED = "be_enabled"
    private const val KEY_BE_TRIGGER = "be_trigger"
    private const val KEY_TRAIL_ENABLED = "trail_enabled"
    private const val KEY_TRAIL_TRIGGER = "trail_trigger"
    private const val KEY_TRAIL_DIST = "trail_distance"
    private const val KEY_BYBIT_API_KEY = "bybit_api_key"
    private const val KEY_BYBIT_API_SECRET = "bybit_api_secret"
    private const val KEY_LIVE_TRADING = "is_live_trading_enabled"

    fun loadSettings(context: Context): TradingSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return TradingSettings(
            startingBalance = prefs.getFloat(KEY_STARTING_BALANCE, 10.0f).toDouble(),
            positionSizeUsd = prefs.getFloat(KEY_POSITION_SIZE, 3.5f).toDouble(),
            leverage = prefs.getInt(KEY_LEVERAGE, 3),
            targetProfitUsd = prefs.getFloat(KEY_TARGET_PROFIT, 1.0f).toDouble(),
            maxLossUsd = prefs.getFloat(KEY_MAX_LOSS_USD, 0.5f).toDouble(),
            isReverseMacdCutEnabled = prefs.getBoolean(KEY_REVERSE_MACD_CUT, false),
            isBreakEvenEnabled = prefs.getBoolean(KEY_BE_ENABLED, true),
            beTriggerPct = prefs.getFloat(KEY_BE_TRIGGER, 2.0f).toDouble(),
            isTrailingEnabled = prefs.getBoolean(KEY_TRAIL_ENABLED, true),
            trailingTriggerPct = prefs.getFloat(KEY_TRAIL_TRIGGER, 2.5f).toDouble(),
            trailingDistancePct = prefs.getFloat(KEY_TRAIL_DIST, 1.2f).toDouble(),
            bybitApiKey = prefs.getString(KEY_BYBIT_API_KEY, "") ?: "",
            bybitApiSecret = prefs.getString(KEY_BYBIT_API_SECRET, "") ?: "",
            isLiveTradingEnabled = prefs.getBoolean(KEY_LIVE_TRADING, false)
        )
    }

    fun saveSettings(context: Context, settings: TradingSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_STARTING_BALANCE, settings.startingBalance.toFloat())
            .putFloat(KEY_POSITION_SIZE, settings.positionSizeUsd.toFloat())
            .putInt(KEY_LEVERAGE, settings.leverage)
            .putFloat(KEY_TARGET_PROFIT, settings.targetProfitUsd.toFloat())
            .putFloat(KEY_MAX_LOSS_USD, settings.maxLossUsd.toFloat())
            .putBoolean(KEY_REVERSE_MACD_CUT, settings.isReverseMacdCutEnabled)
            .putBoolean(KEY_BE_ENABLED, settings.isBreakEvenEnabled)
            .putFloat(KEY_BE_TRIGGER, settings.beTriggerPct.toFloat())
            .putBoolean(KEY_TRAIL_ENABLED, settings.isTrailingEnabled)
            .putFloat(KEY_TRAIL_TRIGGER, settings.trailingTriggerPct.toFloat())
            .putFloat(KEY_TRAIL_DIST, settings.trailingDistancePct.toFloat())
            .putString(KEY_BYBIT_API_KEY, settings.bybitApiKey)
            .putString(KEY_BYBIT_API_SECRET, settings.bybitApiSecret)
            .putBoolean(KEY_LIVE_TRADING, settings.isLiveTradingEnabled)
            .apply()
    }

    /**
     * Creates a calibrated $10 account setup
     */
    fun getTenDollarPreset(current: TradingSettings): TradingSettings {
        return current.copy(
            startingBalance = 10.0,
            positionSizeUsd = 3.5,
            leverage = 3,
            targetProfitUsd = 1.0,
            maxLossUsd = 0.5,
            isReverseMacdCutEnabled = false,
            isBreakEvenEnabled = true,
            beTriggerPct = 2.0,
            isTrailingEnabled = true,
            trailingTriggerPct = 2.5,
            trailingDistancePct = 1.2
        )
    }
}
