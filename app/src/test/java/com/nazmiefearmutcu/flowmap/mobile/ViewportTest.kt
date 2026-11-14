package com.nazmiefearmutcu.flowmap.mobile

import androidx.compose.ui.geometry.Offset
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.ChartViewport
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.axisZoom
import com.nazmiefearmutcu.flowmap.mobile.ui.chart.spans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    private fun vp() = ChartViewport().apply {
        plotW = 1000f; plotH = 1000f
        dataStart = 0.0; dataEnd = 1_000_000.0
        minXSpan = 1_000.0
        set(0.0, 1_000_000.0, 100.0, 200.0)
        follow = false
    }

    @Test
    fun horizontalPinchScalesOnlyTime() {
        val v = vp()
        // Two fingers side by side moving apart: x span doubles, y span ~0 → only time zooms.
        val (_, sx0, sy0) = spans(listOf(Offset(400f, 500f), Offset(600f, 500f)))
        val (_, sx1, sy1) = spans(listOf(Offset(300f, 502f), Offset(700f, 498f)))
        v.zoom(axisZoom(sx0, sx1), axisZoom(sy0, sy1), 500f, 500f)
        assertEquals(500_000.0, v.xSpan, 1.0)
        assertEquals(100.0, v.ySpan, 1e-9)
        assertTrue(v.autoY) // price untouched
    }

    @Test
    fun verticalPinchScalesOnlyPrice() {
        val v = vp()
        val (_, sx0, sy0) = spans(listOf(Offset(500f, 400f), Offset(500f, 600f)))
        val (_, sx1, sy1) = spans(listOf(Offset(500f, 300f), Offset(500f, 700f)))
        v.zoom(axisZoom(sx0, sx1), axisZoom(sy0, sy1), 500f, 500f)
        assertEquals(1_000_000.0, v.xSpan, 1.0)
        assertEquals(50.0, v.ySpan, 1e-9)
        assertFalse(v.autoY)
    }

    @Test
    fun diagonalPinchScalesBothAroundTheFingers() {
        val v = vp()
        val anchorT = v.pxToX(250f)
        val anchorP = v.pxToY(250f)
        v.zoom(2f, 2f, 250f, 250f)
        assertEquals(anchorT, v.pxToX(250f), 1.0)
        assertEquals(anchorP, v.pxToY(250f), 1e-9)
    }

    @Test
    fun panMovesContentWithTheFingerAndStopsFollowing() {
        val v = vp()
        v.follow = true
        v.pan(100f, 0f) // finger moves right → earlier data comes into view
        assertEquals(-100_000.0, v.xMin, 1.0)
        assertFalse(v.follow)
        v.pan(0f, 100f) // finger moves down → higher prices come into view
        assertEquals(110.0, v.yMin, 1e-6)
    }

    @Test
    fun panIsClampedNearTheData() {
        val v = vp()
        v.pan(1_000_000f, 0f)
        assertTrue(v.xMax >= v.dataStart + v.xSpan * 0.15 - 1)
    }

    @Test
    fun followPinsTheRightEdge() {
        val v = vp()
        v.follow = true
        v.followTo(2_000_000.0, 0.0)
        assertEquals(2_000_000.0, v.xMax, 1e-6)
        assertEquals(1_000_000.0, v.xSpan, 1e-6)
    }

    @Test
    fun narrowAxisSpanDoesNotZoom() {
        assertEquals(1f, axisZoom(10f, 30f))
        assertEquals(2f, axisZoom(100f, 200f))
    }
}
