package com.nazmiefearmutcu.flowmap.mobile.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** One tooltip row: label on the left, value on the right. */
data class TipLine(val label: String, val value: String, val color: Color = Fm.text)

class Tooltip(val title: String, val lines: List<TipLine>)

/**
 * A time × price chart surface: plot, right price axis, bottom time axis, multi-touch gestures,
 * a crosshair and the inspection tooltip. Content is drawn by [drawPlot] in plot coordinates.
 */
@Composable
fun ChartFrame(
    viewport: ChartViewport,
    modifier: Modifier = Modifier,
    background: Color = Fm.bg,
    haptics: Boolean = true,
    axisWidth: Dp = 58.dp,
    axisHeight: Dp = 22.dp,
    onReset: () -> Unit,
    onAutoFitY: () -> Unit = { viewport.autoY = true },
    tooltipAt: (time: Double, price: Double) -> Tooltip?,
    drawPlot: DrawScope.(plot: Size) -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer(cacheSize = 96)
    // The inspected point is a (time, price) pair, not a screen position: panning moves the
    // crosshair with the data, so the tooltip keeps reporting the same cell.
    var mark by remember(viewport) { mutableStateOf<Pair<Double, Double>?>(null) }
    fun toData(p: Offset) = viewport.pxToX(p.x) to viewport.pxToY(p.y)
    var sticky by remember { mutableStateOf(false) }
    // Gesture handlers live as long as the viewport; read the latest lambdas and settings through
    // these so a new heatmap, reset target or haptics choice takes effect immediately.
    val onResetNow by rememberUpdatedState(onReset)
    val onAutoFitNow by rememberUpdatedState(onAutoFitY)
    val hapticsNow by rememberUpdatedState(haptics)

    Row(modifier.background(background)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .onSizeChanged { viewport.plotW = it.width.toFloat().coerceAtLeast(1f); viewport.plotH = it.height.toFloat().coerceAtLeast(1f) }
                    .chartGestures(
                        viewport, scope,
                        ChartGestureCallbacks(
                            onTap = { p -> if (mark != null && sticky) { mark = null; sticky = false } else { mark = toData(p); sticky = true } },
                            onDoubleTap = { mark = null; sticky = false; onResetNow() },
                            onScrubStart = { p -> if (hapticsNow) haptic.performHapticFeedback(HapticFeedbackType.LongPress); mark = toData(p); sticky = true },
                            onScrub = { p -> mark = toData(p) },
                            onScrubEnd = { },
                            onInteraction = { if (!sticky) mark = null },
                        ),
                    ),
            ) {
                // Reading revision subscribes this canvas to every viewport change.
                Canvas(Modifier.fillMaxSize()) {
                    viewport.revision
                    viewport.plotW = size.width
                    viewport.plotH = size.height
                    clipRect { drawPlot(size) }
                    // Grid over the plot: the heatmap is opaque and would hide it.
                    drawGrid(viewport)
                    mark?.let { (t, p) -> drawCrosshair(Offset(viewport.xToPx(t), viewport.yToPx(p)), size) }
                }
                overlay()
                val m = mark
                if (m != null) {
                    viewport.revision // follow the data point while the view moves
                    val c = Offset(viewport.xToPx(m.first), viewport.yToPx(m.second))
                    val onScreen = c.x in 0f..viewport.plotW && c.y in 0f..viewport.plotH
                    val tip = if (onScreen) tooltipAt(m.first, m.second) else null
                    if (tip != null) TooltipBox(tip, c)
                }
            }
            // Time axis
            Box(Modifier.fillMaxWidth().height(axisHeight).timeAxisGestures(viewport) { onResetNow() }) {
                Canvas(Modifier.fillMaxSize()) {
                    viewport.revision
                    drawTimeAxis(viewport, measurer, mark?.let { viewport.xToPx(it.first) })
                }
            }
        }
        // Price axis
        Column(Modifier.width(axisWidth).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth().priceAxisGestures(viewport) { onAutoFitNow() }) {
                Canvas(Modifier.fillMaxSize()) {
                    viewport.revision
                    drawPriceAxis(viewport, measurer, mark?.let { viewport.yToPx(it.second) })
                }
            }
            Spacer(Modifier.height(axisHeight))
        }
    }
}

private val axisStyle = TextStyle(color = Fm.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
private val gridColor = Color.White.copy(alpha = 0.07f)

private fun priceTicks(vp: ChartViewport, count: Int = 6): Pair<Double, List<Double>> {
    val step = Fmt.niceStep(vp.ySpan / count)
    val first = ceil(vp.yMin / step) * step
    val out = ArrayList<Double>()
    var p = first
    while (p <= vp.yMax && out.size < 40) { out += p; p += step }
    return step to out
}

private val timeSteps = longArrayOf(
    1_000, 5_000, 15_000, 30_000, 60_000, 300_000, 900_000, 1_800_000, 3_600_000, 7_200_000, 14_400_000, 21_600_000,
    43_200_000, 86_400_000, 2 * 86_400_000L, 7 * 86_400_000L, 14 * 86_400_000L, 30 * 86_400_000L, 91 * 86_400_000L, 182 * 86_400_000L, 365 * 86_400_000L,
)

private fun timeTicks(vp: ChartViewport, count: Int = 4): List<Long> {
    val raw = vp.xSpan / count
    val step = timeSteps.firstOrNull { it >= raw } ?: timeSteps.last()
    val tz = java.util.TimeZone.getDefault()
    val offset = if (step >= 86_400_000L) tz.getOffset(vp.xMin.toLong()).toLong() else 0L
    var t = (floor((vp.xMin + offset) / step) * step).toLong() - offset
    val out = ArrayList<Long>()
    while (t <= vp.xMax && out.size < 20) { if (t >= vp.xMin) out += t; t += step }
    return out
}

private fun DrawScope.drawGrid(vp: ChartViewport) {
    val (_, ticks) = priceTicks(vp)
    for (p in ticks) {
        val y = vp.yToPx(p)
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
    }
    for (t in timeTicks(vp)) {
        val x = vp.xToPx(t.toDouble())
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
    }
}

private fun DrawScope.drawPriceAxis(vp: ChartViewport, measurer: androidx.compose.ui.text.TextMeasurer, crossY: Float?) {
    val (step, ticks) = priceTicks(vp)
    for (p in ticks) {
        val y = vp.yToPx(p)
        val layout = measurer.measure(Fmt.axisPrice(p, step), axisStyle)
        if (y - layout.size.height / 2 < 0 || y + layout.size.height / 2 > size.height) continue
        drawText(layout, topLeft = Offset(6.dp.toPx(), y - layout.size.height / 2))
    }
    if (crossY != null && crossY in 0f..size.height) {
        val label = measurer.measure(Fmt.price(vp.pxToY(crossY)), axisStyle.copy(color = Fm.bg, fontWeight = FontWeight.Bold))
        val h = label.size.height + 6.dp.toPx()
        val top = (crossY - h / 2).coerceIn(0f, size.height - h)
        drawRect(Fm.yellow, Offset(0f, top), Size(size.width, h))
        drawText(label, topLeft = Offset(4.dp.toPx(), top + 3.dp.toPx()))
    }
}

private fun DrawScope.drawTimeAxis(vp: ChartViewport, measurer: androidx.compose.ui.text.TextMeasurer, crossX: Float?) {
    val span = vp.xSpan.toLong()
    for (t in timeTicks(vp)) {
        val x = vp.xToPx(t.toDouble())
        val layout = measurer.measure(Fmt.axisTime(t, span), axisStyle.copy(color = Fm.textDim))
        val left = x - layout.size.width / 2
        if (left < 0 || left + layout.size.width > size.width) continue
        drawText(layout, topLeft = Offset(left, (size.height - layout.size.height) / 2))
    }
    if (crossX != null && crossX in 0f..size.width) {
        val label = measurer.measure(Fmt.dateTime(vp.pxToX(crossX).toLong()), axisStyle.copy(color = Fm.bg, fontWeight = FontWeight.Bold))
        val w = label.size.width + 8.dp.toPx()
        val left = (crossX - w / 2).coerceIn(0f, size.width - w)
        drawRect(Fm.yellow, Offset(left, 0f), Size(w, size.height))
        drawText(label, topLeft = Offset(left + 4.dp.toPx(), (size.height - label.size.height) / 2))
    }
}

private val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

private fun DrawScope.drawCrosshair(p: Offset, s: Size) {
    val c = Color.White.copy(alpha = 0.75f)
    drawLine(c, Offset(p.x, 0f), Offset(p.x, s.height), 1.2f, pathEffect = dash)
    drawLine(c, Offset(0f, p.y), Offset(s.width, p.y), 1.2f, pathEffect = dash)
    drawCircle(Fm.yellow, 5.dp.toPx(), p)
    drawCircle(Fm.bg, 2.5.dp.toPx(), p)
}

/** Yellow-bordered tooltip with a pointer, placed above the finger and kept inside the plot. */
@Composable
private fun BoxScope.TooltipBox(tip: Tooltip, anchor: Offset) {
    val density = LocalDensity.current
    Layout(
        content = {
            Column(
                Modifier.widthIn(min = 200.dp, max = 300.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xF0101626))
                    .border(2.dp, Fm.tooltipBorder, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(tip.title, color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, style = Fm.num)
                tip.lines.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l.label, color = Fm.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(l.value, color = l.color, fontWeight = FontWeight.Bold, fontSize = 14.sp, style = Fm.num)
                    }
                }
            }
        },
        modifier = Modifier.matchParentSize(),
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val gap = with(density) { 18.dp.roundToPx() }
            val x = (anchor.x - placeable.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
            val above = anchor.y - gap - placeable.height
            val y = (if (above >= 0) above else anchor.y + gap).roundToInt().coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
            placeable.place(x, y)
        }
    }
}
