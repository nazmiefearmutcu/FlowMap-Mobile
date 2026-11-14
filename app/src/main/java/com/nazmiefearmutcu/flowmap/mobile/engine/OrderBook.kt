package com.nazmiefearmutcu.flowmap.mobile.engine

import java.util.TreeMap

/** Local L2 order book: price → quantity, maintained from a snapshot plus incremental updates. */
class OrderBook {
    val bids = TreeMap<Double, Double>(reverseOrder())
    val asks = TreeMap<Double, Double>()

    fun clear() { bids.clear(); asks.clear() }

    fun replace(b: List<Pair<Double, Double>>, a: List<Pair<Double, Double>>) {
        clear()
        apply(b, a)
    }

    fun apply(b: List<Pair<Double, Double>>, a: List<Pair<Double, Double>>) {
        for ((p, q) in b) if (q <= 0.0) bids.remove(p) else bids[p] = q
        for ((p, q) in a) if (q <= 0.0) asks.remove(p) else asks[p] = q
        // A crossed book after a missed update is self-healed by dropping the stale side.
        while (bids.isNotEmpty() && asks.isNotEmpty() && bids.firstKey() >= asks.firstKey()) {
            if (b.isNotEmpty()) asks.pollFirstEntry() else bids.pollFirstEntry()
        }
    }

    val bestBid: Double get() = if (bids.isEmpty()) Double.NaN else bids.firstKey()
    val bestAsk: Double get() = if (asks.isEmpty()) Double.NaN else asks.firstKey()
    val mid: Double get() = if (bids.isEmpty() || asks.isEmpty()) Double.NaN else (bestBid + bestAsk) / 2

    /** Top [n] levels grouped into buckets of [group] price units (for the DOM ladder). */
    fun ladder(n: Int, group: Double): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        fun grouped(src: Map<Double, Double>, floorFn: (Double) -> Double): List<Pair<Double, Double>> {
            val out = LinkedHashMap<Double, Double>()
            for ((p, q) in src) {
                val k = floorFn(p)
                out[k] = (out[k] ?: 0.0) + q
                if (out.size > n) break
            }
            return out.entries.take(n).map { it.key to it.value }
        }
        val g = if (group > 0) group else 0.0
        val b = if (g > 0) grouped(bids) { kotlin.math.floor(it / g) * g } else bids.entries.take(n).map { it.key to it.value }
        val a = if (g > 0) grouped(asks) { kotlin.math.ceil(it / g) * g } else asks.entries.take(n).map { it.key to it.value }
        return b to a
    }
}
