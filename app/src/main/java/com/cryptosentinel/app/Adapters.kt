package com.cryptosentinel.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CoinAdapter(private val coins: List<CoinState>) : RecyclerView.Adapter<CoinAdapter.CoinViewHolder>() {

    class CoinViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSymbol: TextView = view.findViewById(R.id.tvCoinSymbol)
        val tvPrice: TextView = view.findViewById(R.id.tvCoinPrice)
        val tvCvd: TextView = view.findViewById(R.id.tvCoinCvd)
        val tvBuyRatio: TextView = view.findViewById(R.id.tvCoinBuyRatio)
        val tvOi: TextView = view.findViewById(R.id.tvCoinOi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoinViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_coin, parent, false)
        return CoinViewHolder(view)
    }

    override fun onBindViewHolder(holder: CoinViewHolder, position: Int) {
        val coin = coins[position]
        val cleanSym = coin.symbol.replace("1000", "").replace("USDT", "")

        holder.tvSymbol.text = cleanSym
        holder.tvPrice.text = if (coin.price >= 1000) String.format("$%,.1f", coin.price)
        else if (coin.price >= 1) String.format("$%,.2f", coin.price)
        else if (coin.price > 0) String.format("$%,.4f", coin.price)
        else "Sync..."

        // CVD formatting
        val cvd = coin.cvd5m
        val sign = if (cvd > 0) "+" else ""
        holder.tvCvd.text = if (Math.abs(cvd) >= 1_000_000) String.format("%s$%.1fM", sign, cvd / 1_000_000)
        else String.format("%s$%.0fk", sign, cvd / 1_000)

        holder.tvCvd.setTextColor(if (cvd > 0) Color.parseColor("#00E676") else if (cvd < 0) Color.parseColor("#FF3D71") else Color.WHITE)
        holder.tvBuyRatio.text = String.format("%.1f%%", coin.buyRatio)
        holder.tvOi.text = String.format("%+.2f%%", coin.oiDeltaPct)
    }

    override fun getItemCount(): Int = coins.size
}

class AlertAdapter(private val alerts: List<AlertItem>) : RecyclerView.Adapter<AlertAdapter.AlertViewHolder>() {

    class AlertViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvAlertTime)
        val tvConviction: TextView = view.findViewById(R.id.tvAlertConviction)
        val tvSymbol: TextView = view.findViewById(R.id.tvAlertSymbol)
        val tvMessage: TextView = view.findViewById(R.id.tvAlertMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_alert, parent, false)
        return AlertViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        val alert = alerts[position]
        holder.tvTime.text = "[${alert.timeStr}]"
        holder.tvConviction.text = alert.conviction
        holder.tvSymbol.text = alert.symbol
        holder.tvMessage.text = alert.message

        val color = if (alert.conviction == "HIGH") Color.parseColor("#FF3D71") else Color.parseColor("#FFD700")
        holder.tvConviction.setTextColor(color)
    }

    override fun getItemCount(): Int = alerts.size
}
