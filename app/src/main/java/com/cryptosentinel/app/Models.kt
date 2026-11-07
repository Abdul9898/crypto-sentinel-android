package com.cryptosentinel.app

import java.util.Collections

data class CoinState(
    val symbol: String,
    var price: Double = 0.0,
    var cvd5m: Double = 0.0,
    var buyRatio: Double = 50.0,
    var oiDeltaPct: Double = 0.0
)

data class AlertItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timeStr: String,
    val symbol: String,
    val type: String,
    val conviction: String,
    val message: String
)

data class MacdStatus(
    val timeframe: String, // "5m", "15m", "1h"
    val dif: Double,
    val prevDif: Double,
    val isBullish: Boolean, // dif > 0
    val isCrossUp: Boolean, // prevDif <= 0 && dif > 0
    val isCrossDown: Boolean, // prevDif >= 0 && dif < 0
    val lastUpdateTime: Long = System.currentTimeMillis()
)

data class GainerItem(
    val symbol: String,
    val price: Double,
    val change12hPct: Double,
    val turnoverUsd: Double,
    val velocity1hPct: Double = 0.0,
    val change24hPct: Double = change12hPct,
    val high24h: Double = 0.0,
    val low24h: Double = 0.0
)

data class CoinTierConfig(
    val minWhaleOrder: Double,
    val clusterThreshold: Double,
    val tpPct: Double,
    val slPct: Double
)

object TierCalculator {
    fun getConfig(symbol: String): CoinTierConfig {
        val clean = symbol.replace("1000", "").replace("USDT", "")
        return when (clean) {
            "BTC", "ETH" -> CoinTierConfig(
                minWhaleOrder = 80_000.0,
                clusterThreshold = 200_000.0,
                tpPct = 1.2,
                slPct = 0.6
            )
            "SOL", "DOGE", "XRP", "SUI", "NEAR" -> CoinTierConfig(
                minWhaleOrder = 35_000.0,
                clusterThreshold = 75_000.0,
                tpPct = 2.0,
                slPct = 1.0
            )
            "PEPE" -> CoinTierConfig(
                minWhaleOrder = 20_000.0,
                clusterThreshold = 45_000.0,
                tpPct = 3.0,
                slPct = 1.5
            )
            else -> CoinTierConfig(
                // Tier 3: Small Altcoins & Breakout Gainers (UAI, AIO, JCT, ELSA, etc.)
                minWhaleOrder = 15_000.0,
                clusterThreshold = 35_000.0,
                tpPct = 4.0,
                slPct = 2.0
            )
        }
    }
}

data class PaperPosition(
    val id: String = java.util.UUID.randomUUID().toString(),
    val symbol: String,
    val side: String, // "LONG" or "SHORT"
    val entryPrice: Double,
    val sizeUsd: Double = 500.0,
    val leverage: Int = 5,
    val tpPrice: Double,
    var slPrice: Double,
    val initialSlPrice: Double = slPrice,
    val tpPct: Double = 1.5,
    val slPct: Double = 0.8,
    val openTimeStr: String,
    val triggerReason: String,
    var highestPrice: Double = entryPrice,
    var lowestPrice: Double = entryPrice,
    var isBreakEvenSet: Boolean = false,
    var isTrailingActive: Boolean = false,
    var statusNote: String = "ACTIVE"
)

data class PaperStats(
    var initialCapital: Double = 10000.0,
    var balance: Double = 10000.0,
    var wins: Int = 0,
    var losses: Int = 0,
    val openPositions: MutableList<PaperPosition> = Collections.synchronizedList(mutableListOf())
) {
    val totalTrades: Int get() = wins + losses
    val winRate: Double get() = if (totalTrades > 0) (wins.toDouble() / totalTrades) * 100 else 0.0
    val pnlUsd: Double get() = balance - initialCapital
    val pnlPct: Double get() = if (initialCapital > 0) (pnlUsd / initialCapital) * 100.0 else 0.0
    val openCount: Int get() = openPositions.size

    fun reset(newInitial: Double) {
        initialCapital = newInitial
        balance = newInitial
        wins = 0
        losses = 0
        openPositions.clear()
    }
}

data class CandleStick(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class TradeStory(
    val id: String = java.util.UUID.randomUUID().toString(),
    val symbol: String,
    val side: String, // "LONG" or "SHORT"
    val entryPrice: Double,
    val exitPrice: Double,
    val pnlUsd: Double,
    val pnlPct: Double,
    val outcome: String, // "WIN (TP)", "WIN (TRAIL)", "LOSS (SL)", "MANUAL"
    val entryTime: String,
    val exitTime: String,
    val triggerReason: String,
    val tpPrice: Double,
    val slPrice: Double
)

data class MarketMatrixState(
    val symbol: String = "",
    val regime: String = "ACCELERATING",
    val volatilityScore: String = "OPTIMAL",
    val trendAlignment: String = "ALIGNED",
    val buyPressurePct: Double = 50.0
)

data class TradingSettings(
    var startingBalance: Double = 10000.0,
    var positionSizeUsd: Double = 500.0,
    var leverage: Int = 5,
    var targetProfitUsd: Double = 2.0,
    var maxLossUsd: Double = 3.5,
    var isReverseMacdCutEnabled: Boolean = true,
    var isBreakEvenEnabled: Boolean = true,
    var beTriggerPct: Double = 1.5,
    var isTrailingEnabled: Boolean = true,
    var trailingTriggerPct: Double = 2.0,
    var trailingDistancePct: Double = 1.2
)

data class ProEngineState(
    val symbol: String = "BTCUSDT",
    val currentPrice: Double = 0.0,
    val change12hPct: Double = 0.0,
    val velocity1hPct: Double = 0.0,
    val turnover24h: Double = 0.0,
    val high24h: Double = 0.0,
    val low24h: Double = 0.0,
    val macd5mBias: String = "NEUTRAL",
    val macd15mBias: String = "NEUTRAL",
    val macd1hBias: String = "NEUTRAL",
    val lastSignalTime: Long = 0L,
    val lastSignalText: String = "Monitoring Multi-Timeframe Trend..."
)


