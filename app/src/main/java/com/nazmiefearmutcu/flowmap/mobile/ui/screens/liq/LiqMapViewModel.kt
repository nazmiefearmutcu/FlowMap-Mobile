package com.nazmiefearmutcu.flowmap.mobile.ui.screens.liq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.Candle
import com.nazmiefearmutcu.flowmap.mobile.data.Interval
import com.nazmiefearmutcu.flowmap.mobile.data.OiPoint
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import com.nazmiefearmutcu.flowmap.mobile.data.exchange.Venues
import com.nazmiefearmutcu.flowmap.mobile.data.userMessage
import com.nazmiefearmutcu.flowmap.mobile.engine.LiqHeatmap
import com.nazmiefearmutcu.flowmap.mobile.engine.LiqHeatmapEngine
import com.nazmiefearmutcu.flowmap.mobile.engine.LiqModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TimeRange(val label: String, val ms: Long, val interval: Interval) {
    H12("12 h", 12 * 3_600_000L, Interval.M1),
    H24("24 h", 24 * 3_600_000L, Interval.M3),
    D3("3 d", 3 * 86_400_000L, Interval.M5),
    W1("1 w", 7 * 86_400_000L, Interval.M15),
    W2("2 w", 14 * 86_400_000L, Interval.M30),
    MO1("1 mo", 30 * 86_400_000L, Interval.H1),
    MO3("3 mo", 90 * 86_400_000L, Interval.H2),
    MO6("6 mo", 182 * 86_400_000L, Interval.H4),
    Y1("1 y", 365 * 86_400_000L, Interval.H12),
}

/** "Pair" = one venue's contract; "Symbol" = open interest of every supported venue combined. */
enum class Scope { PAIR, SYMBOL }

data class LiqMapUi(
    val symbol: String = "BTCUSDT",
    val venue: Venue = Venue.BINANCE,
    val scope: Scope = Scope.PAIR,
    val model: LiqModel = LiqModel.MODEL_1,
    val range: TimeRange = TimeRange.MO3,
    val loading: Boolean = true,
    val error: String? = null,
    val heatmap: LiqHeatmap? = null,
    /** Venues whose open interest went into the model. */
    val oiVenues: List<Venue> = emptyList(),
    val updatedAt: Long = 0L,
)

class LiqMapViewModel : ViewModel() {
    private val settings = AppGraph.settings
    private val _ui = MutableStateFlow(LiqMapUi(symbol = settings.symbol.value, venue = settings.venue.value))
    val ui: StateFlow<LiqMapUi> = _ui.asStateFlow()

    private var candles: List<Candle> = emptyList()
    private var oi: List<OiPoint> = emptyList()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(settings.symbol, settings.venue) { s, v -> s to v }.distinctUntilChanged().collect { (s, v) ->
                _ui.value = _ui.value.copy(symbol = s, venue = v)
                reload()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(120_000)
                if (!_ui.value.loading) reload(silent = true)
            }
        }
    }

    fun setScope(s: Scope) { if (s != _ui.value.scope) { _ui.value = _ui.value.copy(scope = s); reload() } }
    fun setRange(r: TimeRange) { if (r != _ui.value.range) { _ui.value = _ui.value.copy(range = r); reload() } }
    fun setModel(m: LiqModel) {
        if (m == _ui.value.model) return
        _ui.value = _ui.value.copy(model = m)
        rebuild()
    }

    fun reload(silent: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val st = _ui.value
            if (!silent) _ui.value = st.copy(loading = true, error = null)
            try {
                val now = System.currentTimeMillis()
                val start = now - st.range.ms
                val interval = st.range.interval
                val (used, cs) = AppGraph.withVenue(st.venue) { it.candles(st.symbol, interval, start, now) }
                if (cs.size < 2) throw IllegalStateException("No candles for ${st.symbol} on ${used.short}.")
                val venuesForOi = if (st.scope == Scope.PAIR) listOf(used) else Venue.entries.filter { it !in AppGraph.blocked.value }
                val series = venuesForOi.map { v ->
                    async {
                        v to try { Venues.api(v).openInterest(st.symbol, interval, start, now) } catch (_: Exception) { emptyList() }
                    }
                }.map { it.await() }
                candles = cs
                oi = LiqHeatmapEngine.mergeOi(series.map { it.second })
                _ui.value = _ui.value.copy(venue = used, oiVenues = series.filter { it.second.isNotEmpty() }.map { it.first })
                rebuild()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.userMessage())
            }
        }
    }

    private fun rebuild() {
        val st = _ui.value
        val cs = candles
        if (cs.isEmpty()) return
        viewModelScope.launch {
            val h = withContext(Dispatchers.Default) { LiqHeatmapEngine.build(cs, oi, st.model, st.range.interval.ms) }
            _ui.value = _ui.value.copy(heatmap = h, loading = false, error = if (h == null) "Not enough data to build a heatmap." else null, updatedAt = System.currentTimeMillis())
        }
    }
}
