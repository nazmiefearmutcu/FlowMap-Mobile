package com.nazmiefearmutcu.flowmap.mobile.ui.screens.home

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.data.FearGreed
import com.nazmiefearmutcu.flowmap.mobile.data.Interval
import com.nazmiefearmutcu.flowmap.mobile.data.Ticker
import com.nazmiefearmutcu.flowmap.mobile.data.exchange.Venues
import com.nazmiefearmutcu.flowmap.mobile.data.news.FearGreedRepository
import com.nazmiefearmutcu.flowmap.mobile.data.userMessage
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Card
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ChangeChip
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ErrorBanner
import com.nazmiefearmutcu.flowmap.mobile.ui.components.LabeledValue
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SectionTitle
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Sparkline
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SplitBar
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private val majors = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")

/** Market pulse: sentiment, majors, breadth, movers and the live liquidation tally. */
@Composable
fun OverviewScreen(onOpenChart: (String) -> Unit) {
    val venuePref by AppGraph.settings.venue.collectAsState()
    var fng by remember { mutableStateOf<List<FearGreed>>(emptyList()) }
    var tickers by remember { mutableStateOf<List<Ticker>>(emptyList()) }
    var sparks by remember { mutableStateOf<Map<String, List<Candle>>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    val liq by AppGraph.liquidations.state.collectAsState()

    LaunchedEffect(Unit) { try { fng = FearGreedRepository.history(60) } catch (_: Exception) { } }
    LaunchedEffect(venuePref) {
        while (true) {
            try {
                tickers = AppGraph.withVenue(venuePref) { AppGraph.tickers(it.venue) }.second
                error = null
            } catch (e: Exception) {
                error = e.userMessage()
            }
            delay(20_000)
        }
    }
    LaunchedEffect(venuePref) {
        val now = System.currentTimeMillis()
        sparks = coroutineScope {
            majors.map { s -> async { s to try { AppGraph.withVenue(venuePref) { it.candles(s, Interval.H1, now - 48 * 3_600_000L, now) }.second } catch (_: Exception) { emptyList() } } }
                .associate { it.await() }
        }
    }

    val bySym = tickers.associateBy { it.symbol }
    val liquid = tickers.filter { it.quoteVolume > 5_000_000 }
    val up = liquid.count { it.changePct > 0 }
    val gainers = liquid.sortedByDescending { it.changePct }.take(5)
    val losers = liquid.sortedBy { it.changePct }.take(5)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        error?.let { item { ErrorBanner(it, modifier = Modifier.padding(12.dp)) } }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(Modifier.weight(1f)) { FearGreedCard(fng) }
                Card(Modifier.weight(1f)) {
                    Column {
                        Text("Market breadth", color = Fm.textDim, fontSize = 12.sp)
                        Text("$up / ${liquid.size}", color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 22.sp, style = Fm.num)
                        Text("perpetuals up in 24 h", color = Fm.textDim, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        SplitBar(if (liquid.isNotEmpty()) up / liquid.size.toFloat() else 0.5f, Fm.buy, Fm.sell)
                        Spacer(Modifier.height(10.dp))
                        LabeledValue("24 h volume", Fmt.usd(tickers.sumOf { it.quoteVolume }))
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                majors.forEach { s ->
                    val t = bySym[s]
                    Card(Modifier.fillMaxWidth().clickable { AppGraph.settings.setSymbol(s); onOpenChart(s) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(110.dp)) {
                                Text(s.removeSuffix("USDT"), color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(t?.let { Fmt.price(it.last) } ?: "—", color = Fm.text, style = Fm.num, fontSize = 15.sp)
                            }
                            Sparkline(sparks[s].orEmpty().map { it.c }, if ((t?.changePct ?: 0.0) >= 0) Fm.buy else Fm.sell, Modifier.weight(1f).height(44.dp).padding(horizontal = 10.dp))
                            ChangeChip(t?.changePct ?: 0.0, Modifier.width(84.dp))
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column {
                    Text("Liquidations since ${Fmt.timeMin(liq.since)}", color = Fm.textDim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        LabeledValue("Total", Fmt.usd(liq.totals.totalUsd), Modifier.weight(1f))
                        LabeledValue("Longs", Fmt.usd(liq.totals.longUsd), Modifier.weight(1f), Fm.sell)
                        LabeledValue("Shorts", Fmt.usd(liq.totals.shortUsd), Modifier.weight(1f), Fm.buy)
                    }
                }
            }
        }
        item { SectionTitle("Top gainers · 24 h") }
        item { MoverList(gainers, onOpenChart) }
        item { SectionTitle("Top losers · 24 h") }
        item { MoverList(losers, onOpenChart) }
    }
}

@Composable
private fun MoverList(list: List<Ticker>, onOpenChart: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        list.forEach { t ->
            Row(
                Modifier.fillMaxWidth().clickable { AppGraph.settings.setSymbol(t.symbol); onOpenChart(t.symbol) }.heightIn(min = 46.dp).padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.base, color = Fm.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(Fmt.price(t.last), color = Fm.text, style = Fm.num, modifier = Modifier.padding(end = 10.dp))
                ChangeChip(t.changePct, Modifier.width(84.dp))
            }
        }
    }
}

@Composable
private fun FearGreedCard(h: List<FearGreed>) {
    val cur = h.lastOrNull()
    Column {
        Text("Fear & Greed", color = Fm.textDim, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.size(width = 120.dp, height = 64.dp)) {
                val stroke = 10.dp.toPx()
                val arcSize = Size(size.width - stroke, (size.width - stroke))
                val tl = Offset(stroke / 2, stroke / 2)
                drawArc(
                    Brush.horizontalGradient(listOf(Fm.sell, Fm.orange, Fm.yellow, Fm.buy)), 180f, 180f, false, tl, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                cur?.let {
                    val a = Math.toRadians(180.0 + it.value / 100.0 * 180.0)
                    val c = Offset(size.width / 2, tl.y + arcSize.height / 2)
                    val r = arcSize.width / 2
                    drawCircle(Color.White, 6.dp.toPx(), Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat()))
                }
            }
            Text(cur?.value?.toString() ?: "—", color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 24.sp, style = Fm.num)
        }
        Text(cur?.label ?: "Loading…", color = fgColor(cur?.value ?: 50), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (h.size > 2) Sparkline(h.map { it.value.toDouble() }, Fm.yellow, Modifier.fillMaxWidth().height(28.dp).padding(top = 6.dp), fill = false)
        Text("alternative.me · daily", color = Fm.textFaint, fontSize = 10.sp)
    }
}

private fun fgColor(v: Int) = when {
    v < 25 -> Fm.sell
    v < 45 -> Fm.orange
    v < 56 -> Fm.yellow
    else -> Fm.buy
}
