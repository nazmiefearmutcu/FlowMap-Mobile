package com.nazmiefearmutcu.flowmap.mobile.engine

import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import kotlin.math.floor

enum class DepthMode(val label: String) { TOTAL("Total"), BIDS("Bids"), ASKS("Asks") }

/** Trades accumulated between two depth samples. */
class TradeAccumulator {
    var buyUsd = 0.0
    var sellUsd = 0.0
    var buyPq = 0.0
    var sellPq = 0.0
    var buyQty = 0.0
    var sellQty = 0.0

    fun add(price: Double, qty: Double, buy: Boolean) {
        if (buy) { buyUsd += price * qty; buyPq += price * qty; buyQty += qty } else { sellUsd += price * qty; sellPq += price * qty; sellQty += qty }
    }

    fun reset() { buyUsd = 0.0; sellUsd = 0.0; buyPq = 0.0; sellPq = 0.0; buyQty = 0.0; sellQty = 0.0 }
}

/**
 * Ring buffer of order-book columns: FlowMap's liquidity heatmap, recorded on the device.
 * Each column holds resting bid and ask notional (USD) per price bin at one sampling instant,
 * plus the best bid/offer and the trades that printed since the previous column.
 *
 * Recorded values are never rewritten. Each column stores [windowRows] bins around its own anchor
 * (the price window in use when it was recorded); when price drifts, new columns simply use a new
 * anchor. As a field the heatmap spans every anchor in the ring, so nothing recorded is ever
 * dropped or moved. A spare physical slot lets a new column be written while the oldest one is
 * still visible; [count]/[start] are published only after the column is complete.
 */
class DepthHeatmap(val capacity: Int = 3000, val windowRows: Int = 600) : HeatField {
    var binSize = 1.0
        private set
    /** Anchor (absolute bin of row 0) used for new columns. */
    private var anchorBin = 0L
    private val slots = capacity + 1
    private val bid = FloatArray(slots * windowRows)
    private val ask = FloatArray(slots * windowRows)
    private val times = LongArray(slots)
    private val colAnchor = LongArray(slots)
    private val bestBid = DoubleArray(slots) { Double.NaN }
    private val bestAsk = DoubleArray(slots) { Double.NaN }
    private val buyUsd = DoubleArray(slots)
    private val sellUsd = DoubleArray(slots)
    private val buyVwap = DoubleArray(slots) { Double.NaN }
    private val sellVwap = DoubleArray(slots) { Double.NaN }
    private val cvd = DoubleArray(slots)
    @Volatile private var start = 0
    @Volatile private var count = 0
    /** Lowest and highest column anchor currently in the ring (the field's vertical extent). */
    @Volatile private var minAnchor = 0L
    @Volatile private var maxAnchor = 0L
    private var runningCvd = 0.0
    private var scale = 0f
    var mode = DepthMode.TOTAL
    var sampleMs = 1000L

    @Volatile
    var initialized = false
        private set

    override val cols: Int get() = count
    override val rows: Int get() = (maxAnchor - minAnchor).toInt() + windowRows
    override val priceMin: Double get() = minAnchor * binSize
    override val priceStep: Double get() = binSize
    override val colDurationMs: Long get() = sampleMs
    override val scaleMax: Float get() = if (scale > 0f) scale else 1f

    private fun phys(c: Int) = (start + c) % slots

    /** Index into the bid/ask arrays for field row [r] of column [c], or -1 outside that column's window. */
    private fun cell(c: Int, r: Int): Int {
        val p = phys(c)
        val local = (minAnchor + r - colAnchor[p]).toInt()
        return if (local in 0 until windowRows) p * windowRows + local else -1
    }

    override fun colTime(c: Int): Long = times[phys(c)]

    override fun value(c: Int, r: Int): Float {
        val i = cell(c, r)
        if (i < 0) return 0f
        return when (mode) {
            DepthMode.TOTAL -> bid[i] + ask[i]
            DepthMode.BIDS -> bid[i]
            DepthMode.ASKS -> ask[i]
        }
    }

    fun bidAt(c: Int, r: Int): Float = cell(c, r).let { if (it < 0) 0f else bid[it] }
    fun askAt(c: Int, r: Int): Float = cell(c, r).let { if (it < 0) 0f else ask[it] }
    fun bestBidAt(c: Int) = bestBid[phys(c)]
    fun bestAskAt(c: Int) = bestAsk[phys(c)]
    fun buyUsdAt(c: Int) = buyUsd[phys(c)]
    fun sellUsdAt(c: Int) = sellUsd[phys(c)]
    fun buyVwapAt(c: Int) = buyVwap[phys(c)]
    fun sellVwapAt(c: Int) = sellVwap[phys(c)]
    fun cvdAt(c: Int) = cvd[phys(c)]

    /**
     * Chooses a bin of roughly two basis points and centres the window on [mid]: 600 rows span
     * about ±6% around each column's anchor.
     */
    fun init(mid: Double) {
        binSize = Fmt.niceStep(mid * 0.0002)
        anchorBin = floor(mid / binSize).toLong() - windowRows / 2
        minAnchor = anchorBin
        maxAnchor = anchorBin
        initialized = true
    }

    /** Records one column from the current book. */
    fun record(t: Long, bids: Map<Double, Double>, asks: Map<Double, Double>, bb: Double, ba: Double, trades: TradeAccumulator) {
        if (!initialized) {
            if (bb.isNaN() || ba.isNaN()) return
            init((bb + ba) / 2)
        }
        val mid = if (!bb.isNaN() && !ba.isNaN()) (bb + ba) / 2 else Double.NaN
        if (!mid.isNaN()) {
            // Keep the mid inside the middle 60% of the window for new columns; old columns keep theirs.
            val r = floor((mid - anchorBin * binSize) / binSize).toLong()
            if (r !in (windowRows / 5L) until (windowRows * 4L / 5)) anchorBin += r - windowRows / 2
        }
        // The spare slot is never visible, so writing it cannot tear a column that is being drawn.
        val full = count >= capacity
        val slot = (start + count) % slots
        val base = slot * windowRows
        java.util.Arrays.fill(bid, base, base + windowRows, 0f)
        java.util.Arrays.fill(ask, base, base + windowRows, 0f)
        val lo = anchorBin * binSize
        for ((p, q) in bids) {
            val r = floor((p - lo) / binSize).toInt()
            if (r < 0) break
            if (r < windowRows) bid[base + r] += (p * q).toFloat()
        }
        for ((p, q) in asks) {
            val r = floor((p - lo) / binSize).toInt()
            if (r >= windowRows) break
            if (r >= 0) ask[base + r] += (p * q).toFloat()
        }
        colAnchor[slot] = anchorBin
        times[slot] = t
        bestBid[slot] = bb
        bestAsk[slot] = ba
        buyUsd[slot] = trades.buyUsd
        sellUsd[slot] = trades.sellUsd
        buyVwap[slot] = if (trades.buyQty > 0) trades.buyPq / trades.buyQty else Double.NaN
        sellVwap[slot] = if (trades.sellQty > 0) trades.sellPq / trades.sellQty else Double.NaN
        runningCvd += trades.buyUsd - trades.sellUsd
        cvd[slot] = runningCvd
        cvdMin = minOf(cvdMin, runningCvd)
        cvdMax = maxOf(cvdMax, runningCvd)
        updateTradeRef(trades.buyUsd + trades.sellUsd)
        updateScale(base)
        // Publish: extend the field first if the new column's window lies outside it, then the column.
        val newStart = if (full) (start + 1) % slots else start
        val newCount = if (full) count else count + 1
        var lo2 = Long.MAX_VALUE
        var hi2 = Long.MIN_VALUE
        for (c in 0 until newCount) {
            val a = colAnchor[(newStart + c) % slots]
            if (a < lo2) lo2 = a
            if (a > hi2) hi2 = a
        }
        if (lo2 < minAnchor) minAnchor = lo2
        if (hi2 > maxAnchor) maxAnchor = hi2
        if (full) start = newStart else count = newCount
        // Shrink only after the column is visible (an evicted column may have held the extreme anchor).
        minAnchor = lo2
        maxAnchor = hi2
    }

    /**
     * Colour scale, learnt from the first [WARMUP] columns and then held for good: recorded history
     * never changes colour. Liquidity far above the learnt level simply saturates the top colour;
     * the contrast control spreads it out again.
     */
    private fun updateScale(base: Int) {
        if (scaleSamples >= WARMUP) return
        val tmp = FloatArray(windowRows)
        var n = 0
        for (r in 0 until windowRows) {
            val v = bid[base + r] + ask[base + r]
            if (v > 0f) tmp[n++] = v
        }
        if (n == 0) return
        val s = tmp.copyOf(n).also { it.sort() }
        val p = s[((n - 1) * 0.985).toInt()]
        scaleSamples++
        scale += (p - scale) / scaleSamples
    }

    /**
     * Reference notional for trade-bubble size: the mean per-column traded value over the first
     * [WARMUP] columns that printed, then held so existing bubbles never resize.
     */
    var tradeRefUsd = 0.0
        private set
    private var tradeRefSamples = 0
    private var scaleSamples = 0

    private fun updateTradeRef(usd: Double) {
        if (usd <= 0.0 || tradeRefSamples >= WARMUP) return
        tradeRefSamples++
        tradeRefUsd += (usd - tradeRefUsd) / tradeRefSamples
    }

    /** CVD range over everything recorded, so its panel never rescales while you navigate. */
    var cvdMin = 0.0
        private set
    var cvdMax = 0.0
        private set

    companion object {
        const val WARMUP = 60
    }
}
