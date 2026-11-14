package com.nazmiefearmutcu.flowmap.mobile.engine

import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.data.OiPoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class LeverageTier(val leverage: Int, val weight: Double)

/**
 * Leverage-distribution models. All three are estimates: no venue publishes where open positions
 * would be liquidated, so the map is reconstructed from candles, open interest and taker flow.
 */
enum class LiqModel(val label: String, val summary: String, val tiers: List<LeverageTier>, val halfLifeDays: Double, val turnoverShare: Double) {
    MODEL_1(
        "Model 1", "Balanced: 5–100x, positions fade over ~10 days",
        listOf(LeverageTier(5, 0.06), LeverageTier(10, 0.30), LeverageTier(25, 0.29), LeverageTier(50, 0.22), LeverageTier(100, 0.13)),
        10.0, 0.010,
    ),
    MODEL_2(
        "Model 2", "High leverage: 10–100x, positions fade over ~4 days",
        listOf(LeverageTier(10, 0.12), LeverageTier(25, 0.26), LeverageTier(50, 0.34), LeverageTier(100, 0.28)),
        4.0, 0.016,
    ),
    MODEL_3(
        "Model 3", "Conservative: 3–25x, positions fade over ~20 days",
        listOf(LeverageTier(3, 0.12), LeverageTier(5, 0.24), LeverageTier(10, 0.34), LeverageTier(20, 0.20), LeverageTier(25, 0.10)),
        20.0, 0.006,
    ),
}

/** Result of [LiqHeatmapEngine.build]. Values are estimated USD notional per price bin. */
class LiqHeatmap(
    override val cols: Int,
    override val rows: Int,
    override val priceMin: Double,
    override val priceStep: Double,
    private val times: LongArray,
    override val colDurationMs: Long,
    private val values: FloatArray,
    override val scaleMax: Float,
    val candles: List<Candle>,
    val model: LiqModel,
    /** Final (current) long-liquidation notional per tier and bin. */
    val finalLong: Array<FloatArray>,
    /** Final (current) short-liquidation notional per tier and bin. */
    val finalShort: Array<FloatArray>,
    /** True when open interest drove the model; false when only volume was available. */
    val usedOpenInterest: Boolean,
    val oiCoverageFrom: Long,
) : HeatField {
    override fun colTime(c: Int): Long = times[c]
    override fun value(c: Int, r: Int): Float = values[c * rows + r]
    val lastPrice: Double get() = candles.lastOrNull()?.c ?: Double.NaN
}

object LiqHeatmapEngine {
    private const val MAINTENANCE_MARGIN = 0.004

    fun longLiqPrice(entry: Double, leverage: Int) = entry * (1.0 - 1.0 / leverage + MAINTENANCE_MARGIN)
    fun shortLiqPrice(entry: Double, leverage: Int) = entry * (1.0 + 1.0 / leverage - MAINTENANCE_MARGIN)

    /**
     * Builds the liquidation heatmap.
     *
     * For each candle, new positions are estimated from the rise in open interest plus a share of
     * turnover (positions that open and close within the candle). They are split long/short by
     * taker-buy share where the venue publishes it, spread over the candle's price range and placed
     * at each leverage tier's liquidation price. A level is removed once price trades through it, and
     * every level fades with the model's half-life as positions are closed voluntarily.
     */
    fun build(candles: List<Candle>, oi: List<OiPoint>, model: LiqModel, intervalMs: Long, rows: Int = 480): LiqHeatmap? {
        if (candles.size < 2) return null
        val cols = candles.size
        val lo = candles.minOf { it.l }
        val hi = candles.maxOf { it.h }
        val minLev = model.tiers.minOf { it.leverage }
        val pMin = max(lo * 0.5, longLiqPrice(lo, minLev) * 0.98)
        val pMax = min(hi * 1.6, shortLiqPrice(hi, minLev) * 1.02)
        val step = (pMax - pMin) / rows

        // Open interest aligned to candles; volume share calibrated on the overlap where OI exists.
        val oiAligned = alignOi(candles, oi)
        val newFromOi = DoubleArray(cols)
        var sumDelta = 0.0
        var sumQv = 0.0
        for (i in 1 until cols) {
            val a = oiAligned[i - 1]
            val b = oiAligned[i]
            if (!a.isNaN() && !b.isNaN()) {
                val d = max(0.0, b - a)
                newFromOi[i] = d
                sumDelta += d
                sumQv += candles[i].quoteVolume
            } else newFromOi[i] = Double.NaN
        }
        val volumeShare = if (sumQv > 0 && sumDelta > 0) (sumDelta / sumQv).coerceIn(0.002, 0.2) else 0.03
        val decay = 0.5.pow(intervalMs / (model.halfLifeDays * 86_400_000.0)).toFloat()

        val tiers = model.tiers
        val longs = Array(tiers.size) { FloatArray(rows) }
        val shorts = Array(tiers.size) { FloatArray(rows) }
        val values = FloatArray(cols * rows)
        val times = LongArray(cols) { candles[it].t }

        fun bin(p: Double) = ((p - pMin) / step).toInt()

        for (i in 0 until cols) {
            val c = candles[i]
            // 1) Levels that price traded through during this candle are liquidated.
            val lowBin = bin(c.l).coerceIn(0, rows)
            val highBin = bin(c.h).coerceIn(-1, rows - 1)
            for (t in tiers.indices) {
                val L = longs[t]
                for (r in lowBin until rows) L[r] = 0f
                val S = shorts[t]
                for (r in 0..highBin) S[r] = 0f
            }
            // 2) Voluntary closes.
            if (decay < 1f) for (t in tiers.indices) {
                val L = longs[t]
                val S = shorts[t]
                for (r in 0 until rows) { L[r] *= decay; S[r] *= decay }
            }
            // 3) New positions opened in this candle.
            val fromOi = newFromOi[i]
            val notional = (if (fromOi.isNaN()) c.quoteVolume * volumeShare else fromOi) + c.quoteVolume * model.turnoverShare
            if (notional > 0 && i > 0) {
                val longShare = if (!c.takerBuyQuote.isNaN() && c.quoteVolume > 0) (c.takerBuyQuote / c.quoteVolume).coerceIn(0.3, 0.7) else 0.5
                for ((t, tier) in tiers.withIndex()) {
                    val n = notional * tier.weight
                    // Positions opened in this candle whose liquidation price lies inside the candle's own
                    // range were already liquidated by it: only the part beyond the low (longs) or the
                    // high (shorts) survives.
                    clippedDeposit(longs[t], longLiqPrice(c.l, tier.leverage), longLiqPrice(c.h, tier.leverage), n * longShare, pMin, step, hiClip = c.l)
                    clippedDeposit(shorts[t], shortLiqPrice(c.l, tier.leverage), shortLiqPrice(c.h, tier.leverage), n * (1 - longShare), pMin, step, loClip = c.h)
                }
            }
            // 4) Snapshot the column.
            val base = i * rows
            for (t in tiers.indices) {
                val L = longs[t]
                val S = shorts[t]
                for (r in 0 until rows) values[base + r] += L[r] + S[r]
            }
        }
        return LiqHeatmap(
            cols = cols, rows = rows, priceMin = pMin, priceStep = step, times = times, colDurationMs = intervalMs,
            values = values, scaleMax = values.max().coerceAtLeast(1e-6f), candles = candles, model = model,
            finalLong = longs, finalShort = shorts, usedOpenInterest = sumDelta > 0,
            oiCoverageFrom = oi.firstOrNull()?.t ?: Long.MAX_VALUE,
        )
    }

    /** Like [deposit], keeping only the share of [p0, p1] inside [loClip, hiClip]. */
    internal fun clippedDeposit(arr: FloatArray, p0: Double, p1: Double, amount: Double, pMin: Double, step: Double, loClip: Double = Double.NEGATIVE_INFINITY, hiClip: Double = Double.POSITIVE_INFINITY) {
        val a = min(p0, p1)
        val b = max(p0, p1)
        val ca = max(a, loClip)
        val cb = min(b, hiClip)
        if (cb <= ca) return
        val kept = if (b > a) (cb - ca) / (b - a) else 1.0
        deposit(arr, ca, cb, amount * kept, pMin, step)
    }

    /** Spreads [amount] uniformly over the bins covering [p0, p1]. */
    internal fun deposit(arr: FloatArray, p0: Double, p1: Double, amount: Double, pMin: Double, step: Double) {
        val rows = arr.size
        val a = ((min(p0, p1) - pMin) / step).toInt()
        val b = ((max(p0, p1) - pMin) / step).toInt()
        if (b < 0 || a >= rows) return
        val s = a.coerceAtLeast(0)
        val e = b.coerceAtMost(rows - 1)
        val each = (amount / (b - a + 1)).toFloat()
        for (r in s..e) arr[r] += each
    }

    /** OI value at each candle time (last known value, NaN before the first point). */
    internal fun alignOi(candles: List<Candle>, oi: List<OiPoint>): DoubleArray {
        val out = DoubleArray(candles.size) { Double.NaN }
        if (oi.isEmpty()) return out
        var j = -1
        for (i in candles.indices) {
            val t = candles[i].t
            while (j + 1 < oi.size && oi[j + 1].t <= t) j++
            if (j >= 0 && t - oi[j].t <= 2 * 86_400_000L) out[i] = oi[j].usd
        }
        return out
    }

    /** Sums several OI series (e.g. Binance + Bybit for the aggregated "Symbol" view) on the first one's timestamps. */
    fun mergeOi(series: List<List<OiPoint>>): List<OiPoint> {
        val nonEmpty = series.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return emptyList()
        val base = nonEmpty.maxBy { it.size }
        val others = nonEmpty.filter { it !== base }
        val idx = IntArray(others.size) { -1 }
        return base.map { p ->
            var sum = p.usd
            others.forEachIndexed { k, s ->
                while (idx[k] + 1 < s.size && s[idx[k] + 1].t <= p.t) idx[k]++
                if (idx[k] >= 0) sum += s[idx[k]].usd
            }
            OiPoint(p.t, sum)
        }
    }

    internal fun percentile(values: FloatArray, q: Double): Float {
        var nz = 0
        for (v in values) if (v > 0f) nz++
        if (nz == 0) return 1f
        val stride = max(1, nz / 20_000)
        val sample = FloatArray((nz + stride - 1) / stride)
        var k = 0
        var seen = 0
        for (v in values) if (v > 0f) {
            if (seen % stride == 0 && k < sample.size) sample[k++] = v
            seen++
        }
        val s = sample.copyOf(k).also { it.sort() }
        return s[((s.size - 1) * q).toInt()].coerceAtLeast(1e-6f)
    }
}

/** Liquidation-map bars: current liquidation notional per price bucket and leverage tier. */
class LiqMapBars(
    val priceMin: Double,
    val bucket: Double,
    val tiers: List<LeverageTier>,
    /** [tier][bucket] long notional (below price) */
    val longs: Array<DoubleArray>,
    /** [tier][bucket] short notional (above price) */
    val shorts: Array<DoubleArray>,
    val price: Double,
) {
    val buckets: Int get() = longs.firstOrNull()?.size ?: 0
    fun bucketPrice(b: Int) = priceMin + (b + 0.5) * bucket
    fun total(b: Int): Double = longs.sumOf { it[b] } + shorts.sumOf { it[b] }

    /** Cumulative long liquidations from price down to bucket [b] (0 above price). */
    fun cumulativeLong(): DoubleArray {
        val n = buckets
        val out = DoubleArray(n)
        var acc = 0.0
        for (b in n - 1 downTo 0) {
            if (bucketPrice(b) <= price) acc += longs.sumOf { it[b] }
            out[b] = if (bucketPrice(b) <= price) acc else 0.0
        }
        return out
    }

    fun cumulativeShort(): DoubleArray {
        val n = buckets
        val out = DoubleArray(n)
        var acc = 0.0
        for (b in 0 until n) {
            if (bucketPrice(b) >= price) acc += shorts.sumOf { it[b] }
            out[b] = if (bucketPrice(b) >= price) acc else 0.0
        }
        return out
    }

    companion object {
        /** Aggregates the heatmap's final state into [buckets] buckets inside [lo, hi]. */
        fun from(h: LiqHeatmap, lo: Double, hi: Double, buckets: Int = 90): LiqMapBars {
            val bucket = (hi - lo) / buckets
            val tiers = h.model.tiers
            val longs = Array(tiers.size) { DoubleArray(buckets) }
            val shorts = Array(tiers.size) { DoubleArray(buckets) }
            for (t in tiers.indices) for (r in 0 until h.rows) {
                val p = h.rowPrice(r)
                if (p < lo || p >= hi) continue
                val b = ((p - lo) / bucket).toInt().coerceIn(0, buckets - 1)
                longs[t][b] += h.finalLong[t][r].toDouble()
                shorts[t][b] += h.finalShort[t][r].toDouble()
            }
            return LiqMapBars(lo, bucket, tiers, longs, shorts, h.lastPrice)
        }
    }
}
