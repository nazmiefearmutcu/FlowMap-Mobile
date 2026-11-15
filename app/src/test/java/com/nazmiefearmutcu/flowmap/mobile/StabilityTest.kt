package com.nazmiefearmutcu.flowmap.mobile

import androidx.compose.ui.geometry.Offset
import com.nazmiefearmutcu.flowmap.mobile.engine.HeatField
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.PanGate
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.PinchGate
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.pow2AtLeast
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.segmentsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navigation must never change what the charts report. */
class StabilityTest {
    private class Field(private val times: LongArray) : HeatField {
        override val cols = times.size
        override val rows = 1
        override val priceMin = 0.0
        override val priceStep = 1.0
        override val colDurationMs = 1000L
        override val scaleMax = 1f
        override fun colTime(c: Int) = times[c]
        override fun value(c: Int, r: Int) = 1f
    }

    @Test
    fun recordingGapsSplitSegments() {
        val times = longArrayOf(0, 1000, 2000, 60_000, 61_000)
        val segs = segmentsOf(Field(times))
        assertEquals(2, segs.size)
        assertEquals(0, segs[0].first); assertEquals(2, segs[0].last)
        assertEquals(3, segs[1].first); assertEquals(4, segs[1].last)
    }

    @Test
    fun lateSampleIsNotAGap() {
        val times = longArrayOf(0, 1000, 2900, 3900, 30_000)
        val segs = segmentsOf(Field(times))
        assertEquals(2, segs.size)
        assertEquals(3, segs[0].last)
    }

    @Test
    fun zoomLevelsArePowersOfTwo() {
        assertEquals(1, pow2AtLeast(0.3))
        assertEquals(1, pow2AtLeast(1.0))
        assertEquals(4, pow2AtLeast(3.2))
        assertEquals(8, pow2AtLeast(8.0))
    }

    @Test
    fun smallDriftDoesNotUnlockPriceOrFollow() {
        val g = PanGate(autoY = true, follow = true)
        var applied = Offset.Zero
        // A sideways swipe with a few pixels of vertical wobble.
        repeat(20) { applied += g.filter(Offset(-6f, if (it % 2 == 0) 2f else -1.5f)) }
        assertFalse(g.yUnlocked)
        assertEquals(0f, applied.y, 0f)
        // Leftward movement past the threshold unlocks time and replays what was held.
        assertTrue(g.xUnlocked)
        assertEquals(-120f, applied.x, 1e-3f)
    }

    @Test
    fun deliberateVerticalDragUnlocksPrice() {
        val g = PanGate(autoY = true, follow = false)
        var applied = Offset.Zero
        repeat(10) { applied += g.filter(Offset(1f, 8f)) }
        assertTrue(g.yUnlocked)
        assertEquals(80f, applied.y, 1e-3f) // nothing lost once unlocked
    }

    @Test
    fun twoFingerDragWobbleNeverZooms() {
        val g = PinchGate()
        g.reset(300f, 200f)
        // A two-finger drag: the spreads wander by a few percent.
        var zx = 1f
        var zy = 1f
        for (k in 0 until 40) {
            val (fx, fy) = g.factors(300f * (1f + 0.05f * kotlin.math.sin(k / 3f)), 200f * (1f - 0.06f * kotlin.math.cos(k / 4f)))
            zx *= fx; zy *= fy
        }
        assertEquals(1f, zx, 0f)
        assertEquals(1f, zy, 0f)
    }

    @Test
    fun clearPinchUnlocksAndAppliesTheWholeChange() {
        val g = PinchGate()
        g.reset(300f, 200f)
        var zx = 1f
        for (k in 1..10) zx *= g.factors(300f + k * 15f, 200f).first // spread to 450 px
        assertTrue(g.xUnlocked)
        assertEquals(1.5f, zx, 1e-4f) // exactly the fingers' change, nothing lost to the dead zone
        assertFalse(g.yUnlocked)
    }

    @Test
    fun manualModesPassThrough() {
        val g = PanGate(autoY = false, follow = false)
        assertEquals(Offset(3f, 4f), g.filter(Offset(3f, 4f)))
    }
}
