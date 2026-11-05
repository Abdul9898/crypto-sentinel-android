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

data class GainerItem(
    val symbol: String,
    val price: Double,
    val change24hPct: Double,
    val turnoverUsd: Double
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
    val sizeUsd: Double = 1000.0,
    val tpPrice: Double,
    val slPrice: Double,
    val tpPct: Double = 1.5,
    val slPct: Double = 0.8,
    val openTimeStr: String,
    val triggerReason: String
)

data class PaperStats(
    var balance: Double = 10000.0,
    var wins: Int = 0,
    var losses: Int = 0,
    val openPositions: MutableList<PaperPosition> = Collections.synchronizedList(mutableListOf())
) {
    val totalTrades: Int get() = wins + losses
    val winRate: Double get() = if (totalTrades > 0) (wins.toDouble() / totalTrades) * 100 else 0.0
    val pnlUsd: Double get() = balance - 10000.0
    val pnlPct: Double get() = (pnlUsd / 10000.0) * 100.0
    val openCount: Int get() = openPositions.size
}
