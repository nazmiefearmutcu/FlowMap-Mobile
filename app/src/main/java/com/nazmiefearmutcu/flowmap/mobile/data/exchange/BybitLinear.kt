package com.nazmiefearmutcu.flowmap.mobile.data.exchange

import com.nazmiefearmutcu.flowmap.mobile.data.BookSnapshot
import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.data.FundingPoint
import com.nazmiefearmutcu.flowmap.mobile.data.Http
import com.nazmiefearmutcu.flowmap.mobile.data.HttpError
import com.nazmiefearmutcu.flowmap.mobile.data.Interval
import com.nazmiefearmutcu.flowmap.mobile.data.OiPoint
import com.nazmiefearmutcu.flowmap.mobile.data.RatioKind
import com.nazmiefearmutcu.flowmap.mobile.data.RatioPoint
import com.nazmiefearmutcu.flowmap.mobile.data.Ticker
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Bybit V5 linear (USDT perpetual) public REST API. */
class BybitLinear : MarketApi {
    override val venue = Venue.BYBIT
    private val base = "https://api.bybit.com"

    private suspend fun result(url: String): JSONObject {
        val o = JSONObject(Http.get(url))
        val code = o.optInt("retCode", -1)
        if (code != 0) throw HttpError(if (code == 10006) 429 else 400, url, o.optString("retMsg"))
        return o.getJSONObject("result")
    }

    override suspend fun candles(symbol: String, interval: Interval, startMs: Long, endMs: Long): List<Candle> {
        val out = ArrayList<Candle>()
        for ((s, e) in pages(startMs, endMs, interval.ms, 1000)) {
            val r = result("$base/v5/market/kline?category=linear&symbol=$symbol&interval=${interval.bybit}&start=$s&end=$e&limit=1000")
            out += BybitParse.klines(r.getJSONArray("list"))
        }
        return out.distinctBy { it.t }.sortedBy { it.t }
    }

    override suspend fun openInterest(symbol: String, interval: Interval, startMs: Long, endMs: Long): List<OiPoint> {
        val period = statInterval(interval)
        // Bybit reports open interest in coins; value it with the candle close at the same time.
        val raw = ArrayList<Pair<Long, Double>>()
        var cursor: String? = null
        var guard = 0
        do {
            val c = cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: ""
            val r = result("$base/v5/market/open-interest?category=linear&symbol=$symbol&intervalTime=${period.bybitOi}&startTime=$startMs&endTime=$endMs&limit=200$c")
            val list = r.getJSONArray("list")
            for (i in 0 until list.length()) list.getJSONObject(i).let { raw += it.getString("timestamp").toLong() to it.getString("openInterest").toDouble() }
            cursor = r.optString("nextPageCursor").takeIf { it.isNotBlank() && list.length() > 0 }
        } while (cursor != null && ++guard < 40)
        if (raw.isEmpty()) return emptyList()
        raw.sortBy { it.first }
        val prices = candles(symbol, period, raw.first().first, raw.last().first + period.ms)
        return BybitParse.valueOi(raw, prices)
    }

    override suspend fun tickers(): List<Ticker> =
        BybitParse.tickers(result("$base/v5/market/tickers?category=linear").getJSONArray("list"))

    override suspend fun fundingHistory(symbol: String, limit: Int): List<FundingPoint> {
        val list = result("$base/v5/market/funding/history?category=linear&symbol=$symbol&limit=${limit.coerceAtMost(200)}").getJSONArray("list")
        return List(list.length()) { i ->
            list.getJSONObject(i).let { FundingPoint(it.getString("fundingRateTimestamp").toLong(), it.getString("fundingRate").toDouble()) }
        }.sortedBy { it.t }
    }

    override val ratioKinds = listOf(RatioKind.GLOBAL_ACCOUNTS)

    override suspend fun longShort(symbol: String, kind: RatioKind, period: Interval, limit: Int): List<RatioPoint> {
        require(kind == RatioKind.GLOBAL_ACCOUNTS) { "Bybit publishes only the account ratio" }
        val p = statInterval(period).bybitOi
        val list = result("$base/v5/market/account-ratio?category=linear&symbol=$symbol&period=$p&limit=${limit.coerceAtMost(500)}").getJSONArray("list")
        return List(list.length()) { i ->
            list.getJSONObject(i).let { RatioPoint(it.getString("timestamp").toLong(), it.getString("buyRatio").toDouble(), it.getString("sellRatio").toDouble()) }
        }.sortedBy { it.t }
    }

    override suspend fun depthSnapshot(symbol: String): BookSnapshot {
        val r = result("$base/v5/market/orderbook?category=linear&symbol=$symbol&limit=200")
        return BookSnapshot(r.optLong("u"), BinanceParse.levels(r.getJSONArray("b")), BinanceParse.levels(r.getJSONArray("a")))
    }

    override fun statInterval(wanted: Interval): Interval =
        if (wanted.bybitOi != null) wanted
        else Interval.entries.firstOrNull { it.bybitOi != null && it.ms >= wanted.ms } ?: Interval.D1
}

object BybitParse {
    fun klines(list: JSONArray): List<Candle> = List(list.length()) { i ->
        val k = list.getJSONArray(i)
        Candle(
            t = k.getString(0).toLong(),
            o = k.getString(1).toDouble(),
            h = k.getString(2).toDouble(),
            l = k.getString(3).toDouble(),
            c = k.getString(4).toDouble(),
            volume = k.getString(5).toDouble(),
            quoteVolume = k.getString(6).toDouble(),
        )
    }

    /** Values coin-denominated OI with the close of the candle at or before each timestamp. */
    fun valueOi(raw: List<Pair<Long, Double>>, candles: List<Candle>): List<OiPoint> {
        if (candles.isEmpty()) return emptyList()
        var j = 0
        return raw.map { (t, oi) ->
            while (j + 1 < candles.size && candles[j + 1].t <= t) j++
            OiPoint(t, oi * candles[j].c)
        }
    }

    fun tickers(list: JSONArray): List<Ticker> {
        val out = ArrayList<Ticker>(list.length())
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val sym = o.getString("symbol")
            if (!sym.endsWith("USDT")) continue
            out += Ticker(
                venue = Venue.BYBIT,
                symbol = sym,
                last = o.optString("lastPrice").toDoubleOrNull() ?: continue,
                changePct = (o.optString("price24hPcnt").toDoubleOrNull() ?: 0.0) * 100.0,
                quoteVolume = o.optString("turnover24h").toDoubleOrNull() ?: 0.0,
                high = o.optString("highPrice24h").toDoubleOrNull() ?: 0.0,
                low = o.optString("lowPrice24h").toDoubleOrNull() ?: 0.0,
                fundingRate = o.optString("fundingRate").toDoubleOrNull() ?: Double.NaN,
                nextFundingMs = o.optString("nextFundingTime").toLongOrNull() ?: 0L,
                openInterestUsd = o.optString("openInterestValue").toDoubleOrNull() ?: Double.NaN,
            )
        }
        return out
    }
}
