package com.cryptosentinel.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Collections

object StateManager {

    private const val PREFS_NAME = "sentinel_state_storage"
    private const val KEY_BALANCE = "saved_balance"
    private const val KEY_INITIAL_CAPITAL = "saved_initial_capital"
    private const val KEY_WINS = "saved_wins"
    private const val KEY_LOSSES = "saved_losses"
    private const val KEY_OPEN_POSITIONS = "saved_open_positions"
    private const val KEY_TRADE_STORIES = "saved_trade_stories"

    private val gson = Gson()

    fun saveState(context: Context, stats: PaperStats, stories: List<TradeStory>) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val posListCopy = synchronized(stats.openPositions) { stats.openPositions.toList() }
            val storiesCopy = synchronized(stories) { stories.toList() }

            val positionsJson = gson.toJson(posListCopy)
            val storiesJson = gson.toJson(storiesCopy)

            prefs.edit()
                .putFloat(KEY_BALANCE, stats.balance.toFloat())
                .putFloat(KEY_INITIAL_CAPITAL, stats.initialCapital.toFloat())
                .putInt(KEY_WINS, stats.wins)
                .putInt(KEY_LOSSES, stats.losses)
                .putString(KEY_OPEN_POSITIONS, positionsJson)
                .putString(KEY_TRADE_STORIES, storiesJson)
                .apply()
        } catch (_: Exception) {}
    }

    fun loadState(context: Context, defaultInitial: Double): Pair<PaperStats, List<TradeStory>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialCapital = prefs.getFloat(KEY_INITIAL_CAPITAL, defaultInitial.toFloat()).toDouble()
        val balance = prefs.getFloat(KEY_BALANCE, initialCapital.toFloat()).toDouble()
        val wins = prefs.getInt(KEY_WINS, 0)
        val losses = prefs.getInt(KEY_LOSSES, 0)

        val positionsJson = prefs.getString(KEY_OPEN_POSITIONS, null)
        val openPositions = mutableListOf<PaperPosition>()
        if (!positionsJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<PaperPosition>>() {}.type
                val parsed: List<PaperPosition>? = gson.fromJson(positionsJson, type)
                if (parsed != null) openPositions.addAll(parsed)
            } catch (_: Exception) {}
        }

        val storiesJson = prefs.getString(KEY_TRADE_STORIES, null)
        val tradeStories = mutableListOf<TradeStory>()
        if (!storiesJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<TradeStory>>() {}.type
                val parsed: List<TradeStory>? = gson.fromJson(storiesJson, type)
                if (parsed != null) tradeStories.addAll(parsed)
            } catch (_: Exception) {}
        }

        val stats = PaperStats(
            initialCapital = initialCapital,
            balance = balance,
            wins = wins,
            losses = losses,
            openPositions = Collections.synchronizedList(openPositions)
        )

        return Pair(stats, tradeStories)
    }

    fun resetState(context: Context, newInitial: Double): Pair<PaperStats, List<TradeStory>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val stats = PaperStats(initialCapital = newInitial, balance = newInitial, wins = 0, losses = 0)
        return Pair(stats, emptyList())
    }
}
