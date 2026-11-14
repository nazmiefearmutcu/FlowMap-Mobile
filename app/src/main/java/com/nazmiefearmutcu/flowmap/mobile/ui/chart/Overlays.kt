package com.nazmiefearmutcu.flowmap.mobile.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.engine.DepthHeatmap
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import kotlin.math.max
import kotlin.math.sqrt

/** Index of the first candle whose open time is ≥ [t] (binary search). */
fun List<Candle>.lowerBound(t: Long): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].t < t) lo = mid + 1 else hi = mid
    }
    return lo
}

/** Candlesticks; thin wicks only when bodies would be narrower than ~2 px. */
fun DrawScope.drawCandles(vp: ChartViewport, candles: List<Candle>, intervalMs: Long, up: Color = Fm.buy, down: Color = Fm.sell, alpha: Float = 1f) {
    if (candles.isEmpty()) return
    val from = max(0, candles.lowerBound((vp.xMin - intervalMs).toLong()))
    val bodyW = (intervalMs / vp.xSpan * size.width).toFloat()
    val wide = bodyW >= 2.5f
    for (i in from until candles.size) {
        val c = candles[i]
        if (c.t > vp.xMax) break
        val x = vp.xToPx(c.t + intervalMs / 2.0)
        val col = (if (c.c >= c.o) up else down).copy(alpha = alpha)
        val yh = vp.yToPx(c.h)
        val yl = vp.yToPx(c.l)
        drawLine(col, Offset(x, yh), Offset(x, yl), if (wide) 1.2f else max(1f, bodyW * 0.8f))
        if (wide) {
            val yo = vp.yToPx(c.o)
            val yc = vp.yToPx(c.c)
            val w = bodyW * 0.62f
            val top = minOf(yo, yc)
            drawRect(col, Offset(x - w / 2, top), Size(w, max(1.2f, kotlin.math.abs(yc - yo))))
        }
    }
}

/** Polyline through (time, value) pairs, skipping NaN gaps. */
fun DrawScope.drawSeries(vp: ChartViewport, times: LongArray, values: DoubleArray, color: Color, width: Float = 2f, offsetMs: Long = 0L) {
    val path = Path()
    var started = false
    for (i in times.indices) {
        val v = values[i]
        if (v.isNaN()) { started = false; continue }
        val x = vp.xToPx((times[i] + offsetMs).toDouble())
        if (x < -50 || x > size.width + 50) { started = false; continue }
        val y = vp.yToPx(v)
        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width))
}

/** Best bid (green) and best ask (red) as stepped lines over the recorded depth. */
fun DrawScope.drawBbo(vp: ChartViewport, d: DepthHeatmap) {
    val n = d.cols
    if (n == 0) return
    val c0 = max(0, d.colAt(vp.xMin.toLong()))
    val c1 = minOf(n - 1, d.colAt(vp.xMax.toLong()).let { if (it < 0) -1 else it } + 1)
    if (c1 < c0) return
    val bid = Path()
    val ask = Path()
    var started = false
    for (c in c0..c1) {
        val x0 = vp.xToPx(d.colTime(c).toDouble())
        val x1 = vp.xToPx((d.colTime(c) + d.colDurationMs).toDouble())
        val yb = vp.yToPx(d.bestBidAt(c))
        val ya = vp.yToPx(d.bestAskAt(c))
        if (d.bestBidAt(c).isNaN()) { started = false; continue }
        if (!started) { bid.moveTo(x0, yb); ask.moveTo(x0, ya); started = true } else { bid.lineTo(x0, yb); ask.lineTo(x0, ya) }
        bid.lineTo(x1, yb); ask.lineTo(x1, ya)
    }
    drawPath(bid, Fm.buy, style = Stroke(2.2f))
    drawPath(ask, Fm.sell, style = Stroke(2.2f))
}

/** Trade bubbles: aggressive buys (green) and sells (red) per column, area ∝ notional. */
fun DrawScope.drawTradeBubbles(vp: ChartViewport, d: DepthHeatmap, refUsd: Double) {
    val n = d.cols
    if (n == 0 || refUsd <= 0) return
    val c0 = max(0, d.colAt(vp.xMin.toLong()))
    val c1 = minOf(n - 1, d.colAt(vp.xMax.toLong()))
    if (c1 < c0) return
    val colPx = (d.colDurationMs / vp.xSpan * size.width).toFloat()
    val maxR = (18f).coerceAtLeast(colPx * 3)
    for (c in c0..c1) {
        val x = vp.xToPx(d.colTime(c) + d.colDurationMs / 2.0)
        val bu = d.buyUsdAt(c)
        val su = d.sellUsdAt(c)
        if (bu > 0 && !d.buyVwapAt(c).isNaN()) {
            val r = (sqrt(bu / refUsd) * 10f).toFloat().coerceIn(1.5f, maxR)
            drawCircle(Fm.buy.copy(alpha = 0.55f), r, Offset(x, vp.yToPx(d.buyVwapAt(c))))
            if (r > 5f) drawCircle(Fm.buy, r, Offset(x, vp.yToPx(d.buyVwapAt(c))), style = Stroke(1.2f))
        }
        if (su > 0 && !d.sellVwapAt(c).isNaN()) {
            val r = (sqrt(su / refUsd) * 10f).toFloat().coerceIn(1.5f, maxR)
            drawCircle(Fm.sell.copy(alpha = 0.55f), r, Offset(x, vp.yToPx(d.sellVwapAt(c))))
            if (r > 5f) drawCircle(Fm.sell, r, Offset(x, vp.yToPx(d.sellVwapAt(c))), style = Stroke(1.2f))
        }
    }
}

/** Horizontal last-price line with a small marker at the right edge. */
fun DrawScope.drawLastPrice(vp: ChartViewport, price: Double, color: Color) {
    if (price.isNaN()) return
    val y = vp.yToPx(price)
    if (y < 0 || y > size.height) return
    drawLine(color.copy(alpha = 0.8f), Offset(0f, y), Offset(size.width, y), 1.4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
}
