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
import androidx.compose.runtime.snapshotFlow
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
    var overlays by rememberSaveable { mutableStateOf(setOf(0, 1, 2)) }
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LiveHeatmap(sess, st, mode, overlays, palette, threshold, style, haptics)
            IconButton(onClick = { onFullscreen(!fullscreen) }, modifier = Modifier.align(Alignment.TopEnd).padding(end = 58.dp)) {
                Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Full screen", tint = Fm.text.copy(alpha = 0.8f))
            }
        }
        if (4 in overlays) CvdPanel(sess, Modifier.fillMaxWidth().height(64.dp))
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
    vp.set(now - 4 * 60_000.0, now + 30_000.0, mid * 0.997, mid * 1.003)
}

@Composable
private fun LiveHeatmap(
    sess: LiveMarketSession,
    st: LiveState,
    mode: DepthMode,
    overlays: Set<Int>,
    palette: HeatPalette,
    threshold: Float,
    style: ChartStyle,
    haptics: Boolean,
) {
    val vp = remember(sess) { ChartViewport() }
    val rasterizer = remember { HeatmapRasterizer() }
    val depth = sess.depth
    depth.mode = mode
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
    // Auto-fit price to visible candles and best bid/offer until the user scales price.
    LaunchedEffect(sess) {
        snapshotFlow { Triple(vp.revision, vp.autoY, st.version) }.collect { (_, auto, _) ->
            if (!auto || !vp.initialized) return@collect
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
    }

    Box(Modifier.fillMaxSize()) {
        ChartFrame(
            viewport = vp,
            modifier = Modifier.fillMaxSize(),
            background = Color(palette.background),
            haptics = haptics,
            onReset = { fitLive(vp, sess, sess.state.value) },
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
                if (depth.cols > 0) {
                    val img = rasterizer.render(depth, vp, s.width.toInt(), s.height.toInt(), palette, threshold, if (style == ChartStyle.PIXEL) 3 else 2, 0)
                    if (img != null) drawImage(img, dstSize = IntSize(s.width.roundToInt(), s.height.roundToInt()), filterQuality = if (style == ChartStyle.PIXEL) FilterQuality.None else FilterQuality.Low)
                }
                if (0 in overlays) drawCandles(vp, st.candles, 60_000L, alpha = 0.85f)
                if (3 in overlays && st.candles.size > 2) {
                    val times = LongArray(st.candles.size) { st.candles[it].t }
                    drawSeries(vp, times, Indicators.vwap(st.candles), Fm.yellow, 2f, 30_000L)
                }
                if (1 in overlays) drawBbo(vp, depth)
                if (2 in overlays && depth.cols > 0) {
                    var sum = 0.0
                    var n = 0
                    for (c in maxOf(0, depth.cols - 120) until depth.cols) { sum += depth.buyUsdAt(c) + depth.sellUsdAt(c); n++ }
                    drawTradeBubbles(vp, depth, (sum / maxOf(1, n)).coerceAtLeast(1.0))
                }
                drawLastPrice(vp, st.lastPrice, Fm.cyan)
                // Marks where on-device recording started.
                if (depth.cols > 0) {
                    val x = vp.xToPx(depth.colTime(0).toDouble())
                    if (x > 0 && x < s.width) drawLine(Fm.cyan.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, s.height), 1.5f)
                }
            },
            overlay = {
                Row(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    if (depth.cols == 0) ProvenanceBadge(Provenance.REST, extra = "candles · depth recording starts now")
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

/** Cumulative volume delta of aggressive trades since recording started. */
@Composable
private fun CvdPanel(sess: LiveMarketSession, modifier: Modifier) {
    val st by sess.state.collectAsState()
    val d = sess.depth
    Box(modifier.background(Fm.surface)) {
        Canvas(Modifier.fillMaxSize().padding(end = 58.dp)) {
            st.version
            val n = d.cols
            if (n < 2) return@Canvas
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (c in 0 until n) { val v = d.cvdAt(c); lo = minOf(lo, v); hi = maxOf(hi, v) }
            if (hi - lo < 1e-9) hi = lo + 1
            val p = Path()
            for (c in 0 until n) {
                val x = c / (n - 1f) * size.width
                val y = (size.height - (d.cvdAt(c) - lo) / (hi - lo) * size.height * 0.85 - size.height * 0.07).toFloat()
                if (c == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, if (d.cvdAt(n - 1) >= d.cvdAt(0)) Fm.buy else Fm.sell, style = Stroke(2f))
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
