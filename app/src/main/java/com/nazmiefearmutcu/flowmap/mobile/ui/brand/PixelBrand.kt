package com.nazmiefearmutcu.flowmap.mobile.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The 16×16 FlowMap logo, identical to scripts/brand/pixel_assets.py. */
object PixelLogo {
    private val palette = mapOf(
        '1' to Color(0xFF101731), '2' to Color(0xFF3DFF9F), '3' to Color(0xFF173E67), '4' to Color(0xFF1F8FA5),
        '5' to Color(0xFFF2D04B), '6' to Color(0xFFFF7A2F), '7' to Color(0xFFFFFFFF), '8' to Color(0xFF9D1030),
        '9' to Color(0xFFFE3B5B), 'a' to Color(0xFFFF90A3),
    )
    val grid = listOf(
        "0111111111111110", "1111111111111211", "1333344333332221", "1455666554445251",
        "1344555443377731", "1113331111171331", "1111111111171111", "1133117777371111",
        "1111117117171111", "1337777338981331", "1447544459a95441", "1347666558985531",
        "1777443333333331", "1111111111111111", "1111111111111111", "0111111111111110",
    )

    fun DrawScope.drawLogo(topLeft: Offset, cell: Float, alpha: Float = 1f) {
        for ((y, row) in grid.withIndex()) for ((x, ch) in row.withIndex()) {
            val c = palette[ch] ?: continue
            drawRect(c.copy(alpha = alpha), Offset(topLeft.x + x * cell, topLeft.y + y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
fun PixelLogoIcon(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        with(PixelLogo) { drawLogo(Offset.Zero, this@Canvas.size.minDimension / 16f) }
    }
}

/** 5×7 pixel font matching the brand sheet. */
object PixelFont {
    private val glyphs = mapOf(
        'A' to ".###.#...##...#######...##...##...#",
        'B' to "####.#...##...#####.#...##...#####.",
        'C' to ".###.#...##....#....#....#...#.###.",
        'D' to "####.#...##...##...##...##...#####.",
        'E' to "######....#....####.#....#....#####",
        'F' to "######....#....####.#....#....#....",
        'G' to ".###.#...##....#.####...##...#.####",
        'H' to "#...##...##...#######...##...##...#",
        'I' to "#####..#....#....#....#....#..#####",
        'J' to "..###...#....#....#.#..#.#..#..##..",
        'K' to "#...##..#.#.#..##...#.#..#..#.#...#",
        'L' to "#....#....#....#....#....#....#####",
        'M' to "#...###.###.#.##.#.##...##...##...#",
        'N' to "#...###..##.#.##..###...##...##...#",
        'O' to ".###.#...##...##...##...##...#.###.",
        'P' to "####.#...##...#####.#....#....#....",
        'Q' to ".###.#...##...##...##.#.##..#..##.#",
        'R' to "####.#...##...#####.#.#..#..#.#...#",
        'S' to ".#####....#.....###.....#....#####.",
        'T' to "#####..#....#....#....#....#....#..",
        'U' to "#...##...##...##...##...##...#.###.",
        'V' to "#...##...##...##...##...#.#.#...#..",
        'W' to "#...##...##...##.#.##.#.###.###...#",
        'X' to "#...##...#.#.#...#...#.#.#...##...#",
        'Y' to "#...##...#.#.#...#....#....#....#..",
        'Z' to "#####....#...#...#...#...#....#####",
        '0' to ".###.#...##..###.#.###..##...#.###.",
        '1' to "..#...##....#....#....#....#...###.",
        '2' to ".###.#...#....#...#...#...#...#####",
        '3' to "####.....#....#.###.....#....#####.",
        '4' to "...#...##..#.#.#..#.#####...#....#.",
        '5' to "######....####.....#....##...#.###.",
        '6' to ".###.#....#....####.#...##...#.###.",
        '7' to "#####....#...#...#...#....#....#...",
        '8' to ".###.#...##...#.###.#...##...#.###.",
        '9' to ".###.#...##...#.####....#....#.###.",
        '.' to "..........................##...##..",
        ' ' to "...................................",
    )

    fun DrawScope.drawPixelText(text: String, topLeft: Offset, px: Float, color: Color, shadow: Color? = null) {
        var x = topLeft.x
        for (ch in text.uppercase()) {
            val g = glyphs[ch] ?: glyphs[' ']!!
            for (i in g.indices) if (g[i] == '#') {
                val gx = i % 5
                val gy = i / 5
                if (shadow != null) drawRect(shadow, Offset(x + (gx + 1) * px, topLeft.y + (gy + 1) * px), Size(px, px))
                drawRect(color, Offset(x + gx * px, topLeft.y + gy * px), Size(px + 0.3f, px + 0.3f))
            }
            x += 6 * px
        }
    }

    fun width(text: String, px: Float) = (text.length * 6 - 1) * px
}

@Composable
fun PixelWordmark(text: String = "FLOWMAP", height: Dp = 18.dp, color: Color = Color.White, shadow: Color? = Color(0xFF1B3D66), modifier: Modifier = Modifier) {
    val px = height / 8
    Canvas(modifier.size(width = px * (text.length * 6), height = height)) {
        with(PixelFont) { drawPixelText(text, Offset.Zero, px.toPx(), color, shadow) }
    }
}
