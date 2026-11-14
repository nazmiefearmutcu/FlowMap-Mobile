package com.nazmiefearmutcu.flowmap.mobile.data.live

import com.nazmiefearmutcu.flowmap.mobile.data.Liquidation
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import com.nazmiefearmutcu.flowmap.mobile.data.stream.BinanceStreams
import com.nazmiefearmutcu.flowmap.mobile.data.stream.BybitStreams
import com.nazmiefearmutcu.flowmap.mobile.data.stream.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LiqTotals(val longUsd: Double = 0.0, val shortUsd: Double = 0.0, val count: Int = 0, val largest: Liquidation? = null) {
    val totalUsd: Double get() = longUsd + shortUsd
}

data class LiqFeedState(
    val since: Long = System.currentTimeMillis(),
    val recent: List<Liquidation> = emptyList(),
    val totals: LiqTotals = LiqTotals(),
    /** Per-minute long/short notional for the last 60 minutes, oldest first. */
    val minuteLong: DoubleArray = DoubleArray(60),
    val minuteShort: DoubleArray = DoubleArray(60),
    val bySymbol: List<Pair<String, LiqTotals>> = emptyList(),
    val connected: Map<Venue, Boolean> = emptyMap(),
    /** True once the feed has been paused (app in background): totals then exclude that time. */
    val hadPause: Boolean = false,
)

/**
 * Live forced-liquidation feed: Binance's all-market stream plus Bybit for the most active symbols.
 * Venues publish liquidations live only (Binance at most one per symbol per second), so the totals
 * cover the time since the feed started — they are never back-filled or extrapolated.
 */
class LiquidationHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val recent = ArrayDeque<Liquidation>()
    private val totals = HashMap<String, LiqTotals>()
    private var all = LiqTotals()
    private val since = System.currentTimeMillis()
    private val connected = HashMap<Venue, Boolean>()
    private var job: Job? = null
    private var dirty = false
    private var hadPause = false

    private val _state = MutableStateFlow(LiqFeedState(since = since))
    val state: StateFlow<LiqFeedState> = _state.asStateFlow()

    private val bybitSymbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT", "BNBUSDT", "ADAUSDT", "SUIUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT", "1000PEPEUSDT")

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { BinanceStreams.allLiquidations().collect { onEvent(Venue.BINANCE, it) } }
            launch { BybitStreams.liquidations(bybitSymbols).collect { onEvent(Venue.BYBIT, it) } }
            while (isActive) {
                delay(500)
                if (dirty) publish()
            }
        }
    }

    /** Stops the streams; totals are kept and resume counting on the next [start]. */
    fun stop() {
        job?.cancel()
        job = null
        synchronized(lock) { connected.clear(); dirty = true; hadPause = true }
        publish()
    }

    private fun onEvent(v: Venue, ev: StreamEvent) {
        when (ev) {
            is StreamEvent.Status -> synchronized(lock) { connected[v] = ev.connected; dirty = true }
            is StreamEvent.Liq -> synchronized(lock) {
                val l = ev.liq
                recent.addFirst(l)
                while (recent.size > 1500) recent.removeLast()
                all = all.add(l)
                totals[l.symbol] = (totals[l.symbol] ?: LiqTotals()).add(l)
                dirty = true
            }
            else -> Unit
        }
    }

    private fun LiqTotals.add(l: Liquidation) = LiqTotals(
        longUsd = longUsd + if (l.longLiquidated) l.usd else 0.0,
        shortUsd = shortUsd + if (l.longLiquidated) 0.0 else l.usd,
        count = count + 1,
        largest = if (largest == null || l.usd > largest.usd) l else largest,
    )

    private fun publish() {
        val now = System.currentTimeMillis()
        val s = synchronized(lock) {
            dirty = false
            val ml = DoubleArray(60)
            val ms = DoubleArray(60)
            val nowMin = now / 60_000
            for (l in recent) {
                val ago = (nowMin - l.time / 60_000).toInt()
                if (ago !in 0..59) continue
                if (l.longLiquidated) ml[59 - ago] += l.usd else ms[59 - ago] += l.usd
            }
            LiqFeedState(
                since = since,
                recent = recent.take(300),
                totals = all,
                minuteLong = ml,
                minuteShort = ms,
                bySymbol = totals.entries.sortedByDescending { it.value.totalUsd }.take(30).map { it.key to it.value },
                connected = HashMap(connected),
                hadPause = hadPause,
            )
        }
        _state.value = s
    }
}
