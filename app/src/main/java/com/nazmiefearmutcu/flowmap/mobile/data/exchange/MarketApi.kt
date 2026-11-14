package com.nazmiefearmutcu.flowmap.mobile.data.exchange

import com.nazmiefearmutcu.flowmap.mobile.data.BookSnapshot
import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.data.FundingPoint
import com.nazmiefearmutcu.flowmap.mobile.data.Interval
import com.nazmiefearmutcu.flowmap.mobile.data.OiPoint
import com.nazmiefearmutcu.flowmap.mobile.data.RatioKind
import com.nazmiefearmutcu.flowmap.mobile.data.RatioPoint
import com.nazmiefearmutcu.flowmap.mobile.data.Ticker
import com.nazmiefearmutcu.flowmap.mobile.data.Venue

/**
 * A normalized, keyless view of one derivatives venue — the mobile counterpart of Crocodile's
 * exchange adapters. Every list is returned in ascending time order.
 */
interface MarketApi {
    val venue: Venue

    suspend fun candles(symbol: String, interval: Interval, startMs: Long, endMs: Long): List<Candle>

    /** Open-interest history valued in USD. Venues keep only a limited window (Binance: 30 days). */
    suspend fun openInterest(symbol: String, interval: Interval, startMs: Long, endMs: Long): List<OiPoint>

    suspend fun tickers(): List<Ticker>

    suspend fun fundingHistory(symbol: String, limit: Int = 200): List<FundingPoint>

    /** Ratio kinds this venue publishes. */
    val ratioKinds: List<RatioKind>

    suspend fun longShort(symbol: String, kind: RatioKind, period: Interval, limit: Int = 200): List<RatioPoint>

    suspend fun depthSnapshot(symbol: String): BookSnapshot

    /** Intervals the venue accepts for open-interest and ratio statistics. */
    fun statInterval(wanted: Interval): Interval
}

object Venues {
    val binance: MarketApi by lazy { BinanceFutures() }
    val bybit: MarketApi by lazy { BybitLinear() }
    fun api(v: Venue): MarketApi = if (v == Venue.BINANCE) binance else bybit
}

/** Splits [startMs, endMs] into pages of at most [pageSize] intervals. */
internal fun pages(startMs: Long, endMs: Long, stepMs: Long, pageSize: Int): List<Pair<Long, Long>> {
    val out = ArrayList<Pair<Long, Long>>()
    var s = startMs
    val span = stepMs * pageSize
    while (s < endMs) {
        val e = minOf(endMs, s + span - 1)
        out += s to e
        s = e + 1
    }
    return out
}
