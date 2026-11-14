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
 */
class DepthHeatmap(val capacity: Int = 3000, override val rows: Int = 600) : HeatField {
    var binSize = 1.0
        private set
    private var anchorBin = 0L
    private val bid = FloatArray(capacity * rows)
    private val ask = FloatArray(capacity * rows)
    private val times = LongArray(capacity)
    private val bestBid = DoubleArray(capacity) { Double.NaN }
    private val bestAsk = DoubleArray(capacity) { Double.NaN }
    private val buyUsd = DoubleArray(capacity)
    private val sellUsd = DoubleArray(capacity)
    private val buyVwap = DoubleArray(capacity) { Double.NaN }
    private val sellVwap = DoubleArray(capacity) { Double.NaN }
    private val cvd = DoubleArray(capacity)
    private var start = 0
    private var count = 0
    private var runningCvd = 0.0
    private var scale = 1f
    var mode = DepthMode.TOTAL
    var sampleMs = 1000L

    @Volatile
    var initialized = false
        private set

    override val cols: Int get() = count
    override val priceMin: Double get() = anchorBin * binSize
    override val priceStep: Double get() = binSize
    override val colDurationMs: Long get() = sampleMs
    override val scaleMax: Float get() = scale

    private fun phys(c: Int) = (start + c) % capacity

    override fun colTime(c: Int): Long = times[phys(c)]

    override fun value(c: Int, r: Int): Float {
        val i = phys(c) * rows + r
        return when (mode) {
            DepthMode.TOTAL -> bid[i] + ask[i]
            DepthMode.BIDS -> bid[i]
            DepthMode.ASKS -> ask[i]
        }
    }

    fun bidAt(c: Int, r: Int): Float = bid[phys(c) * rows + r]
    fun askAt(c: Int, r: Int): Float = ask[phys(c) * rows + r]
    fun bestBidAt(c: Int) = bestBid[phys(c)]
    fun bestAskAt(c: Int) = bestAsk[phys(c)]
    fun buyUsdAt(c: Int) = buyUsd[phys(c)]
    fun sellUsdAt(c: Int) = sellUsd[phys(c)]
    fun buyVwapAt(c: Int) = buyVwap[phys(c)]
    fun sellVwapAt(c: Int) = sellVwap[phys(c)]
    fun cvdAt(c: Int) = cvd[phys(c)]

    /** Chooses a bin of roughly one basis point and centres the window on [mid]. */
    fun init(mid: Double) {
        binSize = Fmt.niceStep(mid * 0.0001)
        anchorBin = floor(mid / binSize).toLong() - rows / 2
        initialized = true
    }

    /** Records one column from the current book. */
    fun record(t: Long, bids: Map<Double, Double>, asks: Map<Double, Double>, bb: Double, ba: Double, trades: TradeAccumulator) {
        if (!initialized) {
            if (bb.isNaN() || ba.isNaN()) return
            init((bb + ba) / 2)
        }
        val mid = if (!bb.isNaN() && !ba.isNaN()) (bb + ba) / 2 else Double.NaN
        if (!mid.isNaN()) recenterIfNeeded(mid)
        val slot = if (count < capacity) { count++; phys(count - 1) } else { start = (start + 1) % capacity; phys(count - 1) }
        val base = slot * rows
        java.util.Arrays.fill(bid, base, base + rows, 0f)
        java.util.Arrays.fill(ask, base, base + rows, 0f)
        val lo = priceMin
        for ((p, q) in bids) {
            val r = floor((p - lo) / binSize).toInt()
            if (r < 0) break
            if (r < rows) bid[base + r] += (p * q).toFloat()
        }
        for ((p, q) in asks) {
            val r = floor((p - lo) / binSize).toInt()
            if (r >= rows) break
            if (r >= 0) ask[base + r] += (p * q).toFloat()
        }
        times[slot] = t
        bestBid[slot] = bb
        bestAsk[slot] = ba
        buyUsd[slot] = trades.buyUsd
        sellUsd[slot] = trades.sellUsd
        buyVwap[slot] = if (trades.buyQty > 0) trades.buyPq / trades.buyQty else Double.NaN
        sellVwap[slot] = if (trades.sellQty > 0) trades.sellPq / trades.sellQty else Double.NaN
        runningCvd += trades.buyUsd - trades.sellUsd
        cvd[slot] = runningCvd
        updateScale(base)
    }

    private fun updateScale(base: Int) {
        val tmp = FloatArray(rows)
        var n = 0
        for (r in 0 until rows) {
            val v = bid[base + r] + ask[base + r]
            if (v > 0f) tmp[n++] = v
        }
        if (n == 0) return
        val s = tmp.copyOf(n).also { it.sort() }
        val p = s[((n - 1) * 0.985).toInt()]
        scale = if (count <= 1) p else scale * 0.9f + p * 0.1f
    }

    /** Keeps the mid price inside the middle 60% of the window by shifting stored rows. */
    private fun recenterIfNeeded(mid: Double) {
        val r = ((mid - priceMin) / binSize).toInt()
        if (r in (rows / 5) until (rows * 4 / 5)) return
        val shift = r - rows / 2
        val newAnchor = anchorBin + shift
        for (c in 0 until count) {
            val base = phys(c) * rows
            shiftRows(bid, base, shift)
            shiftRows(ask, base, shift)
        }
        anchorBin = newAnchor
    }

    private fun shiftRows(a: FloatArray, base: Int, shift: Int) {
        if (shift > 0) {
            System.arraycopy(a, base + shift.coerceAtMost(rows), a, base, (rows - shift).coerceAtLeast(0))
            java.util.Arrays.fill(a, base + (rows - shift).coerceAtLeast(0), base + rows, 0f)
        } else if (shift < 0) {
            val s = -shift
            System.arraycopy(a, base, a, base + s.coerceAtMost(rows), (rows - s).coerceAtLeast(0))
            java.util.Arrays.fill(a, base, base + s.coerceAtMost(rows), 0f)
        }
    }

    fun reset() {
        start = 0; count = 0; runningCvd = 0.0; scale = 1f; initialized = false
    }
}
