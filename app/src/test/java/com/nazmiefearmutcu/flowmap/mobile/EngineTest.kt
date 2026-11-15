package com.nazmiefearmutcu.flowmap.mobile

import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.data.OiPoint
import com.nazmiefearmutcu.flowmap.mobile.engine.DepthHeatmap
import com.nazmiefearmutcu.flowmap.mobile.engine.HeatPalette
import com.nazmiefearmutcu.flowmap.mobile.engine.Indicators
import com.nazmiefearmutcu.flowmap.mobile.engine.LiqHeatmapEngine
import com.nazmiefearmutcu.flowmap.mobile.engine.LiqMapBars
import com.nazmiefearmutcu.flowmap.mobile.engine.LiqModel
import com.nazmiefearmutcu.flowmap.mobile.engine.OrderBook
import com.nazmiefearmutcu.flowmap.mobile.engine.TradeAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

class EngineTest {
    private val hour = 3_600_000L

    private fun flat(n: Int, price: Double, t0: Long = 0L) = List(n) { i ->
        Candle(t0 + i * hour, price, price * 1.001, price * 0.999, price, 100.0, 1_000_000.0, 500_000.0)
    }

    @Test
    fun liquidationLevelsSitAtLeverageDistanceAndSurviveFlatPrice() {
        val cs = flat(50, 100.0)
        val oi = cs.mapIndexed { i, c -> OiPoint(c.t, 1_000_000.0 + i * 10_000.0) }
        val h = LiqHeatmapEngine.build(cs, oi, LiqModel.MODEL_1, hour)!!
        assertTrue(h.usedOpenInterest)
        // 10x long liquidations sit ~9.6% below price; there must be value there in the last column.
        val r = h.rowAt(LiqHeatmapEngine.longLiqPrice(100.0, 10))
        assertTrue(h.value(h.cols - 1, r) > 0f)
        // Nothing is placed at the traded price itself.
        assertEquals(0f, h.value(h.cols - 1, h.rowAt(100.0)), 0f)
    }

    @Test
    fun priceTradingThroughALevelLiquidatesIt() {
        val cs = flat(30, 100.0).toMutableList()
        // A crash candle to 85 sweeps every long level above 85 (5x and up from entry 100).
        cs += Candle(30 * hour, 100.0, 100.0, 85.0, 86.0, 100.0, 1_000_000.0, 500_000.0)
        val h = LiqHeatmapEngine.build(cs, emptyList(), LiqModel.MODEL_1, hour)!!
        assertTrue(!h.usedOpenInterest)
        val before = h.value(cs.size - 2, h.rowAt(LiqHeatmapEngine.longLiqPrice(100.0, 25)))
        val after = h.value(cs.size - 1, h.rowAt(LiqHeatmapEngine.longLiqPrice(100.0, 25)))
        assertTrue("level existed before the sweep", before > 0f)
        assertEquals("level gone after the sweep", 0f, after, 0f)
        // Short liquidation levels above price are untouched by a down move.
        val short = h.value(cs.size - 1, h.rowAt(LiqHeatmapEngine.shortLiqPrice(100.0, 25)))
        assertTrue(short > 0f)
    }

    @Test
    fun liqMapBarsSplitLongsBelowAndShortsAbove() {
        val h = LiqHeatmapEngine.build(flat(40, 100.0), emptyList(), LiqModel.MODEL_2, hour)!!
        val bars = LiqMapBars.from(h, 4)
        val cumL = bars.cumulativeLong
        val cumS = bars.cumulativeShort
        assertTrue(cumL.first() > 0 && cumL.last() == 0.0)
        assertTrue(cumS.last() > 0 && cumS.first() == 0.0)
    }

    @Test
    fun liqMapBarsDependOnlyOnDataAndZoom() {
        val h = LiqHeatmapEngine.build(flat(40, 100.0), emptyList(), LiqModel.MODEL_1, hour)!!
        // Same zoom level → identical buckets and totals, whatever part of the map is on screen.
        val a = LiqMapBars.from(h, 8)
        val b = LiqMapBars.from(h, 8)
        assertEquals(a.bucket, b.bucket, 0.0)
        assertEquals(a.priceMin, b.priceMin, 0.0)
        for (k in 0 until a.buckets) assertEquals(a.total(k), b.total(k), 0.0)
        // Buckets are whole groups of rows, so every zoom level preserves the map's grand total.
        val rowsTotal = h.model.tiers.indices.sumOf { t -> (0 until h.rows).sumOf { r -> (h.finalLong[t][r] + h.finalShort[t][r]).toDouble() } }
        for (g in listOf(1, 2, 4, 16)) {
            val bars = LiqMapBars.from(h, g)
            assertEquals(rowsTotal, (0 until bars.buckets).sumOf { bars.total(it) }, rowsTotal * 1e-9)
            assertEquals(g * h.priceStep, bars.bucket, 1e-12)
        }
        // Zoom levels are powers of two rows.
        val g = LiqMapBars.rowGroupFor(h, h.priceStep * 5)
        assertEquals(8, g)
    }

    @Test
    fun mergeOiOnlySumsWhereEveryVenueHasData() {
        val a = listOf(OiPoint(0, 10.0), OiPoint(10, 20.0), OiPoint(20, 30.0))
        val b = listOf(OiPoint(5, 1.0), OiPoint(15, 2.0))
        val m = LiqHeatmapEngine.mergeOi(listOf(a, b))
        // t=0 is dropped: venue b has no data yet, and adding it later must not look like new positions.
        assertEquals(listOf(10L, 20L), m.map { it.t })
        assertEquals(listOf(21.0, 32.0), m.map { it.usd })
    }

    @Test
    fun mergeOiStartingLateProducesNoJump() {
        // Long series (Bybit, flat 100) and a short one (Binance, flat 50) starting later.
        val long = (0 until 100).map { OiPoint(it * 10L, 100.0) }
        val short = (50 until 100).map { OiPoint(it * 10L, 50.0) }
        val m = LiqHeatmapEngine.mergeOi(listOf(long, short))
        assertTrue(m.all { it.usd == 150.0 })
    }

    @Test
    fun crossedBookDropsTheStaleBid() {
        val b = OrderBook()
        b.replace(listOf(100.0 to 1.0, 99.0 to 1.0), listOf(101.0 to 1.0, 102.0 to 1.0))
        // An ask update lands below a stale bid: the bid is the stale side.
        b.apply(emptyList(), listOf(99.5 to 2.0))
        assertEquals(99.0, b.bestBid, 0.0)
        assertEquals(99.5, b.bestAsk, 0.0)
        assertEquals(101.0, b.asks.higherKey(99.5)!!, 0.0) // real ask levels untouched
    }

    @Test
    fun minuteBucketsKeepEveryMinuteRegardlessOfVolume() {
        val m = com.nazmiefearmutcu.flowmap.mobile.engine.MinuteBuckets(60)
        val now = 10_000L * 60_000L + 30_000L
        // 5 000 events spread across the hour: none may be lost.
        repeat(5_000) { i -> m.add(now - (i % 60) * 60_000L, 1.0, i % 2 == 0, now) }
        val (l, s) = m.snapshot(now)
        assertEquals(5_000.0, l.sum() + s.sum(), 1e-9)
        // Events older than an hour are ignored entirely.
        m.add(now - 61 * 60_000L, 7.0, true, now)
        val (l1, s1) = m.snapshot(now)
        assertEquals(5_000.0, l1.sum() + s1.sum(), 1e-9)
        // The window slides: one minute later the oldest minute has left it.
        val (l2, s2) = m.snapshot(now + 60_000L)
        val oldest = l.first() + s.first()
        assertEquals(5_000.0 - oldest, l2.sum() + s2.sum(), 1e-9)
        // A venue clock a few seconds ahead still counts, in the current minute.
        m.add(now + 45_000L, 3.0, false, now)
        assertEquals(3.0 + s.last(), m.snapshot(now).second.last(), 1e-9)
    }

    @Test
    fun cumulativeCurvesSplitTheBucketHoldingThePrice() {
        val h = LiqHeatmapEngine.build(flat(40, 100.0), emptyList(), LiqModel.MODEL_1, hour)!!
        // Coarse buckets so the bucket containing the price also holds liquidation rows on both sides.
        val bars = LiqMapBars.from(h, 64)
        val rowsLong = h.model.tiers.indices.sumOf { t -> (0 until h.rows).filter { h.rowPrice(it) <= h.lastPrice }.sumOf { h.finalLong[t][it].toDouble() } }
        val rowsShort = h.model.tiers.indices.sumOf { t -> (0 until h.rows).filter { h.rowPrice(it) >= h.lastPrice }.sumOf { h.finalShort[t][it].toDouble() } }
        assertEquals(rowsLong, bars.cumulativeLong.maxOrNull()!!, rowsLong * 1e-9)
        assertEquals(rowsShort, bars.cumulativeShort.maxOrNull()!!, rowsShort * 1e-9)
        // The partial top bucket never claims prices above the map.
        assertEquals(h.priceMin + h.rows * h.priceStep, bars.bucketHigh(bars.buckets - 1), 1e-9)
    }

    @Test
    fun rowAtFloorsBelowTheMap() {
        val h = LiqHeatmapEngine.build(flat(10, 100.0), emptyList(), LiqModel.MODEL_1, hour)!!
        assertEquals(-1, h.rowAt(h.priceMin - h.priceStep * 0.5))
        assertEquals(0, h.rowAt(h.priceMin + h.priceStep * 0.5))
    }

    @Test
    fun recordedDepthSurvivesLargeMoves() {
        val d = DepthHeatmap(capacity = 100, windowRows = 100)
        fun one(p: Double, q: Double) = TreeMap<Double, Double>(reverseOrder()).apply { put(p, q) }
        fun askOf(p: Double) = TreeMap<Double, Double>().apply { put(p, 1.0) }
        // A wall at 99.0 recorded around 100, then price runs 8% higher (beyond the ±6% window).
        d.record(0, one(99.0, 10.0), askOf(100.02), 99.0, 100.02, TradeAccumulator())
        val before = d.bidAt(0, d.rowAt(99.0))
        for (i in 1..40) { val m = 100.0 + i * 0.2; d.record(i * 1000L, one(m - 0.01, 1.0), askOf(m + 0.01), m - 0.01, m + 0.01, TradeAccumulator()) }
        assertTrue(before > 0f)
        // The first column still reports exactly what was recorded, at the same price.
        assertEquals(before, d.bidAt(0, d.rowAt(99.0)), 0f)
        assertTrue(d.rowAt(108.0) in 0 until d.rows) // and the field now also covers the new prices
    }

    @Test
    fun depthColourScaleIsHeldAfterWarmup() {
        val d = DepthHeatmap(capacity = 200, windowRows = 50)
        fun book(q: Double) = TreeMap<Double, Double>(reverseOrder()).apply { put(99.99, q) } to TreeMap<Double, Double>().apply { put(100.01, q) }
        repeat(DepthHeatmap.WARMUP) { val (b, a) = book(1.0); d.record(1000L * it, b, a, 99.99, 100.01, TradeAccumulator()) }
        val learnt = d.scaleMax
        repeat(20) { val (b, a) = book(1.5); d.record(100_000L + 1000L * it, b, a, 99.99, 100.01, TradeAccumulator()) }
        assertEquals("history must keep its colours", learnt, d.scaleMax, 0f)
    }

    @Test
    fun depthHeatmapRecordsBinsAndRecentres() {
        val d = DepthHeatmap(capacity = 10, windowRows = 100)
        val bids = TreeMap<Double, Double>(reverseOrder()).apply { put(99.99, 5.0); put(99.5, 2.0) }
        val asks = TreeMap<Double, Double>().apply { put(100.01, 3.0) }
        val acc = TradeAccumulator().apply { add(100.0, 1.0, true) }
        d.record(1000, bids, asks, 99.99, 100.01, acc)
        assertEquals(1, d.cols)
        val rb = d.rowAt(99.99)
        assertEquals(99.99 * 5.0, d.bidAt(0, rb).toDouble(), 1e-3)
        assertEquals(100.0, d.buyUsdAt(0), 1e-9)
        // Ring buffer keeps only the newest [capacity] columns.
        repeat(15) { d.record(2000L + it, bids, asks, 99.99, 100.01, TradeAccumulator()) }
        assertEquals(10, d.cols)
        assertEquals(2005L, d.colTime(0))
        // A large move re-anchors the window and keeps the mid inside it.
        val far = TreeMap<Double, Double>(reverseOrder()).apply { put(103.0, 1.0) }
        val farAsk = TreeMap<Double, Double>().apply { put(103.02, 1.0) }
        d.record(9999, far, farAsk, 103.0, 103.02, TradeAccumulator())
        assertTrue(d.rowAt(103.01) in 0 until d.rows)
    }

    @Test
    fun orderBookAppliesDeltasAndHealsCrossing() {
        val b = OrderBook()
        b.replace(listOf(100.0 to 1.0, 99.0 to 2.0), listOf(101.0 to 1.0, 102.0 to 1.0))
        b.apply(listOf(100.0 to 0.0), emptyList())
        assertEquals(99.0, b.bestBid, 0.0)
        b.apply(listOf(101.5 to 1.0), emptyList()) // crosses the 101 ask: the stale ask is dropped
        assertEquals(101.5, b.bestBid, 0.0)
        assertEquals(102.0, b.bestAsk, 0.0)
    }

    @Test
    fun formatting() {
        assertEquals("200.82M", Fmt.compact(200_820_000.0))
        assertEquals("90,282.0", Fmt.price(90282.0))
        assertEquals("+1.50%", Fmt.pct(1.5))
        assertEquals("0.0100%", Fmt.funding(0.0001).removePrefix("+"))
        assertEquals(2.0, Fmt.niceStep(1.9), 0.0)
        assertEquals(5.0, Fmt.niceStep(4.1), 0.0)
    }

    @Test
    fun paletteLutIsOpaqueAndMonotonicEnds() {
        for (p in HeatPalette.entries) {
            assertEquals(256, p.lut.size)
            assertTrue(p.lut.all { (it ushr 24) == 0xFF })
            assertEquals(p.background, p.lut[0])
        }
    }

    @Test
    fun indicators() {
        val e = Indicators.ema(doubleArrayOf(1.0, 1.0, 1.0, 1.0), 2)
        assertEquals(1.0, e[3], 1e-9)
        val r = Indicators.rsi(DoubleArray(30) { it.toDouble() })
        assertEquals(100.0, r[29], 1e-9)
    }
}
