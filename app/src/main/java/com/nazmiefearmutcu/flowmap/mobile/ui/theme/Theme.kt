package com.nazmiefearmutcu.flowmap.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** FlowMap palette, sampled from the pixel brand sheet. */
object Fm {
    val bg = Color(0xFF05070D)
    val surface = Color(0xFF0D1120)
    val surfaceHigh = Color(0xFF151B2E)
    val surfaceTop = Color(0xFF1D2540)
    val outline = Color(0xFF252E48)
    val text = Color(0xFFF2F4FA)
    val textDim = Color(0xFF8A93AD)
    val textFaint = Color(0xFF5A6380)
    val cyan = Color(0xFF5AF5E3)
    val teal = Color(0xFF1F8FA5)
    val navy = Color(0xFF173E67)
    val frame = Color(0xFF101731)
    val yellow = Color(0xFFF2D04B)
    val orange = Color(0xFFFF7A2F)
    val red = Color(0xFFFE3B5B)
    val green = Color(0xFF3DFF9F)
    val buy = Color(0xFF2EBD85)
    val sell = Color(0xFFF6465D)
    val tooltipBorder = Color(0xFFF2D04B)

    val tierColors = listOf(Color(0xFF5AF5E3), Color(0xFF1F8FA5), Color(0xFFF2D04B), Color(0xFFFF7A2F), Color(0xFFFE3B5B), Color(0xFFB57BFF))

    val num = TextStyle(fontFeatureSettings = "tnum")
}

private val scheme = darkColorScheme(
    primary = Fm.cyan,
    onPrimary = Fm.bg,
    secondary = Fm.yellow,
    onSecondary = Fm.bg,
    tertiary = Fm.orange,
    background = Fm.bg,
    onBackground = Fm.text,
    surface = Fm.surface,
    onSurface = Fm.text,
    surfaceVariant = Fm.surfaceHigh,
    onSurfaceVariant = Fm.textDim,
    surfaceContainer = Fm.surface,
    surfaceContainerHigh = Fm.surfaceHigh,
    surfaceContainerHighest = Fm.surfaceTop,
    surfaceContainerLow = Fm.surface,
    outline = Fm.outline,
    outlineVariant = Fm.outline,
    error = Fm.red,
)

private val base = Typography()
private val typography = Typography(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
    labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.4.sp),
)

@Composable
fun FlowMapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
