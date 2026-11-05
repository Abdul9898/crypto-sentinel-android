package com.cryptosentinel.app

import android.app.AlertDialog
import android.content.Context
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
        onClosePosition: ((String) -> Unit)? = null,
        candlesProvider: ((List<CandleStick>) -> Unit) -> Unit
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

        tvTitle.text = "📈 $cleanSym / USDT (1m Candles)"
        tvStatus.text = "$side ACTIVE"

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

        // Fetch candles and load into WebView
        candlesProvider { candles ->
            val candlesJson = buildString {
                append("[")
                candles.forEachIndexed { i, c ->
                    append(String.format(Locale.US, "{\"low\":%.6f,\"high\":%.6f,\"open\":%.6f,\"close\":%.6f}", c.low, c.high, c.open, c.close))
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
                    ctx.font = '13px sans-serif';
                    ctx.fillText('Syncing candle data...', 20, h / 2);
                  } else {
                    let minP = Math.min(...candles.map(c => c.low), entryPrice, tpPrice, slPrice);
                    let maxP = Math.max(...candles.map(c => c.high), entryPrice, tpPrice, slPrice);
                    const pad = (maxP - minP) * 0.12 || 0.001;
                    minP -= pad;
                    maxP += pad;

                    const padLeft = 8;
                    const padRight = 55;
                    const padTop = 15;
                    const padBottom = 20;
                    const chartW = w - padLeft - padRight;
                    const chartH = h - padTop - padBottom;

                    const getY = (p) => padTop + chartH * (1 - (p - minP) / (maxP - minP));
                    const step = chartW / candles.length;
                    const candleW = Math.max(step * 0.65, 2);

                    // Grid
                    ctx.strokeStyle = '#181E29';
                    ctx.lineWidth = 1;
                    for (let i = 1; i <= 4; i++) {
                      const y = padTop + (chartH / 5) * i;
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
                  }
                </script>
                </body>
                </html>
            """.trimIndent()

            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    }
}
