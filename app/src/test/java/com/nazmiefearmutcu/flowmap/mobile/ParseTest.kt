package com.nazmiefearmutcu.flowmap.mobile

import com.nazmiefearmutcu.flowmap.mobile.data.exchange.BinanceParse
import com.nazmiefearmutcu.flowmap.mobile.data.exchange.pages
import com.nazmiefearmutcu.flowmap.mobile.data.news.NewsCategory
import com.nazmiefearmutcu.flowmap.mobile.data.news.NewsItem
import com.nazmiefearmutcu.flowmap.mobile.data.news.NewsQuality
import com.nazmiefearmutcu.flowmap.mobile.data.news.Sentiment
import com.nazmiefearmutcu.flowmap.mobile.data.news.StoryClusterer
import com.nazmiefearmutcu.flowmap.mobile.data.stream.BinanceStreams
import com.nazmiefearmutcu.flowmap.mobile.data.stream.BybitStreams
import com.nazmiefearmutcu.flowmap.mobile.data.stream.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads recorded from the live endpoints on 2026-11-15. */
class ParseTest {
    @Test
    fun binanceKlines() {
        val body = """[[1794690000000,"64738.00","64948.00","63888.00","64426.40","21659.691",1794693599999,"1396292423.87360",654068,"10352.224","667692233.72730","0"]]"""
        val c = BinanceParse.klines(body).single()
        assertEquals(1794690000000, c.t)
        assertEquals(63888.0, c.l, 0.0)
        assertEquals(1396292423.8736, c.quoteVolume, 1e-3)
        assertEquals(667692233.7273, c.takerBuyQuote, 1e-3)
    }

    @Test
    fun binanceOpenInterestUsesUsdValue() {
        val body = """[{"symbol":"BTCUSDT","sumOpenInterest":"97257.019","sumOpenInterestValue":"6298484356.287807","timestamp":1794690000000}]"""
        assertEquals(6298484356.287807, BinanceParse.openInterest(body).single().usd, 1e-3)
    }

    @Test
    fun binanceRatios() {
        val acc = BinanceParse.accountRatio("""[{"symbol":"BTCUSDT","longAccount":"0.6372","longShortRatio":"1.7565","shortAccount":"0.3628","timestamp":1794693600000}]""").single()
        assertEquals(63.72, acc.longPct, 1e-6)
        assertEquals(1.7563, acc.ratio, 1e-3)
        val taker = BinanceParse.takerRatio("""[{"buySellRatio":"0.9155","sellVol":"11307.4670","buyVol":"10352.2240","timestamp":1794690000000}]""").single()
        assertEquals(0.9155, taker.ratio, 1e-3)
    }

    @Test
    fun binanceStreamEvents() {
        val depth = BinanceStreams.parseCombined("""{"stream":"btcusdt@depth@100ms","data":{"e":"depthUpdate","E":1,"T":1,"s":"BTCUSDT","U":10,"u":12,"pu":9,"b":[["100.0","1.5"]],"a":[["101.0","0"]]}}""").single() as StreamEvent.Depth
        assertEquals(10L, depth.firstId); assertEquals(12L, depth.lastId); assertEquals(9L, depth.prevId)
        assertEquals(0.0, depth.asks.single().second, 0.0)
        val trade = BinanceStreams.parseCombined("""{"stream":"x","data":{"e":"aggTrade","E":1,"s":"BTCUSDT","a":1,"p":"100.5","q":"2","f":1,"l":1,"T":5,"m":true}}""").single() as StreamEvent.Trades
        assertFalse("buyer is maker → aggressive sell", trade.list.single().buyerIsTaker)
        val liq = BinanceStreams.parseCombined("""{"e":"forceOrder","E":1,"o":{"s":"ETHUSDT","S":"SELL","o":"LIMIT","f":"IOC","q":"3","p":"2000","ap":"1990","X":"FILLED","l":"3","z":"3","T":7}}""").single() as StreamEvent.Liq
        assertTrue("a SELL liquidation order closes a long", liq.liq.longLiquidated)
        assertEquals(5970.0, liq.liq.usd, 1e-9)
        val coinM = BinanceStreams.parseCombined("""{"e":"forceOrder","E":1,"o":{"s":"BTCUSD_PERP","S":"SELL","q":"300","p":"63000","ap":"63000","z":"300","T":7}}""")
        assertTrue("coin-margined contracts are skipped", coinM.isEmpty())
    }

    @Test
    fun bybitStreamEvents() {
        val ob = BybitStreams.parse("""{"topic":"orderbook.200.BTCUSDT","type":"snapshot","ts":1,"data":{"s":"BTCUSDT","b":[["100","1"]],"a":[["101","2"]],"u":5,"seq":9}}""").single() as StreamEvent.Depth
        assertTrue(ob.snapshot)
        val liq = BybitStreams.parse("""{"topic":"allLiquidation.BTCUSDT","type":"snapshot","ts":1,"data":[{"T":1,"s":"BTCUSDT","S":"Buy","v":"0.5","p":"90000"}]}""").single() as StreamEvent.Liq
        assertTrue("Bybit side Buy = long liquidated", liq.liq.longLiquidated)
        val tr = BybitStreams.parse("""{"topic":"publicTrade.BTCUSDT","data":[{"T":1,"s":"BTCUSDT","S":"Buy","v":"0.1","p":"90000"}]}""").single() as StreamEvent.Trades
        assertTrue(tr.list.single().buyerIsTaker)
    }

    @Test
    fun paging() {
        val p = pages(0, 2_500, 1, 1000)
        assertEquals(3, p.size)
        assertEquals(0L to 999L, p[0])
        assertEquals(2_000L to 2_500L, p[2])
    }

    @Test
    fun newsQualityAndSentiment() {
        assertFalse(NewsQuality.accept("XRP price prediction: could reach $10"))
        assertFalse(NewsQuality.accept("Bitcoin up"))
        assertTrue(NewsQuality.accept("Fed holds rates steady as inflation cools"))
        assertTrue(Sentiment.score("Bitcoin surges to record high as inflows jump") > 0)
        assertTrue(Sentiment.score("Stocks plunge as recession fears grow") < 0)
    }

    @Test
    fun clusteringGroupsTheSameEvent() {
        fun item(t: String, pub: String, tier: Int) = NewsItem(t, "https://x/$pub/${t.hashCode()}", pub, tier, NewsCategory.MARKETS, 1L, "", 0.0)
        val stories = StoryClusterer.cluster(
            listOf(
                item("Federal Reserve holds interest rates steady in November meeting", "CNBC", 2),
                item("Federal Reserve holds interest rates steady, signals patience", "Bloomberg", 1),
                item("Ethereum developers schedule next network upgrade", "The Block", 3),
            ),
        )
        assertEquals(2, stories.size)
        val fed = stories.first { it.lead.title.contains("Federal") }
        assertEquals("Bloomberg", fed.lead.publisher) // highest tier leads
        assertEquals(2, fed.sources)
    }
}

class DateTest {
    @org.junit.Test
    fun rfc822WithLetterZone() {
        val t = com.nazmiefearmutcu.flowmap.mobile.data.news.FeedParser.parseDate("Fri, 13 Nov 2026 09:00:00 Z")
        org.junit.Assert.assertEquals(1794560400000L, t)
        org.junit.Assert.assertEquals(0L, com.nazmiefearmutcu.flowmap.mobile.data.news.FeedParser.parseDate("not a date"))
    }
}
