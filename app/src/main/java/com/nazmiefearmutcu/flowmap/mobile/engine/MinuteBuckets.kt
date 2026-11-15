package com.nazmiefearmutcu.flowmap.mobile.engine

/**
 * Long/short sums for each of the last [size] minutes, kept in a ring indexed by epoch minute.
 * Independent of any event buffer, so heavy cascades never truncate older minutes.
 */
class MinuteBuckets(val size: Int = 60) {
    private val minute = LongArray(size) { Long.MIN_VALUE }
    private val long = DoubleArray(size)
    private val short = DoubleArray(size)

    fun add(timeMs: Long, usd: Double, isLong: Boolean, nowMs: Long) {
        val nowMin = Math.floorDiv(nowMs, 60_000L)
        // A venue clock slightly ahead of the phone's must not lose the event: count it now.
        val m = minOf(Math.floorDiv(timeMs, 60_000L), nowMin)
        if (nowMin - m >= size) return
        val i = Math.floorMod(m, size.toLong()).toInt()
        if (minute[i] != m) { minute[i] = m; long[i] = 0.0; short[i] = 0.0 }
        if (isLong) long[i] += usd else short[i] += usd
    }

    /** Oldest-first arrays for the [size] minutes ending with the minute containing [nowMs]. */
    fun snapshot(nowMs: Long): Pair<DoubleArray, DoubleArray> {
        val nowMin = Math.floorDiv(nowMs, 60_000L)
        val l = DoubleArray(size)
        val s = DoubleArray(size)
        for (k in 0 until size) {
            val m = nowMin - (size - 1 - k)
            val i = Math.floorMod(m, size.toLong()).toInt()
            if (minute[i] == m) { l[k] = long[i]; s[k] = short[i] }
        }
        return l to s
    }
}
