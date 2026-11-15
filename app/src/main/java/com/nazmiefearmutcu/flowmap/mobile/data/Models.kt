package com.nazmiefearmutcu.flowmap.mobile.data

/** Derivatives venues FlowMap Mobile reads directly (public, keyless endpoints). */
enum class Venue(val label: String, val short: String) {
    BINANCE("Binance USDⓈ-M", "Binance"),
    BYBIT("Bybit Linear", "Bybit");

    val other: Venue get() = if (this == BINANCE) BYBIT else BINANCE
}

/** Candle/statistics interval. [binance] and [bybit] are each venue's own code; null = unsupported. */
enum class Interval(val ms: Long, val label: String, val binance: String, val bybit: String, val bybitOi: String?, val binanceOi: String?) {
    M1(60_000L, "1m", "1m", "1", null, null),
    M3(180_000L, "3m", "3m", "3", null, null),
    M5(300_000L, "5m", "5m", "5", "5min", "5m"),
    M15(900_000L, "15m", "15m", "15", "15min", "15m"),
    M30(1_800_000L, "30m", "30m", "30", "30min", "30m"),
    H1(3_600_000L, "1h", "1h", "60", "1h", "1h"),
    H2(7_200_000L, "2h", "2h", "120", null, "2h"),
    H4(14_400_000L, "4h", "4h", "240", "4h", "4h"),
    H6(21_600_000L, "6h", "6h", "360", null, "6h"),
    H12(43_200_000L, "12h", "12h", "720", null, "12h"),
    D1(86_400_000L, "1d", "1d", "D", "1d", "1d");
}

data class Candle(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val volume: Double,
    val quoteVolume: Double,
    /** Taker-buy quote volume; NaN when the venue does not publish it. */
    val takerBuyQuote: Double = Double.NaN,
)

/** Open interest valued in USD at the point in time. */
data class OiPoint(val t: Long, val usd: Double)

data class Ticker(
    val venue: Venue,
    val symbol: String,
    val last: Double,
    val changePct: Double,
    val quoteVolume: Double,
    val high: Double,
    val low: Double,
    val fundingRate: Double = Double.NaN,
    val nextFundingMs: Long = 0L,
    val openInterestUsd: Double = Double.NaN,
) {
    val base: String get() = symbol.removeSuffix("USDT").removeSuffix("USDC")
}

data class FundingPoint(val t: Long, val rate: Double)

enum class RatioKind(val label: String, val detail: String) {
    GLOBAL_ACCOUNTS("Global accounts", "Share of all accounts holding net long vs net short positions"),
    TOP_ACCOUNTS("Top trader accounts", "Top 20% of traders by margin balance, counted by account"),
    TOP_POSITIONS("Top trader positions", "Top 20% of traders by margin balance, weighted by position size"),
    TAKER_VOLUME("Taker buy / sell", "Aggressive buy volume vs aggressive sell volume"),
}

data class RatioPoint(val t: Long, val long: Double, val short: Double) {
    val ratio: Double get() = if (short > 0) long / short else Double.NaN
    val longPct: Double get() = if (long + short > 0) long / (long + short) * 100 else 50.0
}

/** A forced liquidation. [longLiquidated] = a long position was closed by a sell order. */
data class Liquidation(
    val venue: Venue,
    val symbol: String,
    val longLiquidated: Boolean,
    val price: Double,
    val qty: Double,
    val time: Long,
) {
    val usd: Double get() = price * qty
}

data class Trade(val t: Long, val price: Double, val qty: Double, val buyerIsTaker: Boolean) {
    val usd: Double get() = price * qty
}

data class BookSnapshot(val lastUpdateId: Long, val bids: List<Pair<Double, Double>>, val asks: List<Pair<Double, Double>>)

data class FearGreed(val value: Int, val label: String, val t: Long)

/** How a number on screen was obtained — FlowMap never presents an estimate as an observation. */
enum class Provenance(val badge: String, val explain: String) {
    LIVE_L2("L2 LIVE", "Incremental order book streamed from the venue"),
    LIVE("LIVE", "Streamed directly from the venue"),
    REST("REST", "Polled from the venue's public API"),
    ESTIMATE("ESTIMATE", "Model output computed on this device from candles and open interest — not observed orders"),
}
