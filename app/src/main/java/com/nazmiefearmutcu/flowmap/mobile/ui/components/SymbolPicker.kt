package com.nazmiefearmutcu.flowmap.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.Ticker
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm

/** Bottom sheet listing the venue's USDT perpetuals, watchlist first, searchable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolPickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val settings = AppGraph.settings
    val venue by settings.venue.collectAsState()
    val watch by settings.watchlist.collectAsState()
    var query by remember { mutableStateOf("") }
    var tickers by remember { mutableStateOf<List<Ticker>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(venue) {
        try {
            tickers = AppGraph.withVenue(venue) { AppGraph.tickers(it.venue) }.second.sortedByDescending { it.quoteVolume }
        } catch (e: Exception) {
            error = "Could not load symbols."
        }
    }
    val q = query.trim().uppercase()
    val list = tickers.filter { q.isEmpty() || it.symbol.contains(q) }
        .sortedWith(compareByDescending<Ticker> { it.symbol in watch }.thenByDescending { it.quoteVolume })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Fm.surface) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Select symbol", color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.heightIn(min = 10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search, e.g. BTC") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Fm.cyan, unfocusedBorderColor = Fm.outline),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { ErrorBanner(it, modifier = Modifier.padding(top = 8.dp)) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(top = 8.dp)) {
                items(list, key = { it.symbol }) { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(t.symbol) }.heightIn(min = 52.dp).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IconButton(onClick = { settings.toggleWatch(t.symbol) }) {
                            Icon(if (t.symbol in watch) Icons.Default.Star else Icons.Default.StarBorder, "Watch", tint = if (t.symbol in watch) Fm.yellow else Fm.textFaint)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(t.base, color = Fm.text, fontWeight = FontWeight.SemiBold)
                            Text("Vol ${Fmt.usd(t.quoteVolume)}", color = Fm.textDim, fontSize = 12.sp)
                        }
                        Text(Fmt.price(t.last), color = Fm.text, style = Fm.num)
                        Spacer(Modifier.width(4.dp))
                        ChangeChip(t.changePct, Modifier.width(78.dp))
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
}
