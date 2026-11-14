package com.nazmiefearmutcu.flowmap.mobile.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.FundingPoint
import com.nazmiefearmutcu.flowmap.mobile.data.Interval
import com.nazmiefearmutcu.flowmap.mobile.data.OiPoint
import com.nazmiefearmutcu.flowmap.mobile.data.Provenance
import com.nazmiefearmutcu.flowmap.mobile.data.Ticker
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import com.nazmiefearmutcu.flowmap.mobile.data.exchange.Venues
import com.nazmiefearmutcu.flowmap.mobile.data.userMessage
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Card
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ErrorBanner
import com.nazmiefearmutcu.flowmap.mobile.ui.components.FilterChipRow
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ProvenanceBadge
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Sparkline
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.math.abs

private data class FundingRow(val symbol: String, val binance: Ticker?, val bybit: Ticker?) {
    val maxAbs: Double get() = maxOf(binance?.fundingRate?.takeIf { !it.isNaN() }?.let { abs(it) } ?: 0.0, bybit?.fundingRate?.takeIf { !it.isNaN() }?.let { abs(it) } ?: 0.0)
    val volume: Double get() = (binance?.quoteVolume ?: 0.0) + (bybit?.quoteVolume ?: 0.0)
}

private val sorts = listOf("Volume", "Highest", "Lowest", "Extreme")

/** Funding rates of both venues side by side, plus funding and open-interest history per symbol. */
@Composable
fun FundingScreen() {
    val settings = AppGraph.settings
    val symbol by settings.symbol.collectAsState()
    var rows by remember { mutableStateOf<List<FundingRow>>(emptyList()) }
    var sort by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<Map<Venue, List<FundingPoint>>>(emptyMap()) }
    var oi by remember { mutableStateOf<List<OiPoint>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                coroutineScope {
                    val b = async { try { AppGraph.tickers(Venue.BINANCE) } catch (_: Exception) { emptyList() } }
                    val y = async { try { AppGraph.tickers(Venue.BYBIT) } catch (_: Exception) { emptyList() } }
                    val bm = b.await().associateBy { it.symbol }
                    val ym = y.await().associateBy { it.symbol }
                    rows = (bm.keys + ym.keys).map { FundingRow(it, bm[it], ym[it]) }
                    error = if (bm.isEmpty() && ym.isEmpty()) "Funding rates unavailable." else null
                }
            } catch (e: Exception) {
                error = e.userMessage()
            }
            delay(30_000)
        }
    }
    LaunchedEffect(symbol) {
        history = emptyMap(); oi = emptyList()
        coroutineScope {
            val hb = async { try { Venues.binance.fundingHistory(symbol, 90) } catch (_: Exception) { emptyList() } }
            val hy = async { try { Venues.bybit.fundingHistory(symbol, 90) } catch (_: Exception) { emptyList() } }
            val o = async {
                try {
                    val now = System.currentTimeMillis()
                    AppGraph.withVenue(settings.venue.value) { it.openInterest(symbol, Interval.H1, now - 14 * 86_400_000L, now) }.second
                } catch (_: Exception) { emptyList() }
            }
            history = mapOf(Venue.BINANCE to hb.await(), Venue.BYBIT to hy.await())
            oi = o.await()
        }
    }

    val sorted = when (sort) {
        0 -> rows.sortedByDescending { it.volume }
        1 -> rows.sortedByDescending { maxOf(it.binance?.fundingRate.orNeg(), it.bybit?.fundingRate.orNeg()) }
        2 -> rows.sortedBy { minOf(it.binance?.fundingRate.orPos(), it.bybit?.fundingRate.orPos()) }
        else -> rows.sortedByDescending { it.maxAbs }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$symbol funding", color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        ProvenanceBadge(Provenance.REST)
                    }
                    Text("Per funding interval · last 90 settlements", color = Fm.textDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Venue.entries.forEach { v ->
                        val pts = history[v].orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(v.short, color = Fm.textDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                            if (pts.isEmpty()) Text("—", color = Fm.textFaint, modifier = Modifier.weight(1f))
                            else FundingBars(pts, Modifier.weight(1f).height(48.dp))
                            Text(pts.lastOrNull()?.let { Fmt.funding(it.rate) } ?: "", color = fundingColor(pts.lastOrNull()?.rate ?: 0.0), fontSize = 13.sp, style = Fm.num, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
                        }
                    }
                    if (oi.size > 2) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Open interest · 14 d", color = Fm.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(Fmt.usd(oi.last().usd), color = Fm.cyan, fontWeight = FontWeight.Bold, style = Fm.num)
                        }
                        Sparkline(oi.map { it.usd }, Fm.cyan, Modifier.fillMaxWidth().height(70.dp).padding(top = 6.dp))
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("All perpetuals", color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            }
            FilterChipRow(sorts, setOf(sort), { sort = it }, Modifier.padding(horizontal = 12.dp))
            error?.let { ErrorBanner(it, modifier = Modifier.padding(12.dp)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Symbol", color = Fm.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Binance", color = Fm.textDim, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(92.dp))
                Text("Bybit", color = Fm.textDim, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(92.dp))
            }
            if (rows.isEmpty() && error == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Fm.cyan) }
        }
        items(sorted.take(200), key = { it.symbol }) { r ->
            Column(Modifier.clickable { settings.setSymbol(r.symbol) }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.symbol.removeSuffix("USDT"), color = if (r.symbol == symbol) Fm.cyan else Fm.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    FundingCell(r.binance?.fundingRate)
                    FundingCell(r.bybit?.fundingRate)
                }
                HorizontalDivider(color = Fm.outline.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

private fun Double?.orNeg() = this?.takeIf { !it.isNaN() } ?: -1.0
private fun Double?.orPos() = this?.takeIf { !it.isNaN() } ?: 1.0

private fun fundingColor(r: Double): Color = when {
    r > 0.0001 -> Fm.buy
    r < 0 -> Fm.sell
    else -> Fm.text
}

@Composable
private fun FundingCell(rate: Double?) {
    val v = rate?.takeIf { !it.isNaN() }
    Text(v?.let { Fmt.funding(it) } ?: "—", color = v?.let { fundingColor(it) } ?: Fm.textFaint, fontSize = 13.sp, style = Fm.num, textAlign = TextAlign.End, modifier = Modifier.width(92.dp))
}

@Composable
private fun FundingBars(pts: List<FundingPoint>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(Fm.bg)) {
        val max = pts.maxOf { abs(it.rate) }.takeIf { it > 0 } ?: 1.0
        val mid = size.height / 2
        val w = size.width / pts.size
        drawLine(Fm.outline, Offset(0f, mid), Offset(size.width, mid))
        pts.forEachIndexed { i, p ->
            val h = (abs(p.rate) / max * (mid - 2)).toFloat()
            val c = if (p.rate >= 0) Fm.buy else Fm.sell
            drawRect(c, Offset(i * w, if (p.rate >= 0) mid - h else mid), Size(maxOf(1f, w - 1), h))
        }
    }
}
