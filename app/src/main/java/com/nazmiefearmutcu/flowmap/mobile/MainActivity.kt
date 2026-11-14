package com.nazmiefearmutcu.flowmap.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.ui.brand.PixelLogoIcon
import com.nazmiefearmutcu.flowmap.mobile.ui.brand.PixelWordmark
import com.nazmiefearmutcu.flowmap.mobile.ui.screens.chart.ChartScreen
import com.nazmiefearmutcu.flowmap.mobile.ui.screens.home.HomeScreen
import com.nazmiefearmutcu.flowmap.mobile.ui.screens.markets.MarketsScreen
import com.nazmiefearmutcu.flowmap.mobile.ui.screens.me.MeScreen
import com.nazmiefearmutcu.flowmap.mobile.ui.screens.news.NewsScreen
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.FlowMapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Fm.bg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Fm.surface.toArgb()),
        )
        setContent { FlowMapTheme { FlowMapRoot() } }
    }

    override fun onStart() {
        super.onStart()
        AppGraph.setForeground(true)
    }

    override fun onStop() {
        AppGraph.setForeground(false)
        super.onStop()
    }
}

private enum class Dest(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    MARKETS("Markets", Icons.Default.BarChart),
    CHART("Chart", Icons.Default.ShowChart),
    NEWS("News", Icons.AutoMirrored.Filled.Article),
    ME("Me", Icons.Default.Person),
}

@Composable
private fun FlowMapRoot() {
    var dest by rememberSaveable { mutableStateOf(Dest.HOME) }
    var homeTab by rememberSaveable { mutableIntStateOf(1) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val accepted by AppGraph.settings.disclaimerAccepted.collectAsState()

    BackHandler(enabled = fullscreen || dest != Dest.HOME) {
        if (fullscreen) fullscreen = false else dest = Dest.HOME
    }
    val openChart: (String) -> Unit = { s -> AppGraph.settings.setSymbol(s); dest = Dest.CHART }
    val openLiqMap: (String) -> Unit = { s -> AppGraph.settings.setSymbol(s); homeTab = 1; dest = Dest.HOME }

    Column(Modifier.fillMaxSize().background(Fm.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            when (dest) {
                Dest.HOME -> HomeScreen(homeTab, { homeTab = it }, openChart, fullscreen) { fullscreen = it }
                Dest.MARKETS -> MarketsScreen(openChart, openLiqMap)
                Dest.CHART -> ChartScreen(fullscreen) { fullscreen = it }
                Dest.NEWS -> NewsScreen()
                Dest.ME -> MeScreen()
            }
        }
        if (!fullscreen) BottomBar(dest) { dest = it; fullscreen = false }
        else Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
    if (!accepted) DisclaimerDialog { AppGraph.settings.acceptDisclaimer() }
}

@Composable
private fun BottomBar(current: Dest, onSelect: (Dest) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Fm.surface).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dest.entries.forEach { d ->
            val sel = d == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(if (sel) Fm.bg else Fm.surface)
                    .clickable { onSelect(d) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(d.icon, d.label, tint = if (sel) Fm.text else Fm.textDim, modifier = Modifier.size(26.dp))
                Text(d.label, color = if (sel) Fm.text else Fm.textDim, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun DisclaimerDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = Fm.surface,
        icon = { PixelLogoIcon(48.dp) },
        title = { PixelWordmark(height = 20.dp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Welcome to FlowMap Mobile.", color = Fm.text, fontWeight = FontWeight.SemiBold)
                Text("FlowMap shows public market data from Binance and Bybit. Liquidation heatmaps are model estimates, not observed orders.", color = Fm.textDim)
                Text("It is not investment advice, is not a broker and cannot place trades. Data can be delayed or incomplete.", color = Fm.yellow)
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("I understand", color = Fm.cyan, fontWeight = FontWeight.Bold) } },
    )
}
