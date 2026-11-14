package com.nazmiefearmutcu.flowmap.mobile.ui.chart

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nazmiefearmutcu.flowmap.mobile.engine.HeatField
import com.nazmiefearmutcu.flowmap.mobile.engine.HeatPalette
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Samples a [HeatField] into a small bitmap covering exactly the visible viewport. Each output
 * pixel takes the maximum of the cells it covers, so thin liquidity bands never vanish when
 * zoomed out. The bitmap is drawn stretched over the plot: nearest-neighbour for the Pixel style,
 * bilinear for Smooth.
 */
class HeatmapRasterizer {
    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var pixels = IntArray(0)
    private var colStart = IntArray(0)
    private var colEnd = IntArray(0)
    private var rowStart = IntArray(0)
    private var rowEnd = IntArray(0)

    /**
     * @param cell output pixel size in screen pixels (2–4); larger is faster and chunkier.
     * @param emptyColor colour for areas with no columns (before/after the recorded data).
     */
    fun render(
        field: HeatField,
        vp: ChartViewport,
        widthPx: Int,
        heightPx: Int,
        palette: HeatPalette,
        threshold: Float,
        cell: Int,
        emptyColor: Int,
        gamma: Double = 0.8,
    ): ImageBitmap? {
        if (widthPx <= 0 || heightPx <= 0 || field.cols == 0) return null
        val bw = ceil(widthPx / cell.toFloat()).toInt().coerceAtLeast(1)
        val bh = ceil(heightPx / cell.toFloat()).toInt().coerceAtLeast(1)
        ensure(bw, bh)
        val lut = palette.lut
        val bg = palette.background
        val cols = field.cols
        val rows = field.rows
        val firstT = field.colTime(0)
        val lastEnd = field.colTime(cols - 1) + field.colDurationMs

        // Column range per output x.
        val xSpan = vp.xSpan
        for (x in 0 until bw) {
            val t0 = (vp.xMin + x.toDouble() / bw * xSpan).toLong()
            val t1 = (vp.xMin + (x + 1).toDouble() / bw * xSpan).toLong()
            if (t1 < firstT || t0 >= lastEnd) { colStart[x] = -1; colEnd[x] = -2; continue }
            val a = max(0, field.colAt(t0))
            val b = max(a, field.colAt(t1 - 1))
            colStart[x] = a
            colEnd[x] = min(b, cols - 1)
        }
        // Row range per output y (y = 0 is the top).
        val ySpan = vp.ySpan
        for (y in 0 until bh) {
            val pTop = vp.yMax - y.toDouble() / bh * ySpan
            val pBot = vp.yMax - (y + 1).toDouble() / bh * ySpan
            val a = field.rowAt(pBot)
            val b = field.rowAt(pTop)
            if (b < 0 || a >= rows) { rowStart[y] = -1; rowEnd[y] = -2; continue }
            rowStart[y] = a.coerceIn(0, rows - 1)
            rowEnd[y] = max(rowStart[y], b.coerceIn(0, rows - 1))
        }

        val scale = (field.scaleMax * threshold).coerceAtLeast(1e-9f)
        for (x in 0 until bw) {
            val c0 = colStart[x]
            val c1 = colEnd[x]
            if (c0 < 0) {
                for (y in 0 until bh) pixels[y * bw + x] = emptyColor
                continue
            }
            val cStep = max(1, (c1 - c0 + 1) / 6)
            for (y in 0 until bh) {
                val r0 = rowStart[y]
                val r1 = rowEnd[y]
                if (r0 < 0) { pixels[y * bw + x] = bg; continue }
                val rStep = max(1, (r1 - r0 + 1) / 8)
                var v = 0f
                var c = c0
                while (c <= c1) {
                    var r = r0
                    while (r <= r1) {
                        val cv = field.value(c, r)
                        if (cv > v) v = cv
                        r += rStep
                    }
                    c += cStep
                }
                pixels[y * bw + x] = if (v <= 0f) bg else {
                    val t = (v / scale).coerceAtMost(1f).toDouble().pow(gamma)
                    lut[(t * 255).toInt().coerceIn(1, 255)]
                }
            }
        }
        bitmap!!.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        return image
    }

    private fun ensure(w: Int, h: Int) {
        val b = bitmap
        if (b == null || b.width != w || b.height != h) {
            // The old bitmap may still be referenced by a recorded display list; let GC free it.
            val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = nb
            image = nb.asImageBitmap()
            pixels = IntArray(w * h)
        }
        if (colStart.size < w) { colStart = IntArray(w); colEnd = IntArray(w) }
        if (rowStart.size < h) { rowStart = IntArray(h); rowEnd = IntArray(h) }
    }
}
