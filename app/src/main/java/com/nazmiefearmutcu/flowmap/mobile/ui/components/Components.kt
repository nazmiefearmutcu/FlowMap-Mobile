package com.nazmiefearmutcu.flowmap.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazmiefearmutcu.flowmap.mobile.data.Provenance
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm

/** Top tab strip: large bold selected label, dimmed others — as on the reference screen. */
@Composable
fun TopTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            Text(
                t,
                color = if (sel) Fm.text else Fm.textDim,
                fontSize = if (sel) 24.sp else 18.sp,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSelect(i) }.padding(vertical = 6.dp),
            )
        }
    }
}

/** Pill tabs ("Liq Heatmap" | "Liq Map"). */
@Composable
fun PillTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (sel) Fm.surfaceHigh else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(t, color = if (sel) Fm.text else Fm.textDim, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textDecoration = if (sel) TextDecoration.Underline else null)
            }
        }
    }
}

/** Segmented control ("Pair" | "Symbol"). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(Fm.surface).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) Fm.surfaceTop else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(bg).clickable { onSelect(i) }
                    .heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (sel) Fm.text else Fm.textDim, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1) }
        }
    }
}

/** Dropdown chip with an underlined label, like "Model 1 ▾" / "3 mo ▾". */
@Composable
fun <T> DropdownChip(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    optionDetail: ((T) -> String)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Fm.surface).clickable { open = true }
                .heightIn(min = 44.dp).padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke(this)
            Text(label, color = Fm.text, fontSize = 15.sp, textDecoration = TextDecoration.Underline, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, null, tint = Fm.text)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Fm.surfaceHigh) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(optionLabel(o), color = Fm.text)
                            optionDetail?.invoke(o)?.let { Text(it, color = Fm.textDim, fontSize = 12.sp) }
                        }
                    },
                    onClick = { open = false; onSelect(o) },
                )
            }
        }
    }
}

@Composable
fun FilterChipRow(options: List<String>, selected: Set<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier, scrollable: Boolean = true) {
    Row(if (scrollable) modifier.horizontalScroll(rememberScrollState()) else modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i in selected
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (sel) Fm.cyan.copy(alpha = 0.16f) else Fm.surface)
                    .border(1.dp, if (sel) Fm.cyan.copy(alpha = 0.6f) else Fm.outline, RoundedCornerShape(50))
                    .clickable { onToggle(i) }.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (sel) Fm.cyan else Fm.textDim, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
fun ProvenanceBadge(p: Provenance, modifier: Modifier = Modifier, extra: String? = null) {
    val c = when (p) {
        Provenance.LIVE_L2, Provenance.LIVE -> Fm.green
        Provenance.REST -> Fm.cyan
        Provenance.ESTIMATE -> Fm.yellow
    }
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(5.dp))
        Text(p.badge + (extra?.let { " · $it" } ?: ""), color = c, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Fm.surface).border(1.dp, Fm.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(padding)) {
        content()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun ErrorBanner(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Fm.red.copy(alpha = 0.12f)).border(1.dp, Fm.red.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = Fm.red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, color = Fm.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry", color = Fm.cyan) }
    }
}

@Composable
fun ChangeChip(pct: Double, modifier: Modifier = Modifier) {
    val c = if (pct >= 0) Fm.buy else Fm.sell
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
        Text(com.nazmiefearmutcu.flowmap.mobile.core.Fmt.pct(pct), color = c, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, style = Fm.num, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier, fill: Boolean = true) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i / (values.size - 1f) * size.width
            val y = (size.height - ((v - lo) / span * size.height)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val area = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        }
        drawPath(path, color, style = Stroke(width = 1.6.dp.toPx()))
    }
}

/** Horizontal two-colour bar (long vs short share). */
@Composable
fun SplitBar(leftShare: Float, left: Color, right: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))) {
        val w = size.width * leftShare.coerceIn(0f, 1f)
        drawRect(left, size = size.copy(width = w))
        drawRect(right, topLeft = Offset(w, 0f), size = size.copy(width = size.width - w))
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Fm.text) {
    Column(modifier) {
        Text(label, color = Fm.textDim, fontSize = 12.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, style = Fm.num, maxLines = 1)
    }
}
