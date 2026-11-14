package com.nazmiefearmutcu.flowmap.mobile.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.Liquidation
import com.nazmiefearmutcu.flowmap.mobile.data.Provenance
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Card
import com.nazmiefearmutcu.flowmap.mobile.ui.components.FilterChipRow
import com.nazmiefearmutcu.flowmap.mobile.ui.components.LabeledValue
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ProvenanceBadge
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SectionTitle
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SplitBar
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import kotlinx.coroutines.delay

private val minSizes = listOf(0.0, 10_000.0, 100_000.0, 1_000_000.0)
private val minLabels = listOf("All sizes", "≥ $10K", "≥ $100K", "≥ $1M")

/** Live forced liquidations across Binance and Bybit, since the feed started. */
@Composable
fun LiqDataScreen() {
    val st by AppGraph.liquidations.state.collectAsState()
    var minIdx by remember { mutableStateOf(0) }
    var symbolFilter by remember { mutableStateOf(0) } // 0 all, 1 BTC, 2 ETH, 3 SOL
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val symbols = listOf(null, "BTCUSDT", "ETHUSDT", "SOLUSDT")
    val list = st.recent.filter { it.usd >= minSizes[minIdx] && (symbols[symbolFilter] == null || it.symbol == symbols[symbolFilter]) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Liquidations", color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        ProvenanceBadge(Provenance.LIVE, extra = Venue.entries.joinToString(" ") { v -> v.short + if (st.connected[v] == true) " ●" else " ○" })
                    }
                    Text("Since ${Fmt.timeMin(st.since)} (${Fmt.ago(st.since, now).removeSuffix(" ago")})" + if (st.hadPause) " · excludes time in background" else "", color = Fm.textDim, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        LabeledValue("Total", Fmt.usd(st.totals.totalUsd), Modifier.weight(1f))
                        LabeledValue("Longs", Fmt.usd(st.totals.longUsd), Modifier.weight(1f), valueColor = Fm.sell)
                        LabeledValue("Shorts", Fmt.usd(st.totals.shortUsd), Modifier.weight(1f), valueColor = Fm.buy)
                    }
                    Spacer(Modifier.height(10.dp))
                    val share = if (st.totals.totalUsd > 0) (st.totals.longUsd / st.totals.totalUsd).toFloat() else 0.5f
                    SplitBar(share, Fm.sell, Fm.buy)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("Longs ${Fmt.pct(share * 100.0, 1, false)}", color = Fm.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("Shorts ${Fmt.pct((1 - share) * 100.0, 1, false)}", color = Fm.textDim, fontSize = 12.sp)
                    }
                    st.totals.largest?.let { l ->
                        Spacer(Modifier.height(8.dp))
                        Text("Largest: ${l.symbol} ${if (l.longLiquidated) "long" else "short"} ${Fmt.usd(l.usd)} on ${l.venue.short}", color = Fm.yellow, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column {
                    Text("Last 60 minutes", color = Fm.text, fontWeight = FontWeight.SemiBold)
                    Text("Shorts liquidated above, longs below", color = Fm.textDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    MinuteBars(st.minuteLong, st.minuteShort, Modifier.fillMaxWidth().height(120.dp))
                }
            }
        }
        if (st.bySymbol.isNotEmpty()) {
            item { SectionTitle("By symbol") }
            items(st.bySymbol.take(8), key = { "s" + it.first }) { (sym, t) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sym.removeSuffix("USDT"), color = Fm.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp))
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        SplitBar(if (t.totalUsd > 0) (t.longUsd / t.totalUsd).toFloat() else 0.5f, Fm.sell, Fm.buy, height = 6.dp)
                    }
                    Text(Fmt.usd(t.totalUsd), color = Fm.text, style = Fm.num, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                }
            }
        }
        item {
            SectionTitle("Live feed")
            FilterChipRow(listOf("All", "BTC", "ETH", "SOL"), setOf(symbolFilter), { symbolFilter = it }, Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(8.dp))
            FilterChipRow(minLabels, setOf(minIdx), { minIdx = it }, Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(4.dp))
        }
        if (list.isEmpty()) item {
            Text(
                "Waiting for liquidations… Venues publish them live only, so this list starts empty and fills as positions are closed.",
                color = Fm.textDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
            )
        }
        items(list.take(150)) { l -> LiqRow(l, now) }
    }
}

@Composable
private fun LiqRow(l: Liquidation, now: Long) {
    val c = if (l.longLiquidated) Fm.sell else Fm.buy
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${l.symbol.removeSuffix("USDT")} ${if (l.longLiquidated) "Long" else "Short"}", color = Fm.text, fontWeight = FontWeight.SemiBold)
                Text("${l.venue.short} · @ ${Fmt.price(l.price)} · ${Fmt.ago(l.time, now)}", color = Fm.textDim, fontSize = 12.sp, style = Fm.num)
            }
            Text(Fmt.usd(l.usd), color = c, fontWeight = FontWeight.Bold, style = Fm.num, fontSize = if (l.usd >= 100_000) 17.sp else 14.sp)
        }
        HorizontalDivider(color = Fm.outline.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun MinuteBars(longs: DoubleArray, shorts: DoubleArray, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Fm.bg)) {
        val n = longs.size
        val max = maxOf(longs.maxOrNull() ?: 0.0, shorts.maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0
        val mid = size.height / 2
        val w = size.width / n
        drawLine(Fm.outline, Offset(0f, mid), Offset(size.width, mid))
        for (i in 0 until n) {
            val hs = (shorts[i] / max * (mid - 4)).toFloat()
            val hl = (longs[i] / max * (mid - 4)).toFloat()
            if (hs > 0) drawRect(Fm.buy, Offset(i * w + 1, mid - hs), Size(w - 2, hs))
            if (hl > 0) drawRect(Fm.sell, Offset(i * w + 1, mid), Size(w - 2, hl))
        }
    }
}
