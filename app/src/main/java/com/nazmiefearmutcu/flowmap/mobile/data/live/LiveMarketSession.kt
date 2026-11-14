package com.nazmiefearmutcu.flowmap.mobile.data.live

import com.nazmiefearmutcu.flowmap.mobile.data.BookSnapshot
import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.data.Interval
import com.nazmiefearmutcu.flowmap.mobile.data.Trade
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import com.nazmiefearmutcu.flowmap.mobile.data.exchange.Venues
import com.nazmiefearmutcu.flowmap.mobile.data.stream.BinanceStreams
import com.nazmiefearmutcu.flowmap.mobile.data.stream.BybitStreams
import com.nazmiefearmutcu.flowmap.mobile.data.stream.StreamEvent
import com.nazmiefearmutcu.flowmap.mobile.data.userMessage
import com.nazmiefearmutcu.flowmap.mobile.engine.DepthHeatmap
import com.nazmiefearmutcu.flowmap.mobile.engine.OrderBook
import com.nazmiefearmutcu.flowmap.mobile.engine.TradeAccumulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LiveState(
    val venue: Venue,
    val symbol: String,
    val connected: Boolean = false,
    val synced: Boolean = false,
    val message: String? = null,
    val lastPrice: Double = Double.NaN,
    val bestBid: Double = Double.NaN,
    val bestAsk: Double = Double.NaN,
    val bids: List<Pair<Double, Double>> = emptyList(),
    val asks: List<Pair<Double, Double>> = emptyList(),
    val trades: List<Trade> = emptyList(),
    val candles: List<Candle> = emptyList(),
    val sessionStart: Long = System.currentTimeMillis(),
    /** Increments on every recorded depth column so the chart redraws. */
    val version: Long = 0L,
)

/**
 * Streams one market's order book, trades and 1-minute candles and records the liquidity heatmap.
 * Binance books are rebuilt from a REST snapshot plus `depthUpdate` events following the venue's
 * documented sequencing rules (U/u/pu); Bybit sends its own snapshot followed by deltas.
 */
class LiveMarketSession(val venue: Venue, val symbol: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val book = OrderBook()
    private val acc = TradeAccumulator()
    val depth = DepthHeatmap()

    private val _state = MutableStateFlow(LiveState(venue, symbol))
    val state: StateFlow<LiveState> = _state.asStateFlow()

    // Binance sequencing
    private var synced = false
    private var awaitingFirst = false
    private var snapshotId = -1L
    private var lastU = -1L
    private val pending = ArrayDeque<StreamEvent.Depth>()
    private var snapshotJob: Job? = null
    private var resyncs = 0

    private val recentTrades = ArrayDeque<Trade>()
    private var candles = ArrayList<Candle>()
    private var lastPrice = Double.NaN
    private var version = 0L

    private var job: Job? = null
    private var historyLoaded = false

    /** Starts (or resumes) streaming. Recorded depth is kept across pause/resume; the gap stays visible. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!historyLoaded) launch { loadHistory() }
            launch {
                val flow = if (venue == Venue.BINANCE) BinanceStreams.market(symbol) else BybitStreams.market(symbol)
                flow.collect { ev -> handle(ev) }
            }
            launch {
                while (isActive) {
                    delay(depth.sampleMs - System.currentTimeMillis() % depth.sampleMs)
                    sample()
                }
            }
        }
    }

    /** Stops the streams (app in background) without discarding the recorded heatmap. */
    fun pause() {
        job?.cancel()
        job = null
        synchronized(lock) { synced = false; pending.clear(); snapshotJob?.cancel(); snapshotJob = null; acc.reset() }
        _state.value = _state.value.copy(connected = false, synced = false, message = "Paused")
    }

    fun stop() = scope.cancel()

    private suspend fun loadHistory() {
        try {
            val now = System.currentTimeMillis()
            val list = Venues.api(venue).candles(symbol, Interval.M1, now - 12 * 3_600_000L, now)
            historyLoaded = true
            synchronized(lock) {
                val live = candles.filter { c -> list.none { it.t == c.t } }
                candles = ArrayList((list + live).sortedBy { it.t })
            }
            publish()
        } catch (e: Exception) {
            _state.value = _state.value.copy(message = "History unavailable: ${e.userMessage()}")
        }
    }

    private fun handle(ev: StreamEvent) {
        when (ev) {
            is StreamEvent.Status -> {
                if (!ev.connected) synchronized(lock) { synced = false; pending.clear() }
                _state.value = _state.value.copy(connected = ev.connected, message = ev.message)
            }
            is StreamEvent.Depth -> onDepth(ev)
            is StreamEvent.Trades -> synchronized(lock) {
                for (t in ev.list) {
                    acc.add(t.price, t.qty, t.buyerIsTaker)
                    recentTrades.addFirst(t)
                    lastPrice = t.price
                }
                while (recentTrades.size > 80) recentTrades.removeLast()
            }
            is StreamEvent.Kline -> synchronized(lock) {
                val c = ev.candle
                val i = candles.indexOfLast { it.t == c.t }
                if (i >= 0) candles[i] = c else if (candles.isEmpty() || c.t > candles.last().t) candles.add(c)
                if (candles.size > 2000) candles.subList(0, candles.size - 2000).clear()
            }
            is StreamEvent.Liq -> Unit
        }
    }

    private fun onDepth(e: StreamEvent.Depth) = synchronized(lock) {
        if (e.snapshot) { // Bybit
            book.replace(e.bids, e.asks)
            synced = true
            return@synchronized
        }
        if (venue == Venue.BYBIT) {
            if (synced) book.apply(e.bids, e.asks)
            return@synchronized
        }
        if (!synced) {
            pending.addLast(e)
            if (pending.size > 4000) pending.removeFirst()
            requestSnapshot()
            return@synchronized
        }
        onDepthUnlocked(e)
    }

    private fun resync(e: StreamEvent.Depth) {
        synced = false
        pending.clear()
        pending.addLast(e)
        resyncs++
        requestSnapshot()
    }

    private fun requestSnapshot() {
        if (snapshotJob?.isActive == true) return
        snapshotJob = scope.launch {
            if (resyncs > 1) delay(minOf(5_000L, 500L * resyncs))
            try {
                val snap = Venues.api(venue).depthSnapshot(symbol)
                synchronized(lock) { applySnapshot(snap) }
            } catch (ex: Exception) {
                _state.value = _state.value.copy(message = "Order book snapshot failed: ${ex.userMessage()}")
                delay(3_000)
                snapshotJob = null
                synchronized(lock) { if (!synced) requestSnapshot() }
            }
        }
    }

    private fun applySnapshot(s: BookSnapshot) {
        book.replace(s.bids, s.asks)
        snapshotId = s.lastUpdateId
        lastU = s.lastUpdateId
        awaitingFirst = true
        synced = true
        val queued = pending.toList()
        pending.clear()
        for (e in queued) {
            if (!synced) { pending.addLast(e); continue }
            onDepthUnlocked(e)
        }
    }

    private fun onDepthUnlocked(e: StreamEvent.Depth) {
        if (awaitingFirst) {
            if (e.lastId < snapshotId) return
            if (e.firstId > snapshotId) { resync(e); return }
            awaitingFirst = false
        } else if (e.prevId != lastU) {
            resync(e); return
        }
        book.apply(e.bids, e.asks)
        lastU = e.lastId
    }

    private fun sample() {
        synchronized(lock) {
            if (!synced || book.bids.isEmpty() || book.asks.isEmpty()) return
            depth.record(System.currentTimeMillis(), book.bids, book.asks, book.bestBid, book.bestAsk, acc)
            acc.reset()
            version++
        }
        publish()
    }

    private fun publish() {
        val s = synchronized(lock) {
            val (b, a) = book.ladder(40, 0.0)
            _state.value.copy(
                synced = synced,
                lastPrice = if (lastPrice.isNaN()) book.mid else lastPrice,
                bestBid = book.bestBid,
                bestAsk = book.bestAsk,
                bids = b,
                asks = a,
                trades = recentTrades.toList(),
                candles = candles.toList(),
                version = version,
                message = if (synced) null else _state.value.message,
            )
        }
        _state.value = s
    }
}
