package com.nazmiefearmutcu.flowmap.mobile.engine

import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * History for the live heatmap, reconstructed from 1-minute candles with the band shape of FlowMap
 * desktop's `erode` backfill (server/core/backfill.py). No venue publishes past order books, so
 * this is an estimate and is always labelled as one.
 *
 * Each candle's traded value is spread over its [low, high] band with a triangular weight that
 * peaks at the close and keeps a floor of [FLOOR] at the edges (mass preserved). Unlike desktop's
 * one-band-per-candle columns, the mobile view keeps a volume-at-price memory: every column holds
 * all earlier deposits faded with a [HALF_LIFE_MS] half-life, so prices where heavy volume traded
 * stay visible as horizontal bands, the way resting liquidity looks on a live heatmap. Each column
 * is split at that minute's close into a bid side (at or below) and an ask side (above). One global
 * scale covers every column, so colours never change while you navigate.
 */
class CandleHeatmap private constructor(
    override val cols: Int,
    override val rows: Int,
    override val priceMin: Double,
    override val priceStep: Double,
    private val times: LongArray,
    private val total: FloatArray,
    private val closeRow: IntArray,
    override val scaleMax: Float,
    /** First time not covered (live recording starts here). */
    val until: Long,
) : HeatField {
    var mode = DepthMode.TOTAL
    override val colDurationMs: Long get() = 60_000L
    override fun colTime(c: Int): Long = times[c]
    override fun value(c: Int, r: Int): Float {
        val v = total[c * rows + r]
        return when (mode) {
            DepthMode.TOTAL -> v
            DepthMode.BIDS -> if (r <= closeRow[c]) v else 0f
            DepthMode.ASKS -> if (r > closeRow[c]) v else 0f
        }
    }

    companion object {
        const val FLOOR = 0.15
        const val HALF_LIFE_MS = 2 * 3_600_000L

        /** Triangular weights over rows [lo, hi] peaking at [peak], floored and normalised to 1. */
        fun bandWeights(lo: Int, hi: Int, peak: Int): DoubleArray {
            val n = hi - lo + 1
            if (n <= 0) return DoubleArray(0)
            val p = peak.coerceIn(lo, hi)
            val span = max(p - lo, hi - p).toDouble()
            val w = DoubleArray(n) { i ->
                val k = if (span <= 0) 1.0 else 1.0 - abs((lo + i) - p) / span
                FLOOR + (1 - FLOOR) * k
            }
            val sum = w.sum()
            for (i in w.indices) w[i] /= sum
            return w
        }

        /** Builds from candles whose minute ends at or before [until]; null when fewer than two qualify. */
        fun from(candles: List<Candle>, until: Long, rows: Int = 600): CandleHeatmap? {
            val cs = candles.filter { it.t + 60_000L <= until && it.c > 0 && it.h >= it.l && !it.quoteVolume.isNaN() }
            if (cs.size < 2) return null
            val lo = cs.minOf { it.l }
            val hi = cs.maxOf { it.h }
            val pad = max((hi - lo) * 0.03, hi * 0.0005)
            val pMin = lo - pad
            val step = (hi + pad - pMin) / rows
            val total = FloatArray(cs.size * rows)
            val closes = IntArray(cs.size)
            val memory = DoubleArray(rows)
            var peak = 0f
            fun rowOf(p: Double) = ((p - pMin) / step).roundToInt().coerceIn(0, rows - 1)
            var prevT = cs.first().t
            for ((c, cd) in cs.withIndex()) {
                // Fade earlier deposits by the time elapsed (gaps in the candles fade them further).
                val fade = Math.pow(0.5, (cd.t - prevT).toDouble() / HALF_LIFE_MS)
                prevT = cd.t
                if (fade < 1.0) for (r in 0 until rows) memory[r] *= fade
                val a = rowOf(min(cd.l, cd.h))
                val b = rowOf(max(cd.l, cd.h))
                val closeRow = rowOf(cd.c)
                val w = bandWeights(a, b, closeRow)
                for (i in w.indices) memory[a + i] += cd.quoteVolume * w[i]
                closes[c] = closeRow
                val base = c * rows
                for (r in 0 until rows) {
                    val v = memory[r].toFloat()
                    total[base + r] = v
                    if (v > peak) peak = v
                }
            }
            return CandleHeatmap(cs.size, rows, pMin, step, LongArray(cs.size) { cs[it].t }, total, closes, peak.coerceAtLeast(1e-6f), until)
        }
    }
}
