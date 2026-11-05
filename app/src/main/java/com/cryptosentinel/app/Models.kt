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

data class PaperPosition(
    val id: String = java.util.UUID.randomUUID().toString(),
    val symbol: String,
    val side: String, // "LONG" or "SHORT"
    val entryPrice: Double,
    val sizeUsd: Double = 1000.0,
    val tpPrice: Double,
    val slPrice: Double,
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
