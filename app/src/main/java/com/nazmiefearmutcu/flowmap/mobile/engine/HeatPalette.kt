package com.nazmiefearmutcu.flowmap.mobile.engine

/** Colour ramps for heatmaps, each expanded to a 256-entry ARGB lookup table. */
enum class HeatPalette(val label: String, private val stops: IntArray) {
    VIRIDIS("Color 1", intArrayOf(0xFF3B0F5C.toInt(), 0xFF3E3A84.toInt(), 0xFF2D6A8E.toInt(), 0xFF1F948C.toInt(), 0xFF2DB87A.toInt(), 0xFF8FD744.toInt(), 0xFFFDE725.toInt())),
    FLOWMAP("Color 2", intArrayOf(0xFF0B1022.toInt(), 0xFF173E67.toInt(), 0xFF1F8FA5.toInt(), 0xFF5AF5E3.toInt(), 0xFFF2D04B.toInt(), 0xFFFF7A2F.toInt(), 0xFFFE3B5B.toInt())),
    INFERNO("Color 3", intArrayOf(0xFF07050F.toInt(), 0xFF320A5E.toInt(), 0xFF781C6D.toInt(), 0xFFBC3754.toInt(), 0xFFED6925.toInt(), 0xFFFBB61A.toInt(), 0xFFFCFFA4.toInt())),
    ICE("Color 4", intArrayOf(0xFF050A14.toInt(), 0xFF0B2545.toInt(), 0xFF13315C.toInt(), 0xFF1F6FB2.toInt(), 0xFF4CC9F0.toInt(), 0xFFB8F2FF.toInt(), 0xFFFFFFFF.toInt()));

    val lut: IntArray by lazy { build(stops) }

    /** First colour of the ramp: the "no liquidity" background. */
    val background: Int get() = stops[0]

    companion object {
        fun build(stops: IntArray): IntArray {
            val out = IntArray(256)
            val segs = stops.size - 1
            for (i in 0 until 256) {
                val x = i / 255.0 * segs
                val s = minOf(segs - 1, x.toInt())
                val f = x - s
                out[i] = lerp(stops[s], stops[s + 1], f)
            }
            return out
        }

        fun lerp(a: Int, b: Int, f: Double): Int {
            fun ch(c: Int, sh: Int) = (c shr sh) and 0xFF
            val r = (ch(a, 16) + (ch(b, 16) - ch(a, 16)) * f).toInt()
            val g = (ch(a, 8) + (ch(b, 8) - ch(a, 8)) * f).toInt()
            val bl = (ch(a, 0) + (ch(b, 0) - ch(a, 0)) * f).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }
}
