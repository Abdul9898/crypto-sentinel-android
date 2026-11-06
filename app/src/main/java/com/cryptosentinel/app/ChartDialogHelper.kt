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

        val btnTf1m: TextView = view.findViewById(R.id.btnTf1m)
        val btnTf5m: TextView = view.findViewById(R.id.btnTf5m)
        val btnTf15m: TextView = view.findViewById(R.id.btnTf15m)

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

        var activeInterval = "1"

        fun updateTfButtons(selected: String) {
            activeInterval = selected
            val activeBg = 0xFF00E5FF.toInt() // cyan
            val activeText = 0xFF0B0E14.toInt() // dark
            val inactiveBg = 0xFF232B3A.toInt() // border
            val inactiveText = Color.WHITE

            btnTf1m.setBackgroundColor(if (selected == "1") activeBg else inactiveBg)
            btnTf1m.setTextColor(if (selected == "1") activeText else inactiveText)

            btnTf5m.setBackgroundColor(if (selected == "5") activeBg else inactiveBg)
            btnTf5m.setTextColor(if (selected == "5") activeText else inactiveText)

            btnTf15m.setBackgroundColor(if (selected == "15") activeBg else inactiveBg)
            btnTf15m.setTextColor(if (selected == "15") activeText else inactiveText)
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
                        // 1. Candlestick upper viewport (70% of height)
                        const candleViewportH = h * 0.70;
                        const cvdViewportTop = candleViewportH + 15;
                        const cvdViewportH = h - cvdViewportTop - 8;

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

                        // 2. CVD / Volume Sub-Chart Divider
                        ctx.save();
                        ctx.strokeStyle = '#232B3A';
                        ctx.lineWidth = 1;
                        ctx.beginPath();
                        ctx.moveTo(padLeft, candleViewportH + 6);
                        ctx.lineTo(w - padRight, candleViewportH + 6);
                        ctx.stroke();

                        ctx.fillStyle = '#657786';
                        ctx.font = '9px sans-serif';
                        ctx.fillText('VOLUME & CVD DELTA', padLeft, candleViewportH + 4);
                        ctx.restore();

                        // 3. CVD Volume Bars & Cumulative Line
                        const maxVol = Math.max(...candles.map(c => c.volume), 1);
                        let runningCvd = 0;
                        const cvdPoints = [];

                        candles.forEach((c, idx) => {
                          const x = padLeft + idx * step + step / 2;
                          const isUp = c.close >= c.open;
                          const delta = isUp ? c.volume : -c.volume;
                          runningCvd += delta;

                          const barH = Math.max((c.volume / maxVol) * cvdViewportH * 0.85, 2);
                          const barY = cvdViewportTop + cvdViewportH - barH;

                          ctx.fillStyle = isUp ? '#00E67688' : '#FF3D7188';
                          ctx.fillRect(x - candleW / 2, barY, candleW, barH);
                          cvdPoints.push({ x: x, cvd: runningCvd });
                        });

                        // Cumulative Delta Trend Line with Upper & Lower Lines
                        if (cvdPoints.length > 1) {
                          const minCvd = Math.min(...cvdPoints.map(p => p.cvd));
                          const maxCvd = Math.max(...cvdPoints.map(p => p.cvd));
                          const cvdRange = (maxCvd - minCvd) || 1;

                          const getCvdY = (val) => cvdViewportTop + cvdViewportH - 4 - ((val - minCvd) / cvdRange) * (cvdViewportH * 0.85);

                          // 1. CVD Upper Line (High)
                          const yHigh = getCvdY(maxCvd);
                          ctx.save();
                          ctx.strokeStyle = '#00E676';
                          ctx.setLineDash([3, 3]);
                          ctx.lineWidth = 1;
                          ctx.beginPath();
                          ctx.moveTo(padLeft, yHigh);
                          ctx.lineTo(w - padRight, yHigh);
                          ctx.stroke();

                          ctx.fillStyle = '#00E676';
                          ctx.font = '8px monospace';
                          ctx.fillText('CVD HIGH', w - padRight + 2, yHigh + 3);
                          ctx.restore();

                          // 2. CVD Lower Line (Low)
                          const yLow = getCvdY(minCvd);
                          ctx.save();
                          ctx.strokeStyle = '#FF3D71';
                          ctx.setLineDash([3, 3]);
                          ctx.lineWidth = 1;
                          ctx.beginPath();
                          ctx.moveTo(padLeft, yLow);
                          ctx.lineTo(w - padRight, yLow);
                          ctx.stroke();

                          ctx.fillStyle = '#FF3D71';
                          ctx.font = '8px monospace';
                          ctx.fillText('CVD LOW', w - padRight + 2, yLow + 3);
                          ctx.restore();

                          // 3. CVD Gold Curve
                          ctx.save();
                          ctx.strokeStyle = '#FFD700'; // Gold CVD line
                          ctx.lineWidth = 2.0;
                          ctx.beginPath();
                          cvdPoints.forEach((pt, i) => {
                            const ptY = getCvdY(pt.cvd);
                            if (i === 0) ctx.moveTo(pt.x, ptY);
                            else ctx.lineTo(pt.x, ptY);
                          });
                          ctx.stroke();

                          // 4. Direction Slope Indicator
                          const lastPt = cvdPoints[cvdPoints.length - 1];
                          const prevPt = cvdPoints[Math.max(0, cvdPoints.length - 4)];
                          const isRising = lastPt.cvd >= prevPt.cvd;
                          const slopeText = isRising ? '▲ CVD RISING' : '▼ CVD DROPPING';
                          const slopeColor = isRising ? '#00E676' : '#FF3D71';

                          ctx.fillStyle = slopeColor;
                          ctx.font = 'bold 9px monospace';
                          ctx.fillText(slopeText, padLeft + 120, candleViewportH + 4);

                          ctx.fillStyle = '#FFD700';
                          ctx.font = '9px monospace';
                          ctx.fillText('CVD', w - padRight + 2, (yHigh + yLow) / 2);
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

        btnTf1m.setOnClickListener { loadChart("1") }
        btnTf5m.setOnClickListener { loadChart("5") }
        btnTf15m.setOnClickListener { loadChart("15") }

        loadChart("1")
    }
}
