package com.cryptosentinel.app

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

data class PaperStats(
    var balance: Double = 10000.0,
    var wins: Int = 0,
    var losses: Int = 0,
    var openCount: Int = 0
) {
    val totalTrades: Int get() = wins + losses
    val winRate: Double get() = if (totalTrades > 0) (wins.toDouble() / totalTrades) * 100 else 0.0
    val pnlUsd: Double get() = balance - 10000.0
    val pnlPct: Double get() = (pnlUsd / 10000.0) * 100.0
}
