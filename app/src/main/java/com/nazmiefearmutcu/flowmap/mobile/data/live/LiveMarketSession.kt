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
import com.nazmiefearmutcu.flowmap.mobile.engine.CandleHeatmap
import com.nazmiefearmutcu.flowmap.mobile.engine.DepthHeatmap
import com.nazmiefearmutcu.flowmap.mobile.engine.OrderBook
import com.nazmiefearmutcu.flowmap.mobile.engine.TradeAccumulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    /** Problem with a secondary feed (trades/klines) while the order book itself is fine. */
    val notice: String? = null,
)

/**
 * Streams one market's order book, trades and 1-minute candles and records the liquidity heatmap.
 * Binance books are rebuilt from a REST snapshot plus `depthUpdate` events following the venue's
 * documented sequencing rules (U/u/pu); Bybit sends its own snapshot followed by deltas.
 *
 * Every write to [state] goes through [MutableStateFlow.update], so the stream thread, the sampler
 * and history loading never overwrite each other's fields.
 */
class LiveMarketSession(val venue: Venue, val symbol: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val book = OrderBook()
    private val acc = TradeAccumulator()
    val depth = DepthHeatmap()

    private val _state = MutableStateFlow(LiveState(venue, symbol))
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _backfill = MutableStateFlow<CandleHeatmap?>(null)
    /** Heatmap history before recording began, reconstructed from 1-minute candles (an estimate). */
    val backfill: StateFlow<CandleHeatmap?> = _backfill.asStateFlow()

    // Binance sequencing
    private var synced = false
    private var awaitingFirst = false
    private var snapshotId = -1L
    private var lastU = -1L
    private val pending = ArrayDeque<StreamEvent.Depth>()
    private var snapshotJob: Job? = null
    /** Consecutive resyncs without a successful apply; drives the snapshot back-off and resets on success. */
    private var resyncs = 0

    private val recentTrades = ArrayDeque<Trade>()
    private var candles = ArrayList<Candle>()
    private var lastPrice = Double.NaN
    private var version = 0L

    private var job: Job? = null
    /** Bumped by [pause]; work started before a pause must not apply its result after it. */
    private var generation = 0
    /** Start of the next candle back-fill: the newest candle held when a gap began (pause or outage). */
    private var backfillFrom: Long? = null
    /** True once the initial 12-hour history has loaded. */
    private var historyLoaded = false
    /** True while the secondary (trades/klines) socket is down, so its return triggers a back-fill. */
    private var auxDown = false
    private var auxNotice: String? = null
    /** True while the main socket is down, so its return re-fetches the minutes it missed. */
    private var mainDown = false

    /** Starts (or resumes) streaming. Recorded depth is kept across pause/resume; the gap stays visible. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // First start loads 12 h of 1-minute candles; a resume back-fills the minutes it missed.
            launch { loadHistory() }
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
        synchronized(lock) {
            generation++
            synced = false
            pending.clear()
            snapshotJob?.cancel()
            snapshotJob = null
            resyncs = 0
            acc.reset()
            // Only a gap after the first history load is a back-fill; before it, the 12 h load stays due.
            if (backfillFrom == null && historyLoaded) backfillFrom = candles.lastOrNull()?.t
        }
        _state.update { it.copy(connected = false, synced = false, message = "Paused") }
    }

    fun stop() = scope.cancel()

    /** Loads missing candles. [expectedGen] fences work queued before a pause (null = current). */
    private suspend fun loadHistory(expectedGen: Int? = null) {
        try {
            val now = System.currentTimeMillis()
            // Decided up front, before the kline stream can append the current minute.
            var gen = 0
            val from = synchronized(lock) {
                gen = expectedGen ?: generation
                if (gen != generation) return
                if (!historyLoaded) now - 12 * 3_600_000L else backfillFrom
            } ?: return
            if (now - from < 60_000L) { synchronized(lock) { backfillFrom = null }; return }
            val list = Venues.api(venue).candles(symbol, Interval.M1, from, now)
            synchronized(lock) {
                if (gen != generation) return // paused meanwhile: keep the back-fill marker for the resume
                // REST is final for every closed minute; only the minute still forming right now keeps
                // its streamed values (the stream may be a few hundred ms ahead of REST there).
                val currentMinute = System.currentTimeMillis() / 60_000L * 60_000L
                val merged = HashMap<Long, Candle>()
                for (c in candles) merged[c.t] = c
                for (f in list) if (f.t < currentMinute || f.t !in merged) merged[f.t] = f
                candles = ArrayList(merged.values.sortedBy { it.t })
                if (candles.size > 2000) candles.subList(0, candles.size - 2000).clear()
                backfillFrom = null
                historyLoaded = true
            }
            buildBackfill()
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = "History unavailable: ${e.userMessage()}") }
        }
    }

    /** Rebuilds the candle history up to the first recorded column (or now, before recording starts). */
    @Synchronized
    private fun buildBackfill() {
        val (cs, until) = synchronized(lock) { candles.toList() to (if (depth.cols > 0) depth.colTime(0) else System.currentTimeMillis()) }
        val prev = _backfill.value
        if (prev != null && prev.until == until) return
        _backfill.value = CandleHeatmap.from(cs, until)
    }

    private fun handle(ev: StreamEvent) {
        when (ev) {
            is StreamEvent.Status -> if (ev.aux) {
                // Trades/klines socket: the book is unaffected, but its outage must stay visible and its
                // missed minutes be re-fetched when it returns.
                if (!ev.connected) {
                    synchronized(lock) {
                        if (!auxDown) { auxDown = true; if (backfillFrom == null && historyLoaded) backfillFrom = candles.lastOrNull()?.t }
                        auxNotice = "Trade stream interrupted (${ev.message ?: "reconnecting"}); trades and candles resume when it reconnects."
                    }
                    _state.update { it.copy(notice = auxNotice) }
                } else {
                    val gen = synchronized(lock) { val was = auxDown; auxDown = false; auxNotice = null; if (was) generation else null }
                    _state.update { it.copy(notice = null) }
                    if (gen != null) scope.launch { loadHistory(gen) }
                }
            } else {
                if (!ev.connected) synchronized(lock) {
                    synced = false
                    pending.clear()
                    if (!mainDown) { mainDown = true; if (backfillFrom == null && historyLoaded) backfillFrom = candles.lastOrNull()?.t }
                } else {
                    // Bybit carries klines on this socket: re-fetch the minutes missed while it was down.
                    val gen = synchronized(lock) { val was = mainDown; mainDown = false; if (was) generation else null }
                    if (gen != null) scope.launch { loadHistory(gen) }
                }
                _state.update { it.copy(connected = ev.connected, synced = if (ev.connected) it.synced else false, message = ev.message) }
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

    /** Must be called with [lock] held. */
    private fun requestSnapshot() {
        if (snapshotJob?.isActive == true) return
        val gen = generation
        snapshotJob = scope.launch {
            if (resyncs > 1) delay(minOf(5_000L, 500L * resyncs))
            try {
                val snap = Venues.api(venue).depthSnapshot(symbol)
                synchronized(lock) {
                    if (gen != generation) return@synchronized // arrived after a pause: discard
                    // Clear our handle first so a gap found while replaying can request a new snapshot.
                    snapshotJob = null
                    applySnapshot(snap)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                if (synchronized(lock) { gen != generation }) return@launch // paused meanwhile
                _state.update { it.copy(message = "Order book snapshot failed: ${ex.userMessage()}") }
                delay(3_000)
                synchronized(lock) {
                    if (gen != generation) return@synchronized
                    snapshotJob = null
                    if (!synced) requestSnapshot()
                }
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
            resyncs = 0
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
        // The first recorded column fixes where reconstructed history must stop; once the ring starts
        // dropping its oldest columns, history is extended (every 5 min) so no blank band opens up.
        val bf = _backfill.value
        if (depth.cols == 1 || (bf != null && depth.cols > 0 && depth.colTime(0) - bf.until > 5 * 60_000L)) buildBackfill()
        publish()
    }

    private fun publish() {
        // Snapshot and publish under one lock, so two publishers can never land out of order.
        synchronized(lock) {
            val (b, a) = book.ladder(40)
            val synced = this.synced
            val lp = if (lastPrice.isNaN()) book.mid else lastPrice
            val bb = book.bestBid
            val ba = book.bestAsk
            val trades = recentTrades.toList()
            val cs = candles.toList()
            val v = version
            val notice = auxNotice
            _state.update {
                it.copy(
                    synced = synced, lastPrice = lp, bestBid = bb, bestAsk = ba, bids = b, asks = a,
                    trades = trades, candles = cs, version = v, notice = notice,
                    message = if (synced) null else it.message,
                )
            }
        }
    }
}
