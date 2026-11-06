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
        holder.tvPrice.text = if (coin.price >= 1000) String.format(java.util.Locale.US, "$%,.1f", coin.price)
        else if (coin.price >= 1) String.format(java.util.Locale.US, "$%,.2f", coin.price)
        else if (coin.price > 0) String.format(java.util.Locale.US, "$%,.4f", coin.price)
        else "Sync..."

        // CVD formatting
        val cvd = coin.cvd5m
        val sign = if (cvd > 0) "+" else ""
        holder.tvCvd.text = if (Math.abs(cvd) >= 1_000_000) String.format(java.util.Locale.US, "%s$%.1fM", sign, cvd / 1_000_000)
        else String.format(java.util.Locale.US, "%s$%.0fk", sign, cvd / 1_000)

        holder.tvCvd.setTextColor(if (cvd > 0) Color.parseColor("#00E676") else if (cvd < 0) Color.parseColor("#FF3D71") else Color.WHITE)
        holder.tvBuyRatio.text = String.format(java.util.Locale.US, "%.1f%%", coin.buyRatio)
        holder.tvOi.text = String.format(java.util.Locale.US, "%+.2f%%", coin.oiDeltaPct)
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

class GainerAdapter(private val gainers: List<GainerItem>) : RecyclerView.Adapter<GainerAdapter.GainerViewHolder>() {

    class GainerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSymbol: TextView = view.findViewById(R.id.tvGainerSymbol)
        val tvPrice: TextView = view.findViewById(R.id.tvGainerPrice)
        val tvChange: TextView = view.findViewById(R.id.tvGainerChange)
        val tvVol: TextView = view.findViewById(R.id.tvGainerVol)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GainerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gainer, parent, false)
        return GainerViewHolder(view)
    }

    override fun onBindViewHolder(holder: GainerViewHolder, position: Int) {
        val gainer = gainers[position]
        holder.tvSymbol.text = gainer.symbol
        holder.tvPrice.text = if (gainer.price >= 100) String.format("$%,.1f", gainer.price)
        else if (gainer.price >= 1) String.format("$%,.2f", gainer.price)
        else String.format("$%,.4f", gainer.price)

        val vel = gainer.velocity1hPct
        if (vel != 0.0) {
            val sign = if (vel >= 0) "+" else ""
            holder.tvChange.text = String.format(java.util.Locale.US, "%s%.1f%% 1h", sign, vel)
            holder.tvChange.setTextColor(if (vel >= 0) Color.parseColor("#00E676") else Color.parseColor("#FF3D71"))

            val sign12 = if (gainer.change12hPct >= 0) "+" else ""
            holder.tvVol.text = String.format(java.util.Locale.US, "12h %s%.0f%%", sign12, gainer.change12hPct)
        } else {
            val sign = if (gainer.change12hPct >= 0) "+" else ""
            holder.tvChange.text = String.format("%s%.1f%%", sign, gainer.change12hPct)
            holder.tvChange.setTextColor(if (gainer.change12hPct >= 0) Color.parseColor("#00E676") else Color.parseColor("#FF3D71"))

            val volFormatted = if (gainer.turnoverUsd >= 1_000_000) String.format("Vol: $%.1fM", gainer.turnoverUsd / 1_000_000)
            else String.format("Vol: $%.0fK", gainer.turnoverUsd / 1_000)
            holder.tvVol.text = volFormatted
        }
    }

    override fun getItemCount(): Int = gainers.size
}

class TradeStoryAdapter(
    private val stories: List<TradeStory>,
    private val onItemClick: (TradeStory) -> Unit
) : RecyclerView.Adapter<TradeStoryAdapter.StoryViewHolder>() {

    class StoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvOutcome: TextView = view.findViewById(R.id.tvStoryOutcome)
        val tvSymbol: TextView = view.findViewById(R.id.tvStorySymbol)
        val tvPnl: TextView = view.findViewById(R.id.tvStoryPnl)
        val tvDetails: TextView = view.findViewById(R.id.tvStoryDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trade_story, parent, false)
        return StoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: StoryViewHolder, position: Int) {
        val story = stories[position]
        holder.tvOutcome.text = story.outcome
        holder.tvSymbol.text = "${story.side} ${story.symbol}"

        val sign = if (story.pnlUsd >= 0) "+" else "-"
        holder.tvPnl.text = String.format(java.util.Locale.US, "%s$%,.2f", sign, Math.abs(story.pnlUsd))
        val isWin = story.pnlUsd >= 0
        holder.tvPnl.setTextColor(if (isWin) Color.parseColor("#00E676") else Color.parseColor("#FF3D71"))
        holder.tvOutcome.setTextColor(if (isWin) Color.parseColor("#00E676") else Color.parseColor("#FF3D71"))

        val pFormat = if (story.entryPrice < 1.0) "$%,.4f" else "$%,.2f"
        val entryStr = String.format(java.util.Locale.US, pFormat, story.entryPrice)
        val exitStr = String.format(java.util.Locale.US, pFormat, story.exitPrice)
        holder.tvDetails.text = "Entry: $entryStr -> Exit: $exitStr [${story.exitTime}]"

        holder.itemView.setOnClickListener {
            onItemClick(story)
        }
    }

    override fun getItemCount(): Int = stories.size
}

data class ActivePositionItem(
    val position: PaperPosition,
    val currentPrice: Double,
    val pnlEst: Double,
    val pnlPct: Double
)

class ActivePositionAdapter(
    private val items: List<ActivePositionItem>,
    private val onChartClick: (PaperPosition) -> Unit,
    private val onCloseClick: (PaperPosition) -> Unit
) : RecyclerView.Adapter<ActivePositionAdapter.PositionViewHolder>() {

    class PositionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSideBadge: TextView = view.findViewById(R.id.tvPosSideBadge)
        val tvSymbol: TextView = view.findViewById(R.id.tvPosSymbol)
        val tvPnl: TextView = view.findViewById(R.id.tvPosPnl)
        val tvPrices: TextView = view.findViewById(R.id.tvPosPrices)
        val tvStatusNote: TextView = view.findViewById(R.id.tvPosStatusNote)
        val tvTpSl: TextView = view.findViewById(R.id.tvPosTpSl)
        val btnClose: TextView = view.findViewById(R.id.btnPosClose)
        val btnChart: TextView = view.findViewById(R.id.btnPosChart)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PositionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_active_position, parent, false)
        return PositionViewHolder(view)
    }

    override fun onBindViewHolder(holder: PositionViewHolder, position: Int) {
        val item = items[position]
        val pos = item.position
        val isLong = pos.side == "LONG"
        val sideEmoji = if (isLong) "🟢" else "🔴"

        holder.tvSideBadge.text = "$sideEmoji ${pos.side} ${pos.leverage}x"
        holder.tvSideBadge.setTextColor(if (isLong) Color.parseColor("#00E676") else Color.parseColor("#FF3D71"))

        holder.tvSymbol.text = pos.symbol

        val pnlSign = if (item.pnlEst >= 0) "+" else ""
        holder.tvPnl.text = String.format(java.util.Locale.US, "%s$%,.2f (%+.2f%%)", pnlSign, item.pnlEst, item.pnlPct)
        val isProfit = item.pnlEst >= 0
        holder.tvPnl.setTextColor(if (isProfit) Color.parseColor("#00E676") else Color.parseColor("#FF3D71"))

        val pFmt = if (pos.entryPrice < 1.0) "$%,.4f" else "$%,.2f"
        val curPFmt = if (item.currentPrice < 1.0) "$%,.4f" else "$%,.2f"
        val entryStr = String.format(java.util.Locale.US, pFmt, pos.entryPrice)
        val curStr = String.format(java.util.Locale.US, curPFmt, item.currentPrice)
        holder.tvPrices.text = "Entry: $entryStr -> Mark: $curStr"

        holder.tvStatusNote.text = "[${pos.statusNote}]"

        val tpStr = String.format(java.util.Locale.US, pFmt, pos.tpPrice)
        val slStr = String.format(java.util.Locale.US, pFmt, pos.slPrice)
        holder.tvTpSl.text = "🎯 TP: $tpStr | 🛑 SL: $slStr"

        holder.itemView.setOnClickListener {
            onChartClick(pos)
        }
        holder.btnChart.setOnClickListener {
            onChartClick(pos)
        }
        holder.btnClose.setOnClickListener {
            onCloseClick(pos)
        }
    }

    override fun getItemCount(): Int = items.size
}

