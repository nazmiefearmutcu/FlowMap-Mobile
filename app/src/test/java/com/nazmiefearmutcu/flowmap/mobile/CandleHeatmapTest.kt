package com.nazmiefearmutcu.flowmap.mobile

import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.engine.CandleHeatmap
import com.nazmiefearmutcu.flowmap.mobile.engine.DepthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleHeatmapTest {
    private fun c(i: Int, o: Double, h: Double, l: Double, close: Double, qv: Double) =
        Candle(i * 60_000L, o, h, l, close, qv / close, qv)

    @Test
    fun bandWeightsMatchDesktopErodeShape() {
        val w = CandleHeatmap.bandWeights(0, 10, 7)
        assertEquals(1.0, w.sum(), 1e-12)
        assertEquals(7, w.indices.maxBy { w[it] }) // peak at the close row
        assertTrue(w.first() > 0 && w.last() > 0) // floor keeps the band edges visible
        assertTrue(w[0] < w[7] && w[10] < w[7])
    }

    @Test
    fun firstColumnPreservesVolumeAndMemoryFades() {
        val cs = listOf(c(0, 100.0, 101.0, 99.0, 100.5, 1_000.0), c(120, 100.5, 102.0, 100.0, 101.0, 2_000.0))
        val h = CandleHeatmap.from(cs, until = 1_000 * 60_000L, rows = 200)!!
        // Column 0 holds exactly the first candle's traded value.
        assertEquals(1_000.0, (0 until h.rows).sumOf { h.value(0, it).toDouble() }, 1e-3)
        // Two hours later (one half-life) the first deposit survives at half weight next to the new one.
        assertEquals(500.0 + 2_000.0, (0 until h.rows).sumOf { h.value(1, it).toDouble() }, 1e-2)
        // A band below the current price persists on the bid side.
        h.mode = DepthMode.ASKS
        val close = h.rowAt(101.0)
        assertTrue((0 until close).all { h.value(1, it) == 0f })
        h.mode = DepthMode.BIDS
        assertTrue((close + 2 until h.rows).all { h.value(1, it) == 0f })
        h.mode = DepthMode.TOTAL
        assertTrue(h.value(1, h.rowAt(99.2)) > 0f) // old volume node still visible
    }

    @Test
    fun historyStopsWhereRecordingStarts() {
        val cs = (0 until 10).map { c(it, 100.0, 101.0, 99.0, 100.0, 1_000.0) }
        val h = CandleHeatmap.from(cs, until = 5 * 60_000L + 30_000L)!!
        assertEquals(5, h.cols) // the minute containing the first live column is left to live data
        assertNull(CandleHeatmap.from(cs.take(1), until = Long.MAX_VALUE))
    }
}
