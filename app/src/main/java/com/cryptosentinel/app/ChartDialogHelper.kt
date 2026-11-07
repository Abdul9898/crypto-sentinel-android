package com.cryptosentinel.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import java.util.Locale

object ChartDialogHelper {

    fun showChart(
        context: Context,
        symbol: String,
        side: String,
        entryPrice: Double,
        tpPrice: Double,
        slPrice: Double,
        tpPct: Double,
        slPct: Double,
        positionId: String? = null,
        statusNote: String? = null,
        onClosePosition: ((String) -> Unit)? = null,
        macdStatusProvider: ((symbol: String) -> Map<String, MacdStatus>?)? = null,
        candlesProvider: (interval: String, callback: (List<CandleStick>) -> Unit) -> Unit
    ) {
        val cleanSym = symbol.replace("1000", "").replace("USDT", "")
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_chart, null)

        val tvTitle: TextView = view.findViewById(R.id.tvChartTitle)
        val tvStatus: TextView = view.findViewById(R.id.tvChartStatus)
        val tvEntry: TextView = view.findViewById(R.id.tvChartEntry)
        val tvTp: TextView = view.findViewById(R.id.tvChartTp)
        val tvSl: TextView = view.findViewById(R.id.tvChartSl)
        val webView: WebView = view.findViewById(R.id.webViewChart)
        val btnClose: Button = view.findViewById(R.id.btnClosePosition)
        val btnDismiss: Button = view.findViewById(R.id.btnDismissChart)

        val btnTf5m: TextView = view.findViewById(R.id.btnTf5m)
        val btnTf15m: TextView = view.findViewById(R.id.btnTf15m)
        val btnTf1h: TextView = view.findViewById(R.id.btnTf1h)

        val tvMacdBadge5m: TextView = view.findViewById(R.id.tvMacdBadge5m)
        val tvMacdBadge15m: TextView = view.findViewById(R.id.tvMacdBadge15m)
        val tvMacdBadge1h: TextView = view.findViewById(R.id.tvMacdBadge1h)

        tvTitle.text = "📈 $cleanSym / USDT"
        tvStatus.text = statusNote ?: "$side ACTIVE"

        val pFormat = if (entryPrice < 1.0) "$%,.4f" else "$%,.2f"
        tvEntry.text = "Entry: " + String.format(Locale.US, pFormat, entryPrice)
        tvTp.text = "TP: " + String.format(Locale.US, pFormat, tpPrice) + " (+$tpPct%)"
        tvSl.text = "SL: " + String.format(Locale.US, pFormat, slPrice) + " (-$slPct%)"

        webView.settings.javaScriptEnabled = true
        webView.setBackgroundColor(0xFF0B0E14.toInt())

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        if (positionId != null && onClosePosition != null) {
            btnClose.visibility = View.VISIBLE
            btnClose.setOnClickListener {
                onClosePosition(positionId)
                dialog.dismiss()
            }
        } else {
            btnClose.visibility = View.GONE
        }

        btnDismiss.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        var activeInterval = "5"

        fun updateMacdMonitorBadges() {
            val fullSym = if (symbol.endsWith("USDT")) symbol else "${symbol}USDT"
            val map = macdStatusProvider?.invoke(fullSym)

            fun renderBadge(label: String, s: MacdStatus?): Pair<String, Int> {
                if (s == null) return Pair("$label: Sync...", Color.parseColor("#657786"))
                val icon = if (s.isBullish) "🟢" else "🔴"
                val dir = if (s.isBullish) "BULL" else "BEAR"
                val color = if (s.isBullish) Color.parseColor("#00E676") else Color.parseColor("#FF3D71")
                return Pair("$label: $icon $dir", color)
            }

            val b5 = renderBadge("5m", map?.get("5"))
            tvMacdBadge5m.text = b5.first
            tvMacdBadge5m.setTextColor(b5.second)

            val b15 = renderBadge("15m", map?.get("15"))
            tvMacdBadge15m.text = b15.first
            tvMacdBadge15m.setTextColor(b15.second)

            val b1h = renderBadge("1h", map?.get("60"))
            tvMacdBadge1h.text = b1h.first
            tvMacdBadge1h.setTextColor(b1h.second)
        }

        fun updateTfButtons(selected: String) {
            activeInterval = selected
            val activeBg = 0xFF00E5FF.toInt() // cyan
            val activeText = 0xFF0B0E14.toInt() // dark
            val inactiveBg = 0xFF232B3A.toInt() // border
            val inactiveText = Color.WHITE

            btnTf5m.setBackgroundColor(if (selected == "5") activeBg else inactiveBg)
            btnTf5m.setTextColor(if (selected == "5") activeText else inactiveText)

            btnTf15m.setBackgroundColor(if (selected == "15") activeBg else inactiveBg)
            btnTf15m.setTextColor(if (selected == "15") activeText else inactiveText)

            btnTf1h.setBackgroundColor(if (selected == "60") activeBg else inactiveBg)
            btnTf1h.setTextColor(if (selected == "60") activeText else inactiveText)

            updateMacdMonitorBadges()
        }

        fun loadChart(interval: String) {
            updateTfButtons(interval)
            candlesProvider(interval) { candles ->
                val candlesJson = buildString {
                    append("[")
                    candles.forEachIndexed { i, c ->
                        append(String.format(Locale.US, "{\"low\":%.6f,\"high\":%.6f,\"open\":%.6f,\"close\":%.6f,\"volume\":%.2f}", c.low, c.high, c.open, c.close, c.volume))
                        if (i < candles.size - 1) append(",")
                    }
                    append("]")
                }

                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                    <style>
                      body { margin: 0; padding: 0; background-color: #0B0E14; overflow: hidden; font-family: monospace; }
                      canvas { display: block; width: 100vw; height: 100vh; }
                    </style>
                    </head>
                    <body>
                    <canvas id="cv"></canvas>
                    <script>
                      const candles = $candlesJson;
                      const entryPrice = $entryPrice;
                      const tpPrice = $tpPrice;
                      const slPrice = $slPrice;
                      const side = "$side";

                      const canvas = document.getElementById('cv');
                      const dpr = window.devicePixelRatio || 1;
                      const w = window.innerWidth;
                      const h = window.innerHeight;
                      canvas.width = w * dpr;
                      canvas.height = h * dpr;
                      const ctx = canvas.getContext('2d');
                      ctx.scale(dpr, dpr);

                      if (candles.length === 0) {
                        ctx.fillStyle = '#888';
                        ctx.font = '12px sans-serif';
                        ctx.fillText('Syncing candle data...', 20, h / 2);
                      } else {
                        // 1. Candlestick upper viewport (56% of total height)
                        const candleViewportH = h * 0.56;
                        const macdViewportTop = candleViewportH + 16;
                        const macdViewportH = h - macdViewportTop - 8;

                        let minP = Math.min(...candles.map(c => c.low), entryPrice, tpPrice, slPrice);
                        let maxP = Math.max(...candles.map(c => c.high), entryPrice, tpPrice, slPrice);
                        const pad = (maxP - minP) * 0.12 || 0.001;
                        minP -= pad;
                        maxP += pad;

                        const padLeft = 8;
                        const padRight = 55;
                        const padTop = 10;
                        const chartW = w - padLeft - padRight;
                        const chartH = candleViewportH - padTop;

                        const getY = (p) => padTop + chartH * (1 - (p - minP) / (maxP - minP));
                        const step = chartW / candles.length;
                        const candleW = Math.max(step * 0.65, 2);

                        // Candle Grid
                        ctx.strokeStyle = '#181E29';
                        ctx.lineWidth = 1;
                        for (let i = 1; i <= 3; i++) {
                          const y = padTop + (chartH / 4) * i;
                          ctx.beginPath();
                          ctx.moveTo(padLeft, y);
                          ctx.lineTo(w - padRight, y);
                          ctx.stroke();
                        }

                        // Draw Candles
                        candles.forEach((c, idx) => {
                          const x = padLeft + idx * step + step / 2;
                          const isUp = c.close >= c.open;
                          ctx.strokeStyle = isUp ? '#00E676' : '#FF3D71';
                          ctx.fillStyle = isUp ? '#00E676' : '#FF3D71';

                          // Wick
                          ctx.beginPath();
                          ctx.moveTo(x, getY(c.high));
                          ctx.lineTo(x, getY(c.low));
                          ctx.stroke();

                          // Body
                          const yOpen = getY(c.open);
                          const yClose = getY(c.close);
                          const bY = Math.min(yOpen, yClose);
                          const bH = Math.max(Math.abs(yClose - yOpen), 1);
                          ctx.fillRect(x - candleW / 2, bY, candleW, bH);
                        });

                        // Level Lines
                        function drawLevel(price, color, label) {
                          if (price <= 0) return;
                          const y = getY(price);
                          ctx.save();
                          ctx.strokeStyle = color;
                          ctx.setLineDash([4, 4]);
                          ctx.lineWidth = 1.5;
                          ctx.beginPath();
                          ctx.moveTo(padLeft, y);
                          ctx.lineTo(w - padRight, y);
                          ctx.stroke();

                          ctx.fillStyle = color;
                          ctx.font = '10px monospace';
                          ctx.fillText(label, w - padRight + 2, y + 3);
                          ctx.restore();
                        }

                        drawLevel(entryPrice, '#00E5FF', 'ENTRY');
                        drawLevel(tpPrice, '#00E676', 'TP');
                        drawLevel(slPrice, '#FF3D71', 'SL');

                        // 2. MACD(5, 13, 1) Sub-Chart Divider
                        ctx.save();
                        ctx.strokeStyle = '#232B3A';
                        ctx.lineWidth = 1;
                        ctx.beginPath();
                        ctx.moveTo(padLeft, candleViewportH + 6);
                        ctx.lineTo(w - padRight, candleViewportH + 6);
                        ctx.stroke();

                        // 3. Compute MACD(5, 13, 1) (Fast 5, Slow 13, DEA 1)
                        const mult5 = 2 / (5 + 1);
                        const mult13 = 2 / (13 + 1);
                        let ema5 = candles[0].close;
                        let ema13 = candles[0].close;
                        const macdPoints = [];

                        candles.forEach((c, idx) => {
                          ema5 = (c.close - ema5) * mult5 + ema5;
                          ema13 = (c.close - ema13) * mult13 + ema13;
                          const dif = ema5 - ema13;
                          const x = padLeft + idx * step + step / 2;
                          macdPoints.push({ x: x, dif: dif });
                        });

                        if (macdPoints.length > 1) {
                          const maxAbsDif = Math.max(...macdPoints.map(p => Math.abs(p.dif)), 0.00001);
                          const zeroY = macdViewportTop + macdViewportH / 2;
                          const getMacdY = (val) => zeroY - (val / maxAbsDif) * (macdViewportH * 0.44);

                          // 3a. Dashed Zero Baseline (Confirmation Center)
                          ctx.save();
                          ctx.strokeStyle = '#657786';
                          ctx.setLineDash([3, 3]);
                          ctx.lineWidth = 1;
                          ctx.beginPath();
                          ctx.moveTo(padLeft, zeroY);
                          ctx.lineTo(w - padRight, zeroY);
                          ctx.stroke();

                          ctx.fillStyle = '#657786';
                          ctx.font = '8px monospace';
                          ctx.fillText('0.00', w - padRight + 2, zeroY + 3);
                          ctx.restore();

                          // 3b. MACD Histogram Bars from Zero Line
                          macdPoints.forEach(pt => {
                            const ptY = getMacdY(pt.dif);
                            const isBull = pt.dif >= 0;
                            ctx.fillStyle = isBull ? '#00E67644' : '#FF3D7144';
                            const barTop = Math.min(zeroY, ptY);
                            const barH = Math.max(Math.abs(zeroY - ptY), 1);
                            ctx.fillRect(pt.x - candleW / 2, barTop, candleW, barH);
                          });

                          // 3c. Cyan DIF Line (Fast EMA 5 - Slow EMA 13)
                          ctx.save();
                          ctx.strokeStyle = '#00E5FF'; // Cyan DIF
                          ctx.lineWidth = 2.0;
                          ctx.beginPath();
                          macdPoints.forEach((pt, i) => {
                            const ptY = getMacdY(pt.dif);
                            if (i === 0) ctx.moveTo(pt.x, ptY);
                            else ctx.lineTo(pt.x, ptY);
                          });
                          ctx.stroke();
                          ctx.restore();

                          // 3d. Sub-chart Header & Cross Badge
                          const lastPt = macdPoints[macdPoints.length - 1];
                          const prevPt = macdPoints.length > 1 ? macdPoints[macdPoints.length - 2] : lastPt;
                          const isCrossUp = prevPt.dif <= 0 && lastPt.dif > 0;
                          const isCrossDown = prevPt.dif >= 0 && lastPt.dif < 0;
                          const isBull = lastPt.dif >= 0;

                          ctx.save();
                          ctx.fillStyle = '#FFD700';
                          ctx.font = 'bold 9px monospace';
                          ctx.fillText('MOMENTUM OSCILLATOR', padLeft, candleViewportH + 4);

                          ctx.fillStyle = '#00E5FF';
                          ctx.font = '9px monospace';
                          ctx.fillText('ALPHA: ' + (lastPt.dif >= 0 ? '+' : '') + lastPt.dif.toFixed(6), padLeft + 120, candleViewportH + 4);

                          // Status Badge
                          const badgeText = isCrossUp ? '🔥 MOMENTUM BREAKOUT LONG' : isCrossDown ? '🔥 MOMENTUM BREAKDOWN SHORT' : (isBull ? '▲ MOMENTUM BULLISH' : '▼ MOMENTUM BEARISH');
                          const badgeColor = isBull ? '#00E676' : '#FF3D71';
                          ctx.fillStyle = badgeColor;
                          ctx.font = 'bold 9px monospace';
                          ctx.fillText(badgeText, padLeft + 235, candleViewportH + 4);
                          ctx.restore();
                        }
                      }
                    </script>
                    </body>
                    </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }

        btnTf5m.setOnClickListener { loadChart("5") }
        btnTf15m.setOnClickListener { loadChart("15") }
        btnTf1h.setOnClickListener { loadChart("60") }

        tvMacdBadge5m.setOnClickListener { loadChart("5") }
        tvMacdBadge15m.setOnClickListener { loadChart("15") }
        tvMacdBadge1h.setOnClickListener { loadChart("60") }

        loadChart("5")
    }
}
