package com.nazmiefearmutcu.flowmap.mobile.ui.chart

import android.graphics.Bitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nazmiefearmutcu.flowmap.mobile.engine.HeatField
import com.nazmiefearmutcu.flowmap.mobile.engine.HeatPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A run of columns with no recording gap between them. */
data class ColumnSegment(val first: Int, val last: Int) {
    val size: Int get() = last - first + 1
}

/** Splits columns at recording gaps (see [HeatField.isGap]; a late sample is not a gap). */
fun segmentsOf(field: HeatField, cols: Int = field.cols): List<ColumnSegment> {
    if (cols == 0) return emptyList()
    val out = ArrayList<ColumnSegment>()
    var start = 0
    for (c in 1 until cols) {
        if (field.isGap(field.colTime(c - 1), field.colTime(c))) {
            out += ColumnSegment(start, c - 1)
            start = c
        }
    }
    out += ColumnSegment(start, cols - 1)
    return out
}

/** Smallest power of two ≥ [x] (at least 1). */
fun pow2AtLeast(x: Double): Int {
    var g = 1
    while (g < x && g < (1 shl 20)) g = g shl 1
    return g
}

/**
 * Turns a [HeatField] into pixels without ever letting the view change a value.
 *
 * Cells are aggregated (maximum) into fixed blocks of `colGroup × rowGroup` cells, numbered from
 * the start of each recorded segment and from the field's bottom row. Block boundaries therefore
 * depend only on the data and on the zoom level (both group sizes are powers of two); panning just
 * moves the same blocks across the screen. The block bitmap is cached and rebuilt only when the
 * data, the zoom level, the palette or the threshold change. Recording gaps stay empty.
 */
class HeatmapRasterizer {
    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var pixels = IntArray(0)
    private var cacheKey: List<Any>? = null
    private var segments: List<ColumnSegment> = emptyList()
    /** Bitmap x of each segment's first block. */
    private var segmentX = IntArray(0)
    private var colGroup = 1
    private var rowGroup = 1
    private var blockRows = 0

    /**
     * Draws [field] into the current DrawScope (already clipped to the plot).
     * [dataKey] must change whenever the field's contents change (e.g. a recording version).
     */
    fun DrawScope.drawField(
        field: HeatField,
        vp: ChartViewport,
        palette: HeatPalette,
        threshold: Float,
        smooth: Boolean,
        dataKey: Any,
        gamma: Double = 0.8,
    ) {
        val cols = field.cols
        if (cols == 0 || size.width <= 0f || size.height <= 0f) return
        // Zoom level → block size: about one block per two screen pixels at most.
        val visibleCols = vp.xSpan / field.colDurationMs
        val visibleRows = vp.ySpan / field.priceStep
        // Pure function of the zoom level: the same view always shows the same blocks.
        val cg = pow2AtLeast(visibleCols / (size.width / 2.0))
        val rg = pow2AtLeast(visibleRows / (size.height / 2.0))
        val key = listOf(field, dataKey, cols, field.priceMin, field.scaleMax, cg, rg, palette, threshold, gamma)
        if (key != cacheKey) {
            rebuild(field, cols, cg, rg, palette, threshold, gamma)
            cacheKey = key
        }
        val img = image ?: return
        val quality = if (smooth) FilterQuality.Low else FilterQuality.None
        val step = field.priceStep
        val pMin = field.priceMin

        // Visible block rows (k counts from the bottom row of the field).
        val k0 = (((vp.yMin - pMin) / step).toInt() / rowGroup).coerceIn(0, blockRows - 1)
        val k1 = (((vp.yMax - pMin) / step).toInt() / rowGroup).coerceIn(0, blockRows - 1)
        if (k1 < k0) return
        val srcY = blockRows - 1 - k1
        val srcH = k1 - k0 + 1
        // The top block may hold fewer rows than rowGroup; never stretch it past the field's top row.
        val topRow = min(field.rows, (k1 + 1) * rowGroup)
        val top = vp.yToPx(pMin + topRow.toDouble() * step)
        val bottom = vp.yToPx(pMin + k0.toDouble() * rowGroup * step)

        val dur = field.colDurationMs
        for ((si, seg) in segments.withIndex()) {
            val segStart = field.colTime(seg.first).toDouble()
            val segEnd = field.colTime(seg.last).toDouble() + dur
            if (segEnd < vp.xMin || segStart > vp.xMax) continue
            val blocks = (seg.size + colGroup - 1) / colGroup
            val fullBlocks = seg.size / colGroup
            // Visible block range inside this segment.
            val cv0 = max(seg.first, field.colAt(vp.xMin.toLong()))
            val cv1 = min(seg.last, max(seg.first, field.colAt(vp.xMax.toLong())))
            val g0 = ((cv0 - seg.first) / colGroup).coerceIn(0, blocks - 1)
            val g1 = ((cv1 - seg.first) / colGroup).coerceIn(g0, blocks - 1)
            // Whole blocks: uniform width, mapped from their first column's start time.
            val fullEnd = min(g1, fullBlocks - 1)
            if (fullEnd >= g0) {
                val left = vp.xToPx(field.colTime(seg.first + g0 * colGroup).toDouble())
                val right = vp.xToPx(field.colTime(seg.first + (fullEnd + 1) * colGroup - 1).toDouble() + dur)
                blit(img, segmentX[si] + g0, fullEnd - g0 + 1, srcY, srcH, left, right, top, bottom, quality)
            }
            // A trailing partial block (the live edge) covers only the columns it holds.
            if (g1 >= fullBlocks && fullBlocks < blocks) {
                val left = vp.xToPx(field.colTime(seg.first + fullBlocks * colGroup).toDouble())
                val right = vp.xToPx(segEnd)
                blit(img, segmentX[si] + fullBlocks, 1, srcY, srcH, left, right, top, bottom, quality)
            }
        }
    }

    private fun DrawScope.blit(img: ImageBitmap, srcX: Int, srcW: Int, srcY: Int, srcH: Int, left: Float, right: Float, top: Float, bottom: Float, q: FilterQuality) {
        val l = left.roundToInt()
        val t = top.roundToInt()
        val w = max(1, right.roundToInt() - l)
        val h = max(1, bottom.roundToInt() - t)
        drawImage(img, IntOffset(srcX, srcY), IntSize(srcW, srcH), IntOffset(l, t), IntSize(w, h), filterQuality = q)
    }

    private fun rebuild(field: HeatField, cols: Int, cg: Int, rg: Int, palette: HeatPalette, threshold: Float, gamma: Double) {
        colGroup = cg
        rowGroup = rg
        segments = segmentsOf(field, cols)
        segmentX = IntArray(segments.size)
        var w = 0
        for ((i, s) in segments.withIndex()) {
            segmentX[i] = w
            w += (s.size + cg - 1) / cg
        }
        val rows = field.rows
        blockRows = (rows + rg - 1) / rg
        val h = blockRows
        ensure(max(1, w), max(1, h))
        val stride = bitmapWidth
        val lut = palette.lut
        val bg = palette.background
        val scale = (field.scaleMax * threshold).coerceAtLeast(1e-9f)
        for ((si, s) in segments.withIndex()) {
            val blocks = (s.size + cg - 1) / cg
            for (g in 0 until blocks) {
                val c0 = s.first + g * cg
                val c1 = min(s.last, c0 + cg - 1)
                val x = segmentX[si] + g
                for (k in 0 until blockRows) {
                    val r0 = k * rg
                    val r1 = min(rows - 1, r0 + rg - 1)
                    var v = 0f
                    for (c in c0..c1) for (r in r0..r1) {
                        val cv = field.value(c, r)
                        if (cv > v) v = cv
                    }
                    pixels[(blockRows - 1 - k) * stride + x] = if (v <= 0f) bg else {
                        val t = (v / scale).coerceAtMost(1f).toDouble().pow(gamma)
                        lut[(t * 255).toInt().coerceIn(1, 255)]
                    }
                }
            }
        }
        bitmap!!.setPixels(pixels, 0, stride, 0, 0, max(1, w), h)
    }

    private var bitmapWidth = 0

    /**
     * Keeps one bitmap with headroom (width rounded up to 512 blocks): a live recording grows by a
     * column every second and must not reallocate megabytes each time. Only the used part is drawn.
     */
    private fun ensure(w: Int, h: Int) {
        val b = bitmap
        if (b == null || b.width < w || b.height != h) {
            val cap = ((w + 511) / 512) * 512
            // The old bitmap may still be referenced by a recorded display list; let GC free it.
            val nb = Bitmap.createBitmap(cap, h, Bitmap.Config.ARGB_8888)
            bitmap = nb
            image = nb.asImageBitmap()
            pixels = IntArray(cap * h)
            bitmapWidth = cap
        }
    }
}
