package com.nazmiefearmutcu.flowmap.mobile.ui.screens.liq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.ChartStyle
import com.nazmiefearmutcu.flowmap.mobile.data.Provenance
import com.nazmiefearmutcu.flowmap.mobile.engine.HeatPalette
import com.nazmiefearmutcu.flowmap.mobile.engine.LiqHeatmap
import com.nazmiefearmutcu.flowmap.mobile.engine.LiqMapBars
import com.nazmiefearmutcu.flowmap.mobile.engine.LiqModel
import com.nazmiefearmutcu.flowmap.mobile.ui.brand.PixelLogo
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.ChartFrame
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.ChartViewport
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.HeatmapRasterizer
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.TipLine
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.Tooltip
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.drawCandles
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.drawLastPrice
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.lowerBound
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.paddedRange
import com.nazmiefearmutcu.flowmap.mobile.ui.components.DropdownChip
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ErrorBanner
import com.nazmiefearmutcu.flowmap.mobile.ui.components.PillTabs
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ProvenanceBadge
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Segmented
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SymbolPickerSheet
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LiqMapScreen(fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    val vm: LiqMapViewModel = viewModel { LiqMapViewModel() }
    val ui by vm.ui.collectAsState()
    val settings = AppGraph.settings
    val palette by settings.palette.collectAsState()
    val threshold by settings.threshold.collectAsState()
    val style by settings.style.collectAsState()
    val haptics by settings.haptics.collectAsState()
    var sub by remember { mutableStateOf(0) }
    var picker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            PillTabs(listOf("Liq Heatmap", "Liq Map"), sub, { sub = it }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Segmented(listOf("Pair", "Symbol"), ui.scope.ordinal, { vm.setScope(Scope.entries[it]) }, Modifier.weight(1f))
                DropdownChip(ui.model.label, LiqModel.entries, { it.label }, vm::setModel, Modifier.weight(1f), optionDetail = { it.summary })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                DropdownChip(
                    palette.label, HeatPalette.entries, { it.label }, settings::setPalette,
                    leading = { PaletteSwatch(palette, Modifier.padding(end = 0.dp)); Spacer(Modifier.width(8.dp)) },
                )
                Spacer(Modifier.width(12.dp))
                Text("Liquidity Threshold = ${String.format(java.util.Locale.US, "%.2f", threshold)}", color = Fm.text, fontSize = 13.sp, style = Fm.num)
                Slider(
                    value = threshold, onValueChange = settings::previewThreshold, onValueChangeFinished = settings::commitThreshold, valueRange = 0.05f..1f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF2F6BFF), inactiveTrackColor = Fm.surfaceTop),
                    thumb = { Box(Modifier.size(width = 40.dp, height = 26.dp).clip(RoundedCornerShape(50)).background(Color.White)) },
                    track = { st ->
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Fm.surfaceTop)) {
                            Box(Modifier.fillMaxWidth(((st.value - 0.05f) / 0.95f).coerceIn(0f, 1f)).height(6.dp).background(Color(0xFF2F6BFF)))
                        }
                    },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${ui.venue.short} ${ui.symbol}${if (ui.scope == Scope.SYMBOL) " · all venues" else ""}",
                    color = Fm.text, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { picker = true }.padding(vertical = 6.dp),
                )
                IconButton(onClick = { vm.reload() }) { Icon(Icons.Default.Refresh, "Refresh", tint = Fm.text) }
                IconButton(onClick = { picker = true }) { Icon(Icons.Default.Search, "Search symbol", tint = Fm.text) }
                DropdownChip(ui.range.label, TimeRange.entries, { it.label }, vm::setRange)
            }
        }
        ui.error?.let { ErrorBanner(it, onRetry = { vm.reload() }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val h = ui.heatmap
            if (h != null) {
                if (sub == 0) LiqHeatmapChart(h, ui.heatmapKey, palette, threshold, style, haptics)
                else LiqMapBarsChart(h, ui.heatmapKey, haptics)
                Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Say how far back open interest reaches; earlier candles use the volume share.
                    val partialOi = h.usedOpenInterest && h.oiCoverageFrom > h.colTime(0) + h.colDurationMs
                    val oiLabel = "OI ${ui.oiVenues.joinToString("+") { it.short }}" + if (partialOi) " from ${Fmt.axisTime(h.oiCoverageFrom, 100L * 86_400_000L)}" else ""
                    ProvenanceBadge(Provenance.ESTIMATE, extra = if (h.usedOpenInterest) oiLabel else "volume only")
                }
            }
            if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Fm.cyan)
            IconButton(onClick = { onFullscreen(!fullscreen) }, modifier = Modifier.align(Alignment.TopEnd).padding(end = 58.dp)) {
                Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Full screen", tint = Fm.text.copy(alpha = 0.8f))
            }
        }
    }
    if (picker) SymbolPickerSheet(onDismiss = { picker = false }, onPick = { settings.setSymbol(it); picker = false })
}

@Composable
fun PaletteSwatch(p: HeatPalette, modifier: Modifier = Modifier) {
    val colors = remember(p) { listOf(0, 64, 128, 192, 255).map { Color(p.lut[it]) } }
    Box(modifier.size(width = 22.dp, height = 28.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(colors)))
}

private fun fitHeatmap(vp: ChartViewport, h: LiqHeatmap) {
    val first = h.colTime(0).toDouble()
    val last = h.colTime(h.cols - 1).toDouble() + h.colDurationMs
    vp.dataStart = first
    vp.dataEnd = last
    vp.minXSpan = h.colDurationMs * 12.0
    vp.minYSpanFraction = 0.002
    val (lo, hi) = paddedRange(h.candles.minOf { it.l }, h.candles.maxOf { it.h }, 0.12)
    vp.follow = true
    vp.autoY = true
    vp.set(first, last + (last - first) * 0.02, lo, hi)
}

@Composable
private fun LiqHeatmapChart(h: LiqHeatmap, fitKey: String, palette: HeatPalette, threshold: Float, style: ChartStyle, haptics: Boolean) {
    // A new market, venue scope or range gets a fresh viewport (and so a fresh fit); a refresh or a
    // model change of the same market keeps the user's zoom.
    val vp = remember(fitKey) { ChartViewport() }
    val rasterizer = remember { HeatmapRasterizer() }
    LaunchedEffect(h, vp) {
        if (!vp.initialized) fitHeatmap(vp, h) else {
            vp.dataStart = h.colTime(0).toDouble()
            vp.dataEnd = h.colTime(h.cols - 1).toDouble() + h.colDurationMs
        }
    }
    // Price is fitted only on request (first view, double-tap, double-tap on the price axis), never
    // while panning: navigation must not change the scale under the user's finger.
    fun fitVisiblePrice() {
        val cs = h.candles
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var i = cs.lowerBound(vp.xMin.toLong() - h.colDurationMs)
        while (i < cs.size && cs[i].t <= vp.xMax) { lo = minOf(lo, cs[i].l); hi = maxOf(hi, cs[i].h); i++ }
        if (lo < hi) paddedRange(lo, hi, 0.18).let { vp.setY(it.first, it.second) }
        vp.autoY = true
    }
    ChartFrame(
        viewport = vp,
        modifier = Modifier.fillMaxSize(),
        background = Color(palette.background),
        haptics = haptics,
        onReset = { fitHeatmap(vp, h) },
        onAutoFitY = { fitVisiblePrice() },
        tooltipAt = { t, p ->
            val c = h.colAt(t.toLong())
            val r = h.rowAt(p)
            // Only inside the map: no value is reported where nothing is drawn.
            if (c < 0 || c >= h.cols || t >= h.colTime(c) + h.colDurationMs || r < 0 || r >= h.rows) null
            else Tooltip(
                Fmt.dateTime(h.colTime(c)),
                listOf(TipLine("Price:", Fmt.price(p)), TipLine("Liquidation Leverage:", Fmt.compact(h.value(c, r).toDouble()))),
            )
        },
        drawPlot = { s ->
            with(rasterizer) { drawField(h, vp, palette, threshold, smooth = style == ChartStyle.SMOOTH, dataKey = h, gamma = 1.7) }
            drawCandles(vp, h.candles, h.colDurationMs, up = Color(0xFF26C281), down = Color(0xFFE5484D))
            drawLastPrice(vp, h.lastPrice, Fm.yellow)
            // Faint brand watermark, bottom-left.
            with(PixelLogo) { drawLogo(Offset(14.dp.toPx(), s.height - 52.dp.toPx()), 2.4.dp.toPx(), alpha = 0.22f) }
        },
    )
}

/**
 * Liquidation map: estimated liquidation notional by price for each leverage tier (stacked
 * horizontal bars), with cumulative long (below price) and short (above price) curves.
 * Drag vertically to move, pinch to zoom, tap a bar to inspect it.
 *
 * Navigation never changes a number: buckets are fixed groups of the heatmap's price rows and the
 * bar and curve scales come from a fixed band around the current price, so moving the view only
 * moves the picture. Zooming switches between power-of-two bucket sizes; the tooltip always names
 * the exact price range it sums.
 */
@Composable
private fun LiqMapBarsChart(h: LiqHeatmap, viewKey: String, haptics: Boolean) {
    val price = h.lastPrice
    // View state survives refreshes and model changes of the same market (keyed on the request).
    var center by remember(viewKey) { mutableDoubleStateOf(price) }
    var span by remember(viewKey) { mutableDoubleStateOf(price * 0.3) }
    // Bucket size depends only on the zoom level: about 60 buckets per screen height, rounded to a
    // power-of-two number of heatmap rows, so neither scrolling nor a layout change regroups it.
    val rowGroup = LiqMapBars.rowGroupFor(h, span / 60.0)
    val bars = remember(h, rowGroup) { LiqMapBars.from(h, rowGroup) }
    // Scales from a fixed ±25% band around the current price: independent of scrolling (they change
    // only when the zoom level switches bucket size). Bars beyond the scale are marked as clipped.
    val scales = remember(bars) {
        val lo = bars.bucketAt(price * 0.75).coerceIn(0, bars.buckets - 1)
        val hi = bars.bucketAt(price * 1.25).coerceIn(0, bars.buckets - 1)
        val maxTotal = (lo..hi).maxOfOrNull { bars.total(it) }?.takeIf { it > 0 } ?: bars.maxTotal
        val maxCum = (lo..hi).maxOfOrNull { maxOf(bars.cumulativeLong[it], bars.cumulativeShort[it]) }?.takeIf { it > 0 } ?: bars.maxCumulative
        maxTotal to maxCum
    }
    var selectedPrice by remember(viewKey) { mutableStateOf<Double?>(null) }
    val barsNow = androidx.compose.runtime.rememberUpdatedState(bars)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Fm.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val selected = selectedPrice?.let { bars.bucketAt(it) }?.takeIf { it in 0 until bars.buckets }

    Column(Modifier.fillMaxSize().background(Fm.bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 36.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            h.model.tiers.forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Fm.tierColors[i % Fm.tierColors.size]))
                    Spacer(Modifier.width(4.dp))
                    Text("${t.leverage}x", color = Fm.textDim, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Cum. long", color = Fm.sell, fontSize = 12.sp)
            Text("Cum. short", color = Fm.buy, fontSize = 12.sp)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .pointerInput(h) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var moved = 0f
                        // Two fingers pan like one; they zoom only once their vertical spread has
                        // clearly changed (8%), so the wobble of a two-finger drag never rescales.
                        val pinch = com.nazmiefearmutcu.flowmap.mobile.ui.chart.PinchGate()
                        var lastCount = 0
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val (pc, _, _) = com.nazmiefearmutcu.flowmap.mobile.ui.chart.spans(pressed.map { it.previousPosition })
                                val (cc, csx, csy) = com.nazmiefearmutcu.flowmap.mobile.ui.chart.spans(pressed.map { it.position })
                                if (pressed.size != lastCount) pinch.reset(csx, csy)
                                lastCount = pressed.size
                                center += (cc.y - pc.y) / size.height * span
                                val (_, fy) = pinch.factors(csx, csy)
                                if (fy != 1f) span = (span / fy).coerceIn(price * 0.004, price * 1.2)
                                moved += 100f
                            } else {
                                lastCount = 1
                                val dy = pressed[0].positionChange().y
                                moved += kotlin.math.abs(dy)
                                center += dy / size.height * span
                            }
                            ev.changes.forEach { it.consume() }
                        }
                        if (moved < 12f) {
                            val p = center + span / 2 - down.position.y / size.height * span
                            val cur = barsNow.value
                            val b = cur.bucketAt(p)
                            val already = selectedPrice?.let { cur.bucketAt(it) } == b
                            selectedPrice = if (b in 0 until cur.buckets && !already) cur.bucketPrice(b) else null
                        }
                    }
                },
        ) {
            val (maxTotal, maxCum) = scales
            Canvas(Modifier.fillMaxSize()) {
                val axisW = 62.dp.toPx()
                val plotW = size.width - axisW
                fun yOf(p: Double) = ((center + span / 2 - p) / span * size.height).toFloat()
                val b0 = bars.bucketAt(center - span / 2).coerceIn(0, bars.buckets - 1)
                val b1 = bars.bucketAt(center + span / 2).coerceIn(0, bars.buckets - 1)
                for (b in b0..b1) {
                    val yTop = yOf(bars.bucketHigh(b))
                    val bh = yOf(bars.bucketLow(b)) - yTop
                    var x = 0f
                    for (t in bars.tiers.indices) {
                        val v = bars.longs[t][b] + bars.shorts[t][b]
                        if (v <= 0) continue
                        val w = (v / maxTotal * plotW * 0.92).toFloat().coerceAtMost(plotW - x)
                        drawRect(Fm.tierColors[t % Fm.tierColors.size].copy(alpha = if (selected == null || selected == b) 0.9f else 0.35f), Offset(x, yTop + 1f), Size(w, max(1f, bh - 2f)))
                        x += w
                    }
                    // Clipped bar: a white end cap says "larger than the scale"; the tooltip has the value.
                    if (bars.total(b) / maxTotal * 0.92 > 1.0) drawRect(Color.White, Offset(plotW - 4f, yTop + 1f), Size(4f, max(1f, bh - 2f)))
                }
                val lp = Path()
                val sp = Path()
                var ls = false
                var ss = false
                val cumL = bars.cumulativeLong
                val cumS = bars.cumulativeShort
                for (b in (b0 - 1).coerceAtLeast(0)..(b1 + 1).coerceAtMost(bars.buckets - 1)) {
                    val y = yOf(bars.bucketPrice(b))
                    if (cumL[b] > 0) { val x = (cumL[b] / maxCum * plotW).toFloat().coerceAtMost(plotW); if (!ls) { lp.moveTo(x, y); ls = true } else lp.lineTo(x, y) }
                    if (cumS[b] > 0) { val x = (cumS[b] / maxCum * plotW).toFloat().coerceAtMost(plotW); if (!ss) { sp.moveTo(x, y); ss = true } else sp.lineTo(x, y) }
                }
                drawPath(lp, Fm.sell, style = Stroke(2.5f))
                drawPath(sp, Fm.buy, style = Stroke(2.5f))
                // Current price
                val py = yOf(price)
                drawLine(Fm.yellow, Offset(0f, py), Offset(plotW, py), 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                // Price axis
                val step = Fmt.niceStep(span / 7)
                var p = kotlin.math.ceil((center - span / 2) / step) * step
                while (p <= center + span / 2) {
                    val y = yOf(p)
                    val l = measurer.measure(Fmt.axisPrice(p, step), labelStyle)
                    if (y > l.size.height && y < size.height - l.size.height) drawText(l, topLeft = Offset(plotW + 6.dp.toPx(), y - l.size.height / 2))
                    drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, y), Offset(plotW, y))
                    p += step
                }
                val tag = measurer.measure(Fmt.price(price), labelStyle.copy(color = Fm.bg, fontWeight = FontWeight.Bold))
                drawRect(Fm.yellow, Offset(plotW, py - tag.size.height / 2 - 3), Size(axisW, tag.size.height + 6f))
                drawText(tag, topLeft = Offset(plotW + 4.dp.toPx(), py - tag.size.height / 2))
            }
            selected?.let { b ->
                Column(
                    Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 70.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xF0101626))
                        .padding(12.dp),
                ) {
                    Text("${Fmt.price(bars.bucketLow(b))} – ${Fmt.price(bars.bucketHigh(b))}", color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 13.sp, style = Fm.num)
                    bars.tiers.forEachIndexed { t, tier ->
                        val v = bars.longs[t][b] + bars.shorts[t][b]
                        if (v > 0) Text("${tier.leverage}x  ${Fmt.usd(v)}", color = Fm.tierColors[t % Fm.tierColors.size], fontSize = 12.sp, style = Fm.num)
                    }
                    if (bars.cumulativeLong[b] > 0) Text("Cum. long liq.  ${Fmt.usd(bars.cumulativeLong[b])}", color = Fm.sell, fontSize = 12.sp, style = Fm.num)
                    if (bars.cumulativeShort[b] > 0) Text("Cum. short liq.  ${Fmt.usd(bars.cumulativeShort[b])}", color = Fm.buy, fontSize = 12.sp, style = Fm.num)
                }
            }
            Text(
                "Drag to move · pinch to zoom · tap a bar",
                color = Fm.textFaint, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
    }
}
