package com.nazmiefearmutcu.flowmap.mobile.engine

import com.nazmiefearmutcu.flowmap.mobile.data.Candle

object Indicators {
    fun ema(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (values.isEmpty()) return out
        val k = 2.0 / (period + 1)
        var e = values[0]
        for (i in values.indices) {
            e = if (i == 0) values[0] else values[i] * k + e * (1 - k)
            if (i >= period - 1) out[i] = e
        }
        return out
    }

    /** Session VWAP that resets at 00:00 UTC. */
    fun vwap(candles: List<Candle>): DoubleArray {
        val out = DoubleArray(candles.size) { Double.NaN }
        var day = -1L
        var pv = 0.0
        var v = 0.0
        candles.forEachIndexed { i, c ->
            val d = c.t / 86_400_000L
            if (d != day) { day = d; pv = 0.0; v = 0.0 }
            pv += (c.h + c.l + c.c) / 3 * c.volume
            v += c.volume
            out[i] = if (v > 0) pv / v else Double.NaN
        }
        return out
    }

    fun rsi(close: DoubleArray, period: Int = 14): DoubleArray {
        val out = DoubleArray(close.size) { Double.NaN }
        if (close.size <= period) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = close[i] - close[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= period; loss /= period
        out[period] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        for (i in period + 1 until close.size) {
            val d = close[i] - close[i - 1]
            gain = (gain * (period - 1) + maxOf(d, 0.0)) / period
            loss = (loss * (period - 1) + maxOf(-d, 0.0)) / period
            out[i] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        }
        return out
    }
}
