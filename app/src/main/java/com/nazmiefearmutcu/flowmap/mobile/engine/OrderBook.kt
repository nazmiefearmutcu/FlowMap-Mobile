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
        // A crossed book means one side holds a level a missed update should have removed. The level
        // this update just wrote is fresh, so the stale one is the crossing level on the other side.
        while (bids.isNotEmpty() && asks.isNotEmpty() && bids.firstKey() >= asks.firstKey()) {
            val bidFresh = b.any { it.first == bids.firstKey() && it.second > 0.0 }
            val askFresh = a.any { it.first == asks.firstKey() && it.second > 0.0 }
            when {
                bidFresh && !askFresh -> asks.pollFirstEntry()
                askFresh && !bidFresh -> bids.pollFirstEntry()
                // Neither or both touched: drop both crossing levels rather than guess.
                else -> { bids.pollFirstEntry(); asks.pollFirstEntry() }
            }
        }
    }

    val bestBid: Double get() = if (bids.isEmpty()) Double.NaN else bids.firstKey()
    val bestAsk: Double get() = if (asks.isEmpty()) Double.NaN else asks.firstKey()
    val mid: Double get() = if (bids.isEmpty() || asks.isEmpty()) Double.NaN else (bestBid + bestAsk) / 2

    /** Top [n] levels of each side, best first, for the DOM ladder. */
    fun ladder(n: Int): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> =
        bids.entries.take(n).map { it.key to it.value } to asks.entries.take(n).map { it.key to it.value }
}
