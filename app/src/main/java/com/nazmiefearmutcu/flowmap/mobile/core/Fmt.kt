package com.nazmiefearmutcu.flowmap.mobile.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Number and time formatting shared by every screen. English locale only for the first release. */
object Fmt {
    private val loc = Locale.US

    /** Price with precision adapted to magnitude: 90282, 3125.4, 0.3412, 0.00001234. */
    fun price(p: Double): String {
        if (p.isNaN() || p.isInfinite()) return "—"
        val a = abs(p)
        val decimals = when {
            a >= 10_000 -> 1
            a >= 1_000 -> 2
            a >= 10 -> 3
            a >= 1 -> 4
            a == 0.0 -> 2
            else -> (-floor(log10(a))).toInt() + 3
        }.coerceIn(0, 10)
        return String.format(loc, "%,.${decimals}f", p)
    }

    /** Axis label: same as [price] but drops decimals once ticks are wide enough. */
    fun axisPrice(p: Double, step: Double): String {
        if (step <= 0.0) return price(p)
        val decimals = when {
            step >= 1.0 -> 0
            else -> (-floor(log10(step))).toInt().coerceIn(0, 10)
        }
        return String.format(loc, "%.${decimals}f", p)
    }

    /** 200.82M, 1.24B, 12.4K, 950. */
    fun compact(v: Double, decimals: Int = 2): String {
        if (v.isNaN()) return "—"
        val a = abs(v)
        val (div, suf) = when {
            a >= 1e12 -> 1e12 to "T"
            a >= 1e9 -> 1e9 to "B"
            a >= 1e6 -> 1e6 to "M"
            a >= 1e3 -> 1e3 to "K"
            else -> 1.0 to ""
        }
        val d = if (suf.isEmpty() && a >= 100) 0 else decimals
        return String.format(loc, "%.${d}f%s", v / div, suf)
    }

    fun usd(v: Double): String = (if (v < 0) "-$" else "$") + compact(abs(v))

    fun pct(v: Double, decimals: Int = 2, signed: Boolean = true): String {
        if (v.isNaN()) return "—"
        val s = String.format(loc, "%.${decimals}f%%", v)
        return if (signed && v > 0) "+$s" else s
    }

    /** Funding rate given as a fraction (0.0001 = 0.0100%). */
    fun funding(rate: Double): String = pct(rate * 100.0, 4)

    fun ratio(v: Double): String = String.format(loc, "%.2f", v)

    private val fmtCache = HashMap<String, SimpleDateFormat>()
    private fun fmt(pattern: String): SimpleDateFormat = synchronized(fmtCache) {
        fmtCache.getOrPut(pattern) { SimpleDateFormat(pattern, loc) }
    }

    fun dateTime(ms: Long): String = fmt("yyyy-MM-dd HH:mm").format(Date(ms))
    fun timeSec(ms: Long): String = fmt("HH:mm:ss").format(Date(ms))
    fun timeMin(ms: Long): String = fmt("HH:mm").format(Date(ms))

    /** Axis label for a time tick; detail depends on the visible span. */
    fun axisTime(ms: Long, spanMs: Long): String = when {
        spanMs <= 2 * 60_000L -> fmt("HH:mm:ss").format(Date(ms))
        spanMs <= 36 * 3_600_000L -> fmt("HH:mm").format(Date(ms))
        spanMs <= 120 * 86_400_000L -> fmt("MMM d").format(Date(ms))
        else -> fmt("MMM yy").format(Date(ms))
    }

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }

    /** A "nice" step (1, 2, 2.5, 5 × 10^n) near [raw]. */
    fun niceStep(raw: Double): Double {
        if (raw <= 0.0 || raw.isNaN()) return 1.0
        val exp = floor(log10(raw))
        val base = 10.0.pow(exp)
        val f = raw / base
        val nf = when {
            f < 1.5 -> 1.0
            f < 2.25 -> 2.0
            f < 3.5 -> 2.5
            f < 7.5 -> 5.0
            else -> 10.0
        }
        return nf * base
    }
}
