package com.nazmiefearmutcu.flowmap.mobile.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.Interval
import com.nazmiefearmutcu.flowmap.mobile.data.Provenance
import com.nazmiefearmutcu.flowmap.mobile.data.RatioKind
import com.nazmiefearmutcu.flowmap.mobile.data.RatioPoint
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import com.nazmiefearmutcu.flowmap.mobile.data.userMessage
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Card
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ErrorBanner
import com.nazmiefearmutcu.flowmap.mobile.ui.components.FilterChipRow
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ProvenanceBadge
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SplitBar
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SymbolPickerSheet
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private val periods = listOf(Interval.M5, Interval.M15, Interval.H1, Interval.H4, Interval.D1)

/** Long/short ratios published by the venues (accounts, top traders, taker volume). */
@Composable
fun LongShortScreen() {
    val settings = AppGraph.settings
    val symbol by settings.symbol.collectAsState()
    val venuePref by settings.venue.collectAsState()
    var period by remember { mutableStateOf(2) }
    var data by remember { mutableStateOf<List<Pair<RatioKind, List<RatioPoint>>>>(emptyList()) }
    var venue by remember { mutableStateOf(venuePref) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var picker by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(symbol, venuePref, period, reloadKey) {
        loading = true
        error = null
        try {
            val (v, res) = AppGraph.withVenue(venuePref) { api ->
                coroutineScope {
                    api.ratioKinds.map { k -> async { k to try { api.longShort(symbol, k, periods[period], 120) } catch (_: Exception) { emptyList() } } }.map { it.await() }
                }
            }
            venue = v
            data = res
            if (res.all { it.second.isEmpty() }) error = "No ratio data for $symbol on ${v.short}."
        } catch (e: Exception) {
            error = e.userMessage()
        }
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${venue.short} $symbol", color = Fm.text, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { picker = true }.padding(vertical = 6.dp),
                )
                ProvenanceBadge(Provenance.REST)
            }
            FilterChipRow(periods.map { it.label }, setOf(period), { period = it }, Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(6.dp))
            error?.let { ErrorBanner(it, onRetry = { reloadKey++ }, modifier = Modifier.padding(horizontal = 12.dp)) }
            if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Fm.cyan) }
            if (venue == Venue.BYBIT) Text("Bybit publishes the account ratio only.", color = Fm.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        data.filter { it.second.isNotEmpty() }.forEach { (kind, pts) ->
            item(key = kind.name) { RatioCard(kind, pts) }
        }
    }
    if (picker) SymbolPickerSheet(onDismiss = { picker = false }, onPick = { settings.setSymbol(it); picker = false })
}

@Composable
private fun RatioCard(kind: RatioKind, pts: List<RatioPoint>) {
    var sel by remember(pts) { mutableStateOf<Int?>(null) }
    val p = sel?.let { pts.getOrNull(it) } ?: pts.last()
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column {
            Text(kind.label, color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(kind.detail, color = Fm.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(if (kind == RatioKind.TAKER_VOLUME) "Buy" else "Long", color = Fm.textDim, fontSize = 12.sp)
                    Text(Fmt.pct(p.longPct, 1, false), color = Fm.buy, fontWeight = FontWeight.Bold, fontSize = 20.sp, style = Fm.num)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ratio", color = Fm.textDim, fontSize = 12.sp)
                    Text(Fmt.ratio(p.ratio), color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 20.sp, style = Fm.num)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(if (kind == RatioKind.TAKER_VOLUME) "Sell" else "Short", color = Fm.textDim, fontSize = 12.sp)
                    Text(Fmt.pct(100 - p.longPct, 1, false), color = Fm.sell, fontWeight = FontWeight.Bold, fontSize = 20.sp, style = Fm.num)
                }
            }
            Spacer(Modifier.height(8.dp))
            SplitBar((p.longPct / 100).toFloat(), Fm.buy, Fm.sell)
            Spacer(Modifier.height(10.dp))
            RatioChart(pts, sel, { sel = it }, Modifier.fillMaxWidth().height(130.dp))
            Text(Fmt.dateTime(p.t), color = Fm.textDim, fontSize = 11.sp, style = Fm.num, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Long-share area (green over red) with the ratio line; drag to scrub. */
@Composable
private fun RatioChart(pts: List<RatioPoint>, sel: Int?, onSel: (Int?) -> Unit, modifier: Modifier) {
    fun idx(x: Float, w: Float) = ((x / w) * (pts.size - 1)).toInt().coerceIn(0, pts.size - 1)
    Canvas(
        modifier.clip(RoundedCornerShape(8.dp)).background(Fm.bg)
            .pointerInput(pts) { detectTapGestures { onSel(idx(it.x, size.width.toFloat())) } }
            .pointerInput(pts) { detectDragGestures(onDragEnd = {}) { ch, _ -> onSel(idx(ch.position.x, size.width.toFloat())) } },
    ) {
        if (pts.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val n = pts.size
        val area = Path().apply {
            moveTo(0f, h)
            pts.forEachIndexed { i, p -> lineTo(i / (n - 1f) * w, (h * (1 - p.longPct / 100)).toFloat()) }
            lineTo(w, h); close()
        }
        drawRect(Fm.sell.copy(alpha = 0.35f))
        drawPath(area, Fm.buy.copy(alpha = 0.45f))
        drawLine(Fm.textFaint, Offset(0f, h / 2), Offset(w, h / 2), 1f)
        val rs = pts.map { it.ratio }.filter { !it.isNaN() }
        if (rs.isNotEmpty()) {
            val lo = rs.min()
            val hi = rs.max().let { if (it - lo < 1e-9) lo + 1 else it }
            val line = Path()
            pts.forEachIndexed { i, p ->
                val y = (h - (p.ratio - lo) / (hi - lo) * h * 0.9 - h * 0.05).toFloat()
                if (i == 0) line.moveTo(0f, y) else line.lineTo(i / (n - 1f) * w, y)
            }
            drawPath(line, Fm.yellow, style = Stroke(2f))
        }
        sel?.let { drawLine(Fm.text, Offset(it / (n - 1f) * w, 0f), Offset(it / (n - 1f) * w, h), 1.5f) }
    }
}
