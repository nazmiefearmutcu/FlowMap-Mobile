package com.nazmiefearmutcu.flowmap.mobile.ui.screens.me

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.BuildConfig
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.ChartStyle
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import com.nazmiefearmutcu.flowmap.mobile.engine.HeatPalette
import com.nazmiefearmutcu.flowmap.mobile.ui.brand.PixelLogoIcon
import com.nazmiefearmutcu.flowmap.mobile.ui.brand.PixelWordmark
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Card
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Segmented
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SectionTitle
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm

const val DESKTOP_URL = "https://github.com/nazmiefearmutcu/FlowMap-Trade-Terminal"

@Composable
fun MeScreen() {
    val s = AppGraph.settings
    val venue by s.venue.collectAsState()
    val style by s.style.collectAsState()
    val palette by s.palette.collectAsState()
    val haptics by s.haptics.collectAsState()
    val keepOn by s.keepScreenOn.collectAsState()
    val ctx = LocalContext.current
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelLogoIcon(56.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                PixelWordmark(height = 22.dp)
                Spacer(Modifier.height(6.dp))
                Text("ORDER FLOW · HEATMAP · MOBILE", color = Fm.cyan, fontSize = 11.sp, letterSpacing = 2.sp)
                Text("Version ${BuildConfig.VERSION_NAME}", color = Fm.textDim, fontSize = 12.sp)
            }
        }

        SectionTitle("Data")
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Preferred venue", color = Fm.text, fontWeight = FontWeight.SemiBold)
                Segmented(Venue.entries.map { it.short }, venue.ordinal, { s.setVenue(Venue.entries[it]) })
                Text("If a venue refuses your region, FlowMap switches to the other one automatically.", color = Fm.textDim, fontSize = 12.sp)
            }
        }

        SectionTitle("Display")
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Heatmap style", color = Fm.text, fontWeight = FontWeight.SemiBold)
                Segmented(ChartStyle.entries.map { it.label }, style.ordinal, { s.setStyle(ChartStyle.entries[it]) })
                Text("Colour ramp", color = Fm.text, fontWeight = FontWeight.SemiBold)
                Segmented(HeatPalette.entries.map { it.label }, palette.ordinal, { s.setPalette(HeatPalette.entries[it]) })
                ToggleRow("Haptic feedback", "Vibrate when the crosshair appears", haptics, s::setHaptics)
                ToggleRow("Keep screen on", "While the live chart is open", keepOn, s::setKeepScreenOn)
            }
        }

        SectionTitle("Gestures")
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Gesture(Icons.Default.PanTool, "One finger", "Drag anywhere to move through time and price. Flick to glide.")
                Gesture(Icons.Default.ZoomIn, "Two or more fingers", "Pinch sideways to zoom time, up-down to zoom price, diagonally for both. Move fingers together to pan.")
                Gesture(Icons.Default.TouchApp, "Tap / long-press", "Tap to inspect a point, tap again to hide. Long-press and drag to scrub.")
                Gesture(Icons.Default.Height, "Axes", "Drag the price axis to stretch price, the time axis to stretch time. Double-tap an axis to auto-fit.")
                Gesture(Icons.Default.ZoomOutMap, "Double-tap", "Resets the view. The LIVE button returns to the newest data.")
            }
        }

        SectionTitle("About")
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "FlowMap Mobile reads public market data directly from Binance and Bybit, curated RSS feeds and the alternative.me Fear & Greed index. " +
                        "There is no account, no tracking and no FlowMap server: settings stay on this phone.",
                    color = Fm.text, fontSize = 14.sp,
                )
                Text(
                    "Liquidation heatmaps and maps are estimates computed on the device from candles, open interest and taker flow. " +
                        "No venue publishes where positions would be liquidated. Order-book heatmaps are recorded live from the venue while the app is open.",
                    color = Fm.textDim, fontSize = 13.sp,
                )
                Text(
                    "FlowMap is not investment advice, does not recommend any security, is not a broker and cannot place orders. Data can be delayed, incomplete or unavailable.",
                    color = Fm.yellow, fontSize = 13.sp,
                )
                LinkRow("FlowMap desktop (Windows, macOS, Linux)") { open(DESKTOP_URL) }
                LinkRow("Licence: Apache 2.0") { open("https://www.apache.org/licenses/LICENSE-2.0") }
                Text("Data: Binance, Bybit, alternative.me and the publishers named on each story. Their terms of use apply.", color = Fm.textFaint, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Fm.text, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Fm.textDim, fontSize = 12.sp)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Fm.cyan, checkedThumbColor = Fm.bg))
    }
}

@Composable
private fun Gesture(icon: ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Fm.cyan, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Fm.text, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Fm.textDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Fm.cyan, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Fm.cyan)
    }
}
