package com.nazmiefearmutcu.flowmap.mobile.engine

/**
 * A time × price grid of non-negative values that the heatmap renderer can sample.
 * Columns are in ascending time order; rows ascend in price from [priceMin] in steps of [priceStep].
 */
interface HeatField {
    val cols: Int
    val rows: Int
    val priceMin: Double
    val priceStep: Double

    fun colTime(c: Int): Long

    /** Duration a column covers (the last column uses this as its width). */
    val colDurationMs: Long

    fun value(c: Int, r: Int): Float

    /** Value that maps to the top of the colour ramp before the threshold is applied. */
    val scaleMax: Float

    fun rowPrice(r: Int): Double = priceMin + (r + 0.5) * priceStep
    /** Row holding [price]; negative below the map (floor, not truncation toward zero). */
    fun rowAt(price: Double): Int = kotlin.math.floor((price - priceMin) / priceStep).toInt()

    /** True when two consecutive column start times are far enough apart to be a recording gap. */
    fun isGap(prevStart: Long, nextStart: Long): Boolean = nextStart - prevStart > colDurationMs * 3

    /** Largest column whose start time is ≤ [t], or -1 before the first column. */
    fun colAt(t: Long): Int {
        if (cols == 0 || t < colTime(0)) return -1
        var lo = 0
        var hi = cols - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (colTime(mid) <= t) lo = mid else hi = mid - 1
        }
        return lo
    }
}
