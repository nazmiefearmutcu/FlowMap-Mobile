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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    /** The request (symbol|scope|range|venue) [heatmap] answers; views key their state on it. */
    val heatmapKey: String = "",
    /** Venues whose open interest went into the model. */
    val oiVenues: List<Venue> = emptyList(),
    val updatedAt: Long = 0L,
)

class LiqMapViewModel : ViewModel() {
    private val settings = AppGraph.settings
    private val _ui = MutableStateFlow(LiqMapUi(symbol = settings.symbol.value, venue = settings.venue.value))
    val ui: StateFlow<LiqMapUi> = _ui.asStateFlow()

    /** Data of the last completed load, tagged with the request it answers. */
    private class Loaded(
        val base: String,
        val request: String,
        val venue: Venue,
        val oiVenues: List<Venue>,
        val candles: List<Candle>,
        val oi: List<OiPoint>,
        val intervalMs: Long,
    )

    private var loaded: Loaded? = null
    private var loadJob: Job? = null
    private var buildJob: Job? = null

    /** What the user asked for (symbol, scope, range); the venue that answered is added in [requestKey]. */
    private fun baseKey(st: LiqMapUi) = "${st.symbol}|${st.scope}|${st.range}"
    private fun requestKey(st: LiqMapUi) = "${baseKey(st)}|${st.venue}"

    init {
        viewModelScope.launch {
            combine(settings.symbol, settings.venue) { s, v -> s to v }.distinctUntilChanged().collect { (s, v) ->
                _ui.value = _ui.value.copy(symbol = s, venue = v)
                reload()
            }
        }
        // Refresh every 2 minutes, only while the app is on screen.
        viewModelScope.launch {
            while (isActive) {
                delay(120_000)
                if (AppGraph.foreground.value && !_ui.value.loading) reload(silent = true)
            }
        }
    }

    fun setScope(s: Scope) { if (s != _ui.value.scope) { _ui.value = _ui.value.copy(scope = s); reload() } }
    fun setRange(r: TimeRange) { if (r != _ui.value.range) { _ui.value = _ui.value.copy(range = r); reload() } }
    fun setModel(m: LiqModel) {
        if (m == _ui.value.model) return
        _ui.value = _ui.value.copy(model = m)
        // A fetch in progress builds with whatever model is current when its data arrives.
        if (loadJob?.isActive == true) return
        val data = loaded ?: return
        buildJob?.cancel()
        buildJob = viewModelScope.launch { build(data, endsLoad = true) }
    }

    fun reload(silent: Boolean = false) {
        loadJob?.cancel()
        buildJob?.cancel()
        loadJob = viewModelScope.launch {
            val st = _ui.value
            if (!silent) {
                // A different market or window must never be drawn into with the previous heatmap.
                val keep = st.heatmap != null && st.heatmapKey == requestKey(st)
                _ui.value = st.copy(loading = true, error = null, heatmap = if (keep) st.heatmap else null, heatmapKey = if (keep) st.heatmapKey else "")
            }
            try {
                val now = System.currentTimeMillis()
                val start = now - st.range.ms
                val interval = st.range.interval
                val (used, cs) = AppGraph.withVenue(st.venue) { it.candles(st.symbol, interval, start, now) }
                if (cs.size < 2) throw IllegalStateException("No candles for ${st.symbol} on ${used.short}.")
                val venuesForOi = if (st.scope == Scope.PAIR) listOf(used) else Venue.entries.filter { it !in AppGraph.blocked.value }
                val series = venuesForOi.map { v ->
                    async {
                        v to try {
                            Venues.api(v).openInterest(st.symbol, interval, start, now)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.map { it.await() }
                ensureActive() // a newer request superseded this one: publish nothing
                val data = Loaded(
                    base = baseKey(st),
                    request = requestKey(st.copy(venue = used)),
                    venue = used,
                    oiVenues = series.filter { it.second.isNotEmpty() }.map { it.first },
                    candles = cs,
                    oi = LiqHeatmapEngine.mergeOi(series.map { it.second }),
                    intervalMs = interval.ms,
                )
                loaded = data
                // If the model changes while building, build again with the new one: the load ends
                // only with a heatmap that matches what the screen shows.
                while (!build(data, endsLoad = true) && baseKey(_ui.value) == data.base) Unit
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never leave a heatmap on screen that contradicts the model chosen meanwhile.
                val cur = _ui.value
                val stale = cur.heatmap != null && cur.heatmap.model != cur.model
                _ui.value = cur.copy(loading = false, error = e.userMessage(), heatmap = if (stale) null else cur.heatmap, heatmapKey = if (stale) "" else cur.heatmapKey)
            }
        }
    }

    /**
     * Builds with the current model and publishes only if [data] still answers what the user asks for.
     * The venue and OI badge are published together with the heatmap they describe, never before.
     */
    private suspend fun build(data: Loaded, endsLoad: Boolean): Boolean {
        val model = _ui.value.model
        val h = withContext(Dispatchers.Default) { LiqHeatmapEngine.build(data.candles, data.oi, model, data.intervalMs) }
        currentCoroutineContext().ensureActive()
        val now = _ui.value
        if (baseKey(now) != data.base || now.model != model) return false
        _ui.value = now.copy(
            heatmap = h,
            heatmapKey = data.request,
            venue = data.venue,
            oiVenues = data.oiVenues,
            loading = if (endsLoad) false else now.loading,
            error = if (h == null) "Not enough data to build a heatmap." else null,
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }
}
