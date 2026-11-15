package com.nazmiefearmutcu.flowmap.mobile.ui.chart

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.min

/**
 * Visible window of a time × price chart. X is epoch milliseconds, Y is price.
 * All gesture math lives here so it can be unit-tested without a device.
 */
@Stable
class ChartViewport {
    var xMin by mutableDoubleStateOf(0.0)
        private set
    var xMax by mutableDoubleStateOf(1.0)
        private set
    var yMin by mutableDoubleStateOf(0.0)
        private set
    var yMax by mutableDoubleStateOf(1.0)
        private set

    /** Keep the right edge pinned to the newest data while true. */
    var follow by mutableStateOf(true)

    /** Fit the price axis to visible data while true; any vertical gesture turns it off. */
    var autoY by mutableStateOf(true)

    /** Bumped on every change so drawing code can key caches. */
    var revision by mutableIntStateOf(0)
        private set

    var initialized = false
        private set

    // Data limits for clamping.
    var dataStart = 0.0
    var dataEnd = 1.0
    var minXSpan = 60_000.0
    var minYSpanFraction = 0.0005

    /** Plot size in pixels, updated by the chart layout. */
    var plotW = 1f
    var plotH = 1f

    val xSpan: Double get() = xMax - xMin
    val ySpan: Double get() = yMax - yMin

    fun set(x0: Double, x1: Double, y0: Double, y1: Double) {
        if (x1 <= x0 || y1 <= y0 || x0.isNaN() || y0.isNaN()) return
        xMin = x0; xMax = x1; yMin = y0; yMax = y1
        initialized = true
        revision++
    }

    fun setY(y0: Double, y1: Double) {
        if (y1 <= y0 || y0.isNaN() || y1.isNaN()) return
        if (y0 == yMin && y1 == yMax) return
        yMin = y0; yMax = y1
        revision++
    }

    fun xToPx(t: Double): Float = ((t - xMin) / xSpan * plotW).toFloat()
    fun yToPx(p: Double): Float = ((yMax - p) / ySpan * plotH).toFloat()
    fun pxToX(px: Float): Double = xMin + px / plotW * xSpan
    fun pxToY(py: Float): Double = yMax - py / plotH * ySpan

    /** Moves the content by (dx, dy) pixels — the finger's movement. */
    fun pan(dx: Float, dy: Float) {
        val tx = -dx / plotW * xSpan
        val ty = dy / plotH * ySpan
        if (dx != 0f) {
            xMin += tx; xMax += tx
            // Any deliberate horizontal pan leaves the live edge (the gesture layer filters drift),
            // so a move is never silently undone by the next live update.
            follow = false
        }
        if (dy != 0f) {
            yMin += ty; yMax += ty
            autoY = false
        }
        clampX()
        revision++
    }

    /**
     * Zooms by [fx] horizontally and [fy] vertically (>1 = zoom in) around the pixel (ax, ay).
     * The two axes are independent, so a horizontal pinch scales time and a vertical pinch scales price.
     */
    fun zoom(fx: Float, fy: Float, ax: Float, ay: Float) {
        if (fx != 1f && fx > 0f) {
            val anchor = if (follow) xMax else pxToX(ax)
            val span = (xSpan / fx).coerceIn(minXSpan, max(minXSpan, (dataEnd - dataStart) * 3.0))
            val frac = (anchor - xMin) / xSpan
            xMin = anchor - frac * span
            xMax = xMin + span
            clampX()
        }
        if (fy != 1f && fy > 0f) {
            val anchor = pxToY(ay)
            val mid = (yMin + yMax) / 2
            val minSpan = max(1e-12, mid * minYSpanFraction)
            val span = (ySpan / fy).coerceIn(minSpan, mid * 4)
            val frac = (anchor - yMin) / ySpan
            yMin = anchor - frac * span
            yMax = yMin + span
            autoY = false
        }
        revision++
    }

    /** Keeps at least 15% of the window over data and allows half a window of future space. */
    private fun clampX() {
        val span = xSpan
        val lo = dataStart - span * 0.85
        val hi = dataEnd + span * 0.5
        if (xMin < lo) { xMin = lo; xMax = lo + span }
        if (xMax > hi) { xMax = hi; xMin = hi - span }
    }

    /** Pins the right edge to [latest] (+ a small margin) when following. */
    fun followTo(latest: Double, marginFraction: Double = 0.06) {
        if (!follow) return
        val span = xSpan
        val newMax = latest + span * marginFraction
        if (newMax == xMax) return
        xMax = newMax
        xMin = newMax - span
        revision++
    }

    fun clampSpan(maxSpan: Double) {
        if (xSpan > maxSpan) {
            xMin = xMax - maxSpan
            revision++
        }
    }
}

/** Fits [lo, hi] with [pad] fractional padding on both ends. */
fun paddedRange(lo: Double, hi: Double, pad: Double = 0.08): Pair<Double, Double> {
    if (lo.isNaN() || hi.isNaN()) return 0.0 to 1.0
    val l = min(lo, hi)
    val h = max(lo, hi)
    val span = if (h - l <= 0) max(1e-9, l * 0.01) else h - l
    return (l - span * pad) to (h + span * pad)
}
