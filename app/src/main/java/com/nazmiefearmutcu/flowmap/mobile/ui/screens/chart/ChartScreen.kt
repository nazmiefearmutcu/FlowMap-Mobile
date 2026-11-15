package com.nazmiefearmutcu.flowmap.mobile.ui.screens.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.ChartStyle
import com.nazmiefearmutcu.flowmap.mobile.data.Provenance
import com.nazmiefearmutcu.flowmap.mobile.data.live.LiveMarketSession
import com.nazmiefearmutcu.flowmap.mobile.data.live.LiveState
import com.nazmiefearmutcu.flowmap.mobile.engine.DepthMode
import com.nazmiefearmutcu.flowmap.mobile.engine.HeatPalette
import com.nazmiefearmutcu.flowmap.mobile.engine.Indicators
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.ChartFrame
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.ChartViewport
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.HeatmapRasterizer
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.TipLine
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.Tooltip
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.drawBbo
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.drawCandles
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.drawLastPrice
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.drawPriceLine
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.drawSeries
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.drawTradeBubbles
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.lowerBound
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.paddedRange
import com.nazmiefearmutcu.flowmap.mobile.ui.components.DropdownChip
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ErrorBanner
import com.nazmiefearmutcu.flowmap.mobile.ui.components.FilterChipRow
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ProvenanceBadge
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Segmented
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SymbolPickerSheet
import com.nazmiefearmutcu.flowmap.mobile.ui.screens.liq.PaletteSwatch
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import kotlin.math.roundToInt

private val overlayNames = listOf("Candles", "BBO", "Trades", "VWAP", "CVD")

/** FlowMap's live liquidity heatmap: order book over time, BBO, trade bubbles, DOM and tape. */
@Composable
fun ChartScreen(fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    val vm: LiveChartViewModel = viewModel { LiveChartViewModel() }
    val session by vm.session.collectAsState()
    val settings = AppGraph.settings
    val palette by settings.palette.collectAsState()
    val style by settings.style.collectAsState()
    val haptics by settings.haptics.collectAsState()
    val keepOn by settings.keepScreenOn.collectAsState()
    var overlays by rememberSaveable { mutableStateOf(setOf(1, 2)) }
    var mode by rememberSaveable { mutableStateOf(DepthMode.TOTAL) }
    var panel by rememberSaveable { mutableStateOf(0) }
    var threshold by rememberSaveable { mutableStateOf(0.6f) }
    var picker by remember { mutableStateOf(false) }

    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    val sess = session ?: return
    val st by sess.state.collectAsState()
    // One viewport per market, shared by the heatmap and the CVD panel so both show the same time.
    val vp = remember(sess) { ChartViewport() }

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            ChartHeader(st, onSymbol = { picker = true })
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                DropdownChip(mode.label, DepthMode.entries, { it.label }, { mode = it })
                FilterChipRow(overlayNames, overlays, { i -> overlays = if (i in overlays) overlays - i else overlays + i }, scrollable = false)
                DropdownChip(style.label, ChartStyle.entries, { it.label }, settings::setStyle)
                DropdownChip(palette.label, HeatPalette.entries, { it.label }, settings::setPalette, leading = { PaletteSwatch(palette); Spacer(Modifier.width(8.dp)) })
                DropdownChip("Contrast ${(threshold * 100).roundToInt()}%", listOf(0.25f, 0.4f, 0.6f, 0.8f, 1f), { "${(it * 100).roundToInt()}%" }, { threshold = it })
            }
        }
        st.message?.takeIf { !st.synced && it != "Paused" }?.let { ErrorBanner(it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
        st.notice?.let { ErrorBanner(it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LiveHeatmap(sess, vp, st, mode, overlays, palette, threshold, style, haptics)
            IconButton(onClick = { onFullscreen(!fullscreen) }, modifier = Modifier.align(Alignment.TopEnd).padding(end = 58.dp)) {
                Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Full screen", tint = Fm.text.copy(alpha = 0.8f))
            }
        }
        if (4 in overlays) CvdPanel(sess, vp, Modifier.fillMaxWidth().height(64.dp))
        if (!fullscreen) {
            Segmented(listOf("DOM", "Tape", "Hide"), panel, { panel = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
            when (panel) {
                0 -> DomLadder(st, Modifier.fillMaxWidth().height(268.dp))
                1 -> Tape(st, Modifier.fillMaxWidth().height(268.dp))
                else -> Unit
            }
        }
    }
    if (picker) SymbolPickerSheet(onDismiss = { picker = false }, onPick = { settings.setSymbol(it); picker = false })
}

@Composable
private fun ChartHeader(st: LiveState, onSymbol: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onSymbol)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.symbol, color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (st.connected && st.synced) Fm.green else if (st.connected) Fm.yellow else Fm.red))
            }
            Text(st.venue.label, color = Fm.textDim, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.price(st.lastPrice), color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 22.sp, style = Fm.num)
            val spread = st.bestAsk - st.bestBid
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (spread.isNaN()) "" else "Spread ${Fmt.price(spread)}  ", color = Fm.textDim, fontSize = 12.sp, style = Fm.num)
                ProvenanceBadge(if (st.synced) Provenance.LIVE_L2 else Provenance.REST)
            }
        }
    }
}

private fun fitLive(vp: ChartViewport, sess: LiveMarketSession, st: LiveState) {
    val now = System.currentTimeMillis().toDouble()
    vp.dataStart = st.candles.firstOrNull()?.t?.toDouble() ?: (now - 3_600_000)
    vp.dataEnd = now
    vp.minXSpan = 20_000.0
    vp.minYSpanFraction = 0.0003
    vp.follow = true
    vp.autoY = true
    val mid = if (!st.lastPrice.isNaN()) st.lastPrice else st.candles.lastOrNull()?.c ?: return
    // Open on the last hour: reconstructed history on the left, live recording at the right edge.
    vp.set(now - 60 * 60_000.0, now + 90_000.0, mid * 0.99, mid * 1.01)
}

@Composable
private fun LiveHeatmap(
    sess: LiveMarketSession,
    vp: ChartViewport,
    st: LiveState,
    mode: DepthMode,
    overlays: Set<Int>,
    palette: HeatPalette,
    threshold: Float,
    style: ChartStyle,
    haptics: Boolean,
) {
    val rasterizer = remember { HeatmapRasterizer() }
    val historyRasterizer = remember { HeatmapRasterizer() }
    val depth = sess.depth
    depth.mode = mode
    val history by sess.backfill.collectAsState()
    history?.mode = mode
    val hasPrice = !st.lastPrice.isNaN() || st.candles.isNotEmpty()

    LaunchedEffect(sess, hasPrice) { if (hasPrice && !vp.initialized) fitLive(vp, sess, st) }
    // Advance the live edge on every recorded column.
    LaunchedEffect(st.version, st.candles.size) {
        if (!vp.initialized) return@LaunchedEffect
        val now = System.currentTimeMillis().toDouble()
        vp.dataEnd = now
        st.candles.firstOrNull()?.let { vp.dataStart = it.t.toDouble() }
        vp.followTo(now)
    }
    // Price is fitted on request (first view, double-tap, double-tap on the price axis) and when
    // new data arrives while following the live edge — never in response to panning, so
    // navigation cannot change the scale. State is read fresh from the session, never captured.
    fun fitY(force: Boolean = false) {
        if (!vp.initialized || (!force && !vp.autoY)) return
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        val cs = sess.state.value.candles
        var i = cs.lowerBound(vp.xMin.toLong() - 60_000)
        while (i < cs.size && cs[i].t <= vp.xMax) { lo = minOf(lo, cs[i].l); hi = maxOf(hi, cs[i].h); i++ }
        val c0 = maxOf(0, depth.colAt(vp.xMin.toLong()))
        val c1 = depth.colAt(vp.xMax.toLong())
        var c = c0
        while (c in 0..c1) {
            val b = depth.bestBidAt(c); val a = depth.bestAskAt(c)
            if (!b.isNaN()) { lo = minOf(lo, b); hi = maxOf(hi, a) }
            c += 1
        }
        if (lo < hi) paddedRange(lo, hi, 0.35).let { vp.setY(it.first, it.second) }
    }
    LaunchedEffect(st.version) { if (vp.follow) fitY() }
    val vwap = remember(st.candles) {
        if (st.candles.size > 2) LongArray(st.candles.size) { st.candles[it].t } to Indicators.vwap(st.candles) else null
    }

    Box(Modifier.fillMaxSize()) {
        ChartFrame(
            viewport = vp,
            modifier = Modifier.fillMaxSize(),
            background = Color(palette.background),
            haptics = haptics,
            onReset = { fitLive(vp, sess, sess.state.value); fitY(force = true) },
            onAutoFitY = { vp.autoY = true; fitY(force = true) },
            tooltipAt = { t, p ->
                val lines = ArrayList<TipLine>()
                val c = depth.colAt(t.toLong())
                val inDepth = c >= 0 && t < depth.colTime(c) + depth.colDurationMs
                if (inDepth) {
                    val r = depth.rowAt(p)
                    if (r in 0 until depth.rows) {
                        lines += TipLine("Bids:", Fmt.usd(depth.bidAt(c, r).toDouble()), Fm.buy)
                        lines += TipLine("Asks:", Fmt.usd(depth.askAt(c, r).toDouble()), Fm.sell)
                    }
                    val bu = depth.buyUsdAt(c); val su = depth.sellUsdAt(c)
                    if (bu > 0 || su > 0) lines += TipLine("Trades:", "${Fmt.usd(bu)} / ${Fmt.usd(su)}")
                }
                val bf = history
                if (!inDepth && bf != null && t < bf.until) {
                    val bc = bf.colAt(t.toLong())
                    val br = bf.rowAt(p)
                    if (bc >= 0 && br in 0 until bf.rows) {
                        lines += TipLine("Volume memory:", Fmt.usd(bf.value(bc, br).toDouble()), Fm.yellow)
                        lines += TipLine("Source:", "1-min candles, 2 h fade")
                    }
                }
                val cs = st.candles
                val k = cs.lowerBound(t.toLong() - 59_999)
                if (k < cs.size && cs[k].t <= t) {
                    val cd = cs[k]
                    lines += TipLine("O / C:", "${Fmt.price(cd.o)} / ${Fmt.price(cd.c)}")
                    lines += TipLine("H / L:", "${Fmt.price(cd.h)} / ${Fmt.price(cd.l)}")
                }
                Tooltip(if (inDepth) Fmt.timeSec(t.toLong()) else Fmt.dateTime(t.toLong()), listOf(TipLine("Price:", Fmt.price(p))) + lines)
            },
            drawPlot = { s ->
                st.version // redraw on every new column
                val smooth = style == ChartStyle.SMOOTH
                history?.let { bf -> with(historyRasterizer) { drawField(bf, vp, palette, threshold, smooth, dataKey = bf to mode) } }
                if (depth.cols > 0) with(rasterizer) {
                    drawField(depth, vp, palette, threshold, smooth, dataKey = st.version to mode)
                }
                // Candles when chosen; otherwise a price line over the heatmap (FlowMap's heat line).
                if (0 in overlays) drawCandles(vp, st.candles, 60_000L, alpha = 0.85f)
                else drawPriceLine(vp, st.candles, if (depth.cols > 0) depth.colTime(0) else Long.MAX_VALUE)
                if (3 in overlays && vwap != null) drawSeries(vp, vwap.first, vwap.second, Fm.yellow, 2f, 30_000L)
                if (1 in overlays) drawBbo(vp, depth)
                // Bubble sizes use a reference learnt once, so recorded bubbles never resize.
                if (2 in overlays && depth.tradeRefUsd > 0) drawTradeBubbles(vp, depth, depth.tradeRefUsd)
                drawLastPrice(vp, st.lastPrice, Fm.cyan)
                // Marks where on-device recording started.
                if (depth.cols > 0) {
                    val x = vp.xToPx(depth.colTime(0).toDouble())
                    if (x > 0 && x < s.width) drawLine(Fm.cyan.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, s.height), 1.5f)
                }
            },
            overlay = {
                Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (history != null) ProvenanceBadge(Provenance.ESTIMATE, extra = "history from 1-min candles")
                    if (depth.cols == 0) ProvenanceBadge(Provenance.REST, extra = "order-book recording starts now")
                    else ProvenanceBadge(Provenance.LIVE_L2, extra = "recorded ${Fmt.ago(depth.colTime(0)).removeSuffix(" ago")}")
                }
                if (!vp.follow) {
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(50)).background(Fm.cyan)
                            .clickable { vp.follow = true; vp.followTo(System.currentTimeMillis().toDouble()) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("LIVE", color = Fm.bg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Fm.bg, modifier = Modifier.size(16.dp))
                    }
                }
            },
        )
    }
}

/**
 * Cumulative volume delta of aggressive trades since recording started, on the chart's own time
 * axis. Its vertical range spans the whole recording, so navigating never rescales it.
 */
@Composable
private fun CvdPanel(sess: LiveMarketSession, vp: ChartViewport, modifier: Modifier) {
    val st by sess.state.collectAsState()
    val d = sess.depth
    Box(modifier.background(Fm.surface)) {
        Canvas(Modifier.fillMaxSize().padding(end = 58.dp)) {
            st.version
            vp.revision
            val n = d.cols
            if (n < 2) return@Canvas
            val lo = d.cvdMin
            val hi = if (d.cvdMax - lo < 1e-9) lo + 1 else d.cvdMax
            val sx = size.width / vp.plotW
            val c0 = maxOf(0, d.colAt(vp.xMin.toLong()))
            val c1 = minOf(n - 1, maxOf(c0, d.colAt(vp.xMax.toLong()) + 1))
            val p = Path()
            var started = false
            for (c in c0..c1) {
                // Break the line across recording gaps instead of bridging them.
                if (c > c0 && d.isGap(d.colTime(c - 1), d.colTime(c))) started = false
                val x = vp.xToPx(d.colTime(c) + d.colDurationMs.toDouble()) * sx
                val y = (size.height - (d.cvdAt(c) - lo) / (hi - lo) * size.height * 0.85 - size.height * 0.07).toFloat()
                if (!started) { p.moveTo(x, y); started = true } else p.lineTo(x, y)
            }
            val zero = (size.height - (0 - lo) / (hi - lo) * size.height * 0.85 - size.height * 0.07).toFloat()
            drawLine(Fm.outline, Offset(0f, zero), Offset(size.width, zero), 1f)
            drawPath(p, if (d.cvdAt(n - 1) >= 0) Fm.buy else Fm.sell, style = Stroke(2f))
        }
        Text("CVD ${if (d.cols > 0) Fmt.usd(d.cvdAt(d.cols - 1)) else ""}", color = Fm.textDim, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
    }
}

@Composable
private fun DomLadder(st: LiveState, modifier: Modifier) {
    val asks = st.asks.take(5).reversed()
    val bids = st.bids.take(5)
    val maxQ = (asks + bids).maxOfOrNull { it.second } ?: 1.0
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Text("Price", color = Fm.textDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Size", color = Fm.textDim, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(90.dp))
            Text("Value", color = Fm.textDim, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
        }
        // Fixed ladder (no scrolling) so the spread always stays in the middle.
        asks.forEach { (p, q) -> DomRow(p, q, maxQ, Fm.sell) }
        val spread = st.bestAsk - st.bestBid
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.price(st.lastPrice), color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, style = Fm.num, modifier = Modifier.weight(1f))
            Text(if (spread.isNaN()) "" else "spread ${Fmt.price(spread)} (${Fmt.pct(spread / st.bestBid * 10_000, 2, false).removeSuffix("%")} bp)", color = Fm.textDim, fontSize = 11.sp)
        }
        bids.forEach { (p, q) -> DomRow(p, q, maxQ, Fm.buy) }
    }
}

@Composable
private fun DomRow(price: Double, qty: Double, maxQ: Double, color: Color) {
    Box(Modifier.fillMaxWidth().height(22.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = (qty / maxQ * size.width).toFloat()
            drawRect(color.copy(alpha = 0.18f), Offset(size.width - w, 1f), Size(w, size.height - 2f))
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.price(price), color = color, fontSize = 13.sp, style = Fm.num, modifier = Modifier.weight(1f))
            Text(Fmt.compact(qty, 3), color = Fm.text, fontSize = 13.sp, style = Fm.num, textAlign = TextAlign.End, modifier = Modifier.width(90.dp))
            Text(Fmt.usd(price * qty), color = Fm.textDim, fontSize = 12.sp, style = Fm.num, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
        }
    }
}

@Composable
private fun Tape(st: LiveState, modifier: Modifier) {
    val big = remember(st.trades) { st.trades.map { it.usd }.sorted().let { if (it.isEmpty()) 0.0 else it[(it.size * 0.9).toInt().coerceAtMost(it.size - 1)] } }
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Text("Time", color = Fm.textDim, fontSize = 11.sp, modifier = Modifier.width(76.dp))
            Text("Price", color = Fm.textDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Value", color = Fm.textDim, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(90.dp))
        }
        if (st.trades.isEmpty()) Text("Waiting for trades…", color = Fm.textDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(st.trades) { t ->
                val c = if (t.buyerIsTaker) Fm.buy else Fm.sell
                Row(Modifier.fillMaxWidth().heightIn(min = 22.dp).background(if (t.usd >= big && big > 0) c.copy(alpha = 0.14f) else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.timeSec(t.t), color = Fm.textDim, fontSize = 12.sp, style = Fm.num, modifier = Modifier.width(76.dp))
                    Text(Fmt.price(t.price), color = c, fontSize = 13.sp, style = Fm.num, modifier = Modifier.weight(1f))
                    Text(Fmt.usd(t.usd), color = Fm.text, fontSize = 13.sp, style = Fm.num, fontWeight = if (t.usd >= big) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.End, modifier = Modifier.width(90.dp))
                }
            }
        }
    }
}
