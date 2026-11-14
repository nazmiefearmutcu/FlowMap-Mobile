package com.nazmiefearmutcu.flowmap.mobile.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import com.nazmiefearmutcu.flowmap.mobile.ui.brand.PixelLogoIcon
import com.nazmiefearmutcu.flowmap.mobile.ui.components.DropdownChip
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ErrorBanner
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SymbolPickerSheet
import com.nazmiefearmutcu.flowmap.mobile.ui.components.TopTabs
import com.nazmiefearmutcu.flowmap.mobile.ui.screens.liq.LiqMapScreen
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm

private val homeTabs = listOf("Overview", "Liq Map", "Liq Data", "Longs/Shorts", "Funding")

/** Home: the analytics hub with top tabs, laid out like the reference screen. */
@Composable
fun HomeScreen(tab: Int, onTab: (Int) -> Unit, onOpenChart: (String) -> Unit, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    var picker by rememberSaveable { mutableStateOf(false) }
    val venue by AppGraph.settings.venue.collectAsState()
    val blocked by AppGraph.blocked.collectAsState()

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelLogoIcon(36.dp)
                Spacer(Modifier.width(12.dp))
                Row(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(50)).background(Fm.surfaceHigh).clickable { picker = true }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = Fm.text)
                    Spacer(Modifier.width(10.dp))
                    Text("Search Assets", color = Fm.textDim, fontSize = 16.sp, textDecoration = TextDecoration.Underline)
                }
                Spacer(Modifier.width(8.dp))
                DropdownChip(venue.short, Venue.entries, { it.label }, AppGraph.settings::setVenue)
            }
            TopTabs(homeTabs, tab, onTab, Modifier.padding(top = 4.dp, bottom = 6.dp))
            if (blocked.isNotEmpty()) {
                ErrorBanner(
                    "${blocked.joinToString { it.short }} is not available in your region; using ${blocked.first().other.short} instead.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> OverviewScreen(onOpenChart)
                1 -> LiqMapScreen(fullscreen, onFullscreen)
                2 -> LiqDataScreen()
                3 -> LongShortScreen()
                else -> FundingScreen()
            }
        }
    }
    if (picker) SymbolPickerSheet(onDismiss = { picker = false }, onPick = { AppGraph.settings.setSymbol(it); picker = false })
}
