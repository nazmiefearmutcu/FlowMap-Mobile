package com.nazmiefearmutcu.flowmap.mobile.data.exchange

import com.nazmiefearmutcu.flowmap.mobile.data.BookSnapshot
import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.data.FundingPoint
import com.nazmiefearmutcu.flowmap.mobile.data.Http
import com.nazmiefearmutcu.flowmap.mobile.data.Interval
import com.nazmiefearmutcu.flowmap.mobile.data.OiPoint
import com.nazmiefearmutcu.flowmap.mobile.data.RatioKind
import com.nazmiefearmutcu.flowmap.mobile.data.RatioPoint
import com.nazmiefearmutcu.flowmap.mobile.data.Ticker
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** Binance USDⓈ-M futures public REST API. */
class BinanceFutures : MarketApi {
    override val venue = Venue.BINANCE
    private val base = "https://fapi.binance.com"

    override suspend fun candles(symbol: String, interval: Interval, startMs: Long, endMs: Long): List<Candle> {
        val out = ArrayList<Candle>()
        for ((s, e) in pages(startMs, endMs, interval.ms, 1500)) {
            val body = Http.get("$base/fapi/v1/klines?symbol=$symbol&interval=${interval.binance}&startTime=$s&endTime=$e&limit=1500")
            out += BinanceParse.klines(body)
        }
        return out.distinctBy { it.t }.sortedBy { it.t }
    }

    override suspend fun openInterest(symbol: String, interval: Interval, startMs: Long, endMs: Long): List<OiPoint> {
        val period = statInterval(interval)
        // Binance serves only the latest 30 days of open-interest statistics.
        val floor = System.currentTimeMillis() - 29L * 86_400_000L
        val s0 = maxOf(startMs, floor)
        if (s0 >= endMs) return emptyList()
        val out = ArrayList<OiPoint>()
        for ((s, e) in pages(s0, endMs, period.ms, 500)) {
            val body = Http.get("$base/futures/data/openInterestHist?symbol=$symbol&period=${period.binanceOi}&startTime=$s&endTime=$e&limit=500")
            out += BinanceParse.openInterest(body)
        }
        return out.distinctBy { it.t }.sortedBy { it.t }
    }

    override suspend fun tickers(): List<Ticker> = coroutineScope {
        val t24 = async { Http.get("$base/fapi/v1/ticker/24hr") }
        val prem = async { Http.get("$base/fapi/v1/premiumIndex") }
        BinanceParse.tickers(t24.await(), prem.await())
    }

    override suspend fun fundingHistory(symbol: String, limit: Int): List<FundingPoint> =
        BinanceParse.funding(Http.get("$base/fapi/v1/fundingRate?symbol=$symbol&limit=$limit"))

    override val ratioKinds = RatioKind.entries.toList()

    override suspend fun longShort(symbol: String, kind: RatioKind, period: Interval, limit: Int): List<RatioPoint> {
        val p = statInterval(period).binanceOi
        val path = when (kind) {
            RatioKind.GLOBAL_ACCOUNTS -> "globalLongShortAccountRatio"
            RatioKind.TOP_ACCOUNTS -> "topLongShortAccountRatio"
            RatioKind.TOP_POSITIONS -> "topLongShortPositionRatio"
            RatioKind.TAKER_VOLUME -> "takerlongshortRatio"
        }
        val body = Http.get("$base/futures/data/$path?symbol=$symbol&period=$p&limit=${limit.coerceAtMost(500)}")
        return if (kind == RatioKind.TAKER_VOLUME) BinanceParse.takerRatio(body) else BinanceParse.accountRatio(body)
    }

    override suspend fun depthSnapshot(symbol: String): BookSnapshot =
        BinanceParse.depth(Http.get("$base/fapi/v1/depth?symbol=$symbol&limit=1000"))

    override fun statInterval(wanted: Interval): Interval =
        if (wanted.binanceOi != null) wanted
        else Interval.entries.firstOrNull { it.binanceOi != null && it.ms >= wanted.ms } ?: Interval.D1
}

/** Pure parsers, unit-tested against recorded payloads. */
object BinanceParse {
    fun klines(body: String): List<Candle> {
        val arr = JSONArray(body)
        return List(arr.length()) { i ->
            val k = arr.getJSONArray(i)
            Candle(
                t = k.getLong(0),
                o = k.getString(1).toDouble(),
                h = k.getString(2).toDouble(),
                l = k.getString(3).toDouble(),
                c = k.getString(4).toDouble(),
                volume = k.getString(5).toDouble(),
                quoteVolume = k.getString(7).toDouble(),
                takerBuyQuote = k.getString(10).toDouble(),
            )
        }
    }

    fun openInterest(body: String): List<OiPoint> {
        val arr = JSONArray(body)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            OiPoint(o.getLong("timestamp"), o.getString("sumOpenInterestValue").toDouble())
        }
    }

    fun tickers(t24Body: String, premiumBody: String): List<Ticker> {
        val prem = HashMap<String, JSONObject>()
        val pa = JSONArray(premiumBody)
        for (i in 0 until pa.length()) pa.getJSONObject(i).let { prem[it.getString("symbol")] = it }
        val arr = JSONArray(t24Body)
        val out = ArrayList<Ticker>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sym = o.getString("symbol")
            if (!sym.endsWith("USDT") || sym.contains('_')) continue
            val p = prem[sym]
            out += Ticker(
                venue = Venue.BINANCE,
                symbol = sym,
                last = o.optString("lastPrice").toDoubleOrNull() ?: continue,
                changePct = o.optString("priceChangePercent").toDoubleOrNull() ?: 0.0,
                quoteVolume = o.optString("quoteVolume").toDoubleOrNull() ?: 0.0,
                high = o.optString("highPrice").toDoubleOrNull() ?: 0.0,
                low = o.optString("lowPrice").toDoubleOrNull() ?: 0.0,
                fundingRate = p?.optString("lastFundingRate")?.toDoubleOrNull() ?: Double.NaN,
                nextFundingMs = p?.optLong("nextFundingTime") ?: 0L,
            )
        }
        return out
    }

    fun funding(body: String): List<FundingPoint> {
        val arr = JSONArray(body)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            FundingPoint(o.getLong("fundingTime"), o.getString("fundingRate").toDouble())
        }.sortedBy { it.t }
    }

    fun accountRatio(body: String): List<RatioPoint> {
        val arr = JSONArray(body)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            RatioPoint(o.getLong("timestamp"), o.getString("longAccount").toDouble(), o.getString("shortAccount").toDouble())
        }.sortedBy { it.t }
    }

    fun takerRatio(body: String): List<RatioPoint> {
        val arr = JSONArray(body)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            RatioPoint(o.getLong("timestamp"), o.getString("buyVol").toDouble(), o.getString("sellVol").toDouble())
        }.sortedBy { it.t }
    }

    fun depth(body: String): BookSnapshot {
        val o = JSONObject(body)
        return BookSnapshot(o.getLong("lastUpdateId"), levels(o.getJSONArray("bids")), levels(o.getJSONArray("asks")))
    }

    fun levels(arr: JSONArray): List<Pair<Double, Double>> =
        List(arr.length()) { i -> arr.getJSONArray(i).let { it.getString(0).toDouble() to it.getString(1).toDouble() } }
}
