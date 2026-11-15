package com.nazmiefearmutcu.flowmap.mobile.data.stream

import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.data.Http
import com.nazmiefearmutcu.flowmap.mobile.data.Liquidation
import com.nazmiefearmutcu.flowmap.mobile.data.Trade
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

sealed interface StreamEvent {
    /** Order-book update. [snapshot] replaces the book; otherwise levels with qty 0 are removed. */
    data class Depth(
        val snapshot: Boolean,
        val firstId: Long,
        val lastId: Long,
        val prevId: Long,
        val bids: List<Pair<Double, Double>>,
        val asks: List<Pair<Double, Double>>,
        val time: Long,
    ) : StreamEvent

    data class Trades(val list: List<Trade>) : StreamEvent
    data class Liq(val liq: Liquidation) : StreamEvent
    data class Kline(val candle: Candle, val closed: Boolean) : StreamEvent
    /** Connection change. [aux] marks a secondary socket (Binance trades/klines) that does not carry the book. */
    data class Status(val connected: Boolean, val message: String? = null, val aux: Boolean = false) : StreamEvent
}

/**
 * One WebSocket with automatic reconnection (exponential back-off, capped at 30 s).
 * [subscribe] messages are sent on every (re)connect; [ping] is sent every 20 s when given.
 * When [silenceMs] > 0, a socket that delivers no message for that long is treated as dead and
 * reopened: a venue that silently re-routes a stream must never keep reading as "live".
 */
fun webSocketFlow(
    url: String,
    subscribe: List<String> = emptyList(),
    ping: String? = null,
    silenceMs: Long = 0L,
    parse: (String) -> List<StreamEvent>,
): Flow<StreamEvent> = flow {
    var backoff = 1_000L
    while (true) {
        var opened = false
        val session = callbackFlow {
            val lastMessage = AtomicLong(System.currentTimeMillis())
            val ws = Http.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    lastMessage.set(System.currentTimeMillis())
                    subscribe.forEach { webSocket.send(it) }
                    trySend(StreamEvent.Status(true))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastMessage.set(System.currentTimeMillis())
                    val events = try {
                        parse(text)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    events.forEach { trySend(it) }
                }

                // The server's close frame (Binance closes every 24 h) must be answered, otherwise
                // onClosed never fires and the session hangs until a ping times out.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    trySend(StreamEvent.Status(false, "Closed by venue: $reason"))
                    close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    trySend(StreamEvent.Status(false, "Closed: $reason"))
                    close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val msg = response?.let { "HTTP ${it.code}" } ?: (t.message ?: "connection lost")
                    trySend(StreamEvent.Status(false, msg))
                    close()
                }
            })
            val pinger = ping?.let { p ->
                launch {
                    while (isActive) {
                        delay(20_000)
                        ws.send(p)
                    }
                }
            }
            val watchdog = if (silenceMs > 0) launch {
                while (isActive) {
                    delay(minOf(5_000L, silenceMs))
                    if (System.currentTimeMillis() - lastMessage.get() > silenceMs) {
                        trySend(StreamEvent.Status(false, "No data for ${silenceMs / 1000} s; reconnecting"))
                        close()
                        break
                    }
                }
            } else null
            awaitClose {
                pinger?.cancel()
                watchdog?.cancel()
                ws.cancel()
            }
        }.buffer(Channel.UNLIMITED) // never drop book diffs while the collector is busy
        session.collect { ev ->
            if (ev is StreamEvent.Status && ev.connected) {
                opened = true
                backoff = 1_000L
            }
            emit(ev)
        }
        if (!opened) backoff = (backoff * 2).coerceAtMost(30_000L)
        delay(backoff)
    }
}

/** Binance USDⓈ-M market streams. */
object BinanceStreams {
    private const val BASE = "wss://fstream.binance.com"

    /**
     * Binance routes order-book diffs on the original endpoint but trades, klines and liquidations
     * only under `/market` (verified 2026-11-15: the old path stays open but silent for them).
     * Two sockets are merged; only the depth socket's status drives the book's sync state.
     */
    fun market(symbol: String): Flow<StreamEvent> {
        val s = symbol.lowercase()
        // Limits are generous because both streams only push on change: a thin market can go quiet
        // for a while, and a needless reconnect costs a resync. A truly dead route is still caught.
        val depth = webSocketFlow("$BASE/stream?streams=$s@depth@100ms", silenceMs = 60_000L, parse = ::parseCombined)
        val trades = webSocketFlow("$BASE/market/stream?streams=$s@aggTrade/$s@kline_1m", silenceMs = 10 * 60_000L, parse = ::parseCombined)
            .map { if (it is StreamEvent.Status) it.copy(aux = true) else it }
        return merge(depth, trades)
    }

    /** Liquidations can pause for minutes in a calm market, so the silence limit is generous. */
    fun allLiquidations(): Flow<StreamEvent> = webSocketFlow("$BASE/market/ws/!forceOrder@arr", silenceMs = 15 * 60_000L, parse = ::parseCombined)

    fun parseCombined(text: String): List<StreamEvent> {
        val root = JSONObject(text)
        val d = root.optJSONObject("data") ?: root
        return when (d.optString("e")) {
            "depthUpdate" -> listOf(
                StreamEvent.Depth(
                    snapshot = false,
                    firstId = d.getLong("U"),
                    lastId = d.getLong("u"),
                    prevId = d.optLong("pu", -1L),
                    bids = levels(d.getJSONArray("b")),
                    asks = levels(d.getJSONArray("a")),
                    time = d.optLong("E"),
                ),
            )
            "aggTrade" -> listOf(
                StreamEvent.Trades(listOf(Trade(d.getLong("T"), d.getString("p").toDouble(), d.getString("q").toDouble(), !d.getBoolean("m")))),
            )
            "kline" -> {
                val k = d.getJSONObject("k")
                listOf(
                    StreamEvent.Kline(
                        Candle(
                            t = k.getLong("t"), o = k.getString("o").toDouble(), h = k.getString("h").toDouble(),
                            l = k.getString("l").toDouble(), c = k.getString("c").toDouble(), volume = k.getString("v").toDouble(),
                            quoteVolume = k.getString("q").toDouble(), takerBuyQuote = k.getString("Q").toDouble(),
                        ),
                        k.getBoolean("x"),
                    ),
                )
            }
            "forceOrder" -> {
                val o = d.getJSONObject("o")
                // Coin-margined contracts (BTCUSD_PERP) count quantity in contracts, not coins: skip them.
                if (o.getString("s").contains('_')) return emptyList()
                val price = o.optString("ap").toDoubleOrNull()?.takeIf { it > 0 } ?: o.getString("p").toDouble()
                val qty = o.optString("z").toDoubleOrNull()?.takeIf { it > 0 } ?: o.getString("q").toDouble()
                listOf(StreamEvent.Liq(Liquidation(Venue.BINANCE, o.getString("s"), o.getString("S") == "SELL", price, qty, o.getLong("T"))))
            }
            else -> emptyList()
        }
    }

    private fun levels(a: JSONArray) = List(a.length()) { i -> a.getJSONArray(i).let { it.getString(0).toDouble() to it.getString(1).toDouble() } }
}

/** Bybit V5 public linear streams. */
object BybitStreams {
    private const val URL = "wss://stream.bybit.com/v5/public/linear"
    private const val PING = "{\"op\":\"ping\"}"

    private fun sub(topics: List<String>) = "{\"op\":\"subscribe\",\"args\":[" + topics.joinToString(",") { "\"$it\"" } + "]}"

    fun market(symbol: String): Flow<StreamEvent> =
        webSocketFlow(URL, listOf(sub(listOf("orderbook.200.$symbol", "publicTrade.$symbol", "kline.1.$symbol"))), PING, 60_000L, ::parse)

    fun liquidations(symbols: List<String>): Flow<StreamEvent> =
        // One topic per request: a single unknown symbol would make Bybit reject a whole batch.
        // Bybit answers every ping with a pong, so 90 s of silence means the link is dead.
        webSocketFlow(URL, symbols.map { sub(listOf("allLiquidation.$it")) }, PING, 90_000L, ::parse)

    fun parse(text: String): List<StreamEvent> {
        val root = JSONObject(text)
        val topic = root.optString("topic")
        return when {
            topic.startsWith("orderbook.") -> {
                val d = root.getJSONObject("data")
                val u = d.optLong("u")
                listOf(
                    StreamEvent.Depth(
                        snapshot = root.optString("type") == "snapshot" || u == 1L,
                        firstId = u, lastId = u, prevId = -1L,
                        bids = levels(d.getJSONArray("b")), asks = levels(d.getJSONArray("a")),
                        time = root.optLong("ts"),
                    ),
                )
            }
            topic.startsWith("publicTrade.") -> {
                val arr = root.getJSONArray("data")
                listOf(StreamEvent.Trades(List(arr.length()) { i ->
                    arr.getJSONObject(i).let { Trade(it.getLong("T"), it.getString("p").toDouble(), it.getString("v").toDouble(), it.getString("S") == "Buy") }
                }))
            }
            topic.startsWith("kline.") -> {
                val arr = root.getJSONArray("data")
                List(arr.length()) { i ->
                    val k = arr.getJSONObject(i)
                    StreamEvent.Kline(
                        Candle(
                            t = k.getLong("start"), o = k.getString("open").toDouble(), h = k.getString("high").toDouble(),
                            l = k.getString("low").toDouble(), c = k.getString("close").toDouble(), volume = k.getString("volume").toDouble(),
                            quoteVolume = k.getString("turnover").toDouble(),
                        ),
                        k.optBoolean("confirm"),
                    )
                }
            }
            topic.startsWith("allLiquidation.") -> {
                val arr = root.getJSONArray("data")
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    // Bybit: side "Buy" means a long position was liquidated.
                    StreamEvent.Liq(Liquidation(Venue.BYBIT, o.getString("s"), o.getString("S") == "Buy", o.getString("p").toDouble(), o.getString("v").toDouble(), o.getLong("T")))
                }
            }
            else -> emptyList()
        }
    }

    private fun levels(a: JSONArray) = List(a.length()) { i -> a.getJSONArray(i).let { it.getString(0).toDouble() to it.getString(1).toDouble() } }
}
