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

    fun loadSettings(context: Context): TradingSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return TradingSettings(
            startingBalance = prefs.getFloat(KEY_STARTING_BALANCE, 10000f).toDouble(),
            positionSizeUsd = prefs.getFloat(KEY_POSITION_SIZE, 500f).toDouble(),
            leverage = prefs.getInt(KEY_LEVERAGE, 5),
            targetProfitUsd = prefs.getFloat(KEY_TARGET_PROFIT, 2.0f).toDouble(),
            maxLossUsd = prefs.getFloat(KEY_MAX_LOSS_USD, 3.5f).toDouble(),
            isReverseMacdCutEnabled = prefs.getBoolean(KEY_REVERSE_MACD_CUT, true),
            isBreakEvenEnabled = prefs.getBoolean(KEY_BE_ENABLED, true),
            beTriggerPct = prefs.getFloat(KEY_BE_TRIGGER, 1.5f).toDouble(),
            isTrailingEnabled = prefs.getBoolean(KEY_TRAIL_ENABLED, true),
            trailingTriggerPct = prefs.getFloat(KEY_TRAIL_TRIGGER, 2.0f).toDouble(),
            trailingDistancePct = prefs.getFloat(KEY_TRAIL_DIST, 1.2f).toDouble()
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
            .apply()
    }
}
