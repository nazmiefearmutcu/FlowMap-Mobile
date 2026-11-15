package com.nazmiefearmutcu.flowmap.mobile.ui.screens.markets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.Ticker
import com.nazmiefearmutcu.flowmap.mobile.data.userMessage
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ChangeChip
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ErrorBanner
import com.nazmiefearmutcu.flowmap.mobile.ui.components.FilterChipRow
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Segmented
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import kotlinx.coroutines.delay
import kotlin.math.abs

private val sortLabels = listOf("Volume", "Gainers", "Losers", "Funding", "Price")

/**
 * Markets (showMe-style monitor): heat tiles of the most traded perpetuals, then a sortable,
 * searchable table with a watchlist. Tap opens the live chart; long-press opens the Liq Map.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarketsScreen(onOpenChart: (String) -> Unit, onOpenLiqMap: (String) -> Unit) {
    val settings = AppGraph.settings
    val venue by settings.venue.collectAsState()
    val watch by settings.watchlist.collectAsState()
    var tickers by androidx.compose.runtime.remember { mutableStateOf<List<Ticker>>(emptyList()) }
    var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(0) }
    var scope by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(venue) {
        while (true) {
            AppGraph.awaitForeground()
            try {
                tickers = AppGraph.withVenue(venue) { AppGraph.tickers(it.venue) }.second
                error = null
            } catch (e: Exception) {
                error = e.userMessage()
            }
            delay(15_000)
        }
    }
    val q = query.trim().uppercase()
    val base = tickers.filter { (scope == 0 || it.symbol in watch) && (q.isEmpty() || it.symbol.contains(q)) }
    val list = when (sort) {
        0 -> base.sortedByDescending { it.quoteVolume }
        1 -> base.sortedByDescending { it.changePct }
        2 -> base.sortedBy { it.changePct }
        3 -> base.sortedByDescending { if (it.fundingRate.isNaN()) -1.0 else abs(it.fundingRate) }
        else -> base.sortedByDescending { it.last }
    }
    val tiles = tickers.sortedByDescending { it.quoteVolume }.take(12)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Text("Markets", color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            Text("${venue.label} perpetuals · 24 h", color = Fm.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            error?.let { ErrorBanner(it, modifier = Modifier.padding(12.dp)) }
        }
        if (tiles.isNotEmpty()) item {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tiles.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { t -> HeatTile(t, Modifier.weight(1f), { onOpenChart(t.symbol) }, { onOpenLiqMap(t.symbol) }) }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search symbol") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Fm.cyan, unfocusedBorderColor = Fm.outline),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Segmented(listOf("All", "Watchlist (${watch.size})"), scope, { scope = it }, Modifier.fillMaxWidth().padding(12.dp))
            FilterChipRow(sortLabels, setOf(sort), { sort = it }, Modifier.padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
                Text("Symbol / Vol", color = Fm.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Price / Funding", color = Fm.textDim, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(120.dp))
                Text("24 h", color = Fm.textDim, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(88.dp))
            }
            if (tickers.isEmpty() && error == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Fm.cyan) }
            if (scope == 1 && list.isEmpty()) Text("Star symbols to build your watchlist.", color = Fm.textDim, modifier = Modifier.padding(16.dp))
        }
        items(list.take(300), key = { it.symbol }) { t ->
            Column(Modifier.combinedClickable(onClick = { onOpenChart(t.symbol) }, onLongClick = { onOpenLiqMap(t.symbol) })) {
                Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { settings.toggleWatch(t.symbol) }) {
                        Icon(if (t.symbol in watch) Icons.Default.Star else Icons.Default.StarBorder, "Watch", tint = if (t.symbol in watch) Fm.yellow else Fm.textFaint)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t.base, color = Fm.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(Fmt.usd(t.quoteVolume), color = Fm.textDim, fontSize = 12.sp, style = Fm.num)
                    }
                    Column(Modifier.width(120.dp), horizontalAlignment = Alignment.End) {
                        Text(Fmt.price(t.last), color = Fm.text, fontSize = 15.sp, style = Fm.num)
                        Text(if (t.fundingRate.isNaN()) "—" else Fmt.funding(t.fundingRate), color = if (t.fundingRate < 0) Fm.sell else Fm.textDim, fontSize = 12.sp, style = Fm.num)
                    }
                    Spacer(Modifier.width(8.dp))
                    ChangeChip(t.changePct, Modifier.width(80.dp))
                }
                HorizontalDivider(color = Fm.outline.copy(alpha = 0.35f), modifier = Modifier.padding(start = 56.dp, end = 16.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeatTile(t: Ticker, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    val k = (abs(t.changePct) / 8.0).coerceIn(0.12, 1.0).toFloat()
    val c = if (t.changePct >= 0) Fm.buy else Fm.sell
    Box(
        modifier.aspectRatio(1.25f).clip(RoundedCornerShape(10.dp)).background(Color.Black).background(c.copy(alpha = 0.18f + 0.6f * k))
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(8.dp),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t.base, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Text(Fmt.pct(t.changePct, 1), color = Color.White, fontSize = 12.sp, style = Fm.num)
        }
    }
}
