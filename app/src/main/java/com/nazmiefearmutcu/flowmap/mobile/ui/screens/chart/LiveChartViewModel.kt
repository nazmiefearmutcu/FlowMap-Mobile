package com.nazmiefearmutcu.flowmap.mobile.ui.screens.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nazmiefearmutcu.flowmap.mobile.data.AppGraph
import com.nazmiefearmutcu.flowmap.mobile.data.Venue
import com.nazmiefearmutcu.flowmap.mobile.data.live.LiveMarketSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Owns the live session for the selected market and pauses it while the app is in the background.
 *
 * Picking another symbol anywhere in the app no longer throws the recording away: the previous
 * session is paused and kept (up to [KEEP] markets, least recently used dropped first), so coming
 * back shows the recorded heatmap with the time away left as a visible gap.
 */
class LiveChartViewModel : ViewModel() {
    private val _session = MutableStateFlow<LiveMarketSession?>(null)
    val session: StateFlow<LiveMarketSession?> = _session.asStateFlow()

    /** Paused sessions by "venue|symbol", most recently used last. */
    private val kept = LinkedHashMap<String, LiveMarketSession>()

    init {
        val s = AppGraph.settings
        viewModelScope.launch {
            combine(s.symbol, s.venue, AppGraph.blocked) { sym, v, blocked -> sym to (if (v in blocked) v.other else v) }
                .distinctUntilChanged()
                .collect { (sym, v) -> switchTo(v, sym) }
        }
        viewModelScope.launch {
            AppGraph.foreground.collect { fg -> _session.value?.let { if (fg) it.start() else it.pause() } }
        }
        // A WebSocket refused with 451/403 means the venue blocks this region: fall back.
        viewModelScope.launch {
            _session.collectLatest { sess ->
                sess?.state?.collect { st ->
                    val m = st.message ?: return@collect
                    if (!st.synced && (m.contains("HTTP 451") || m.contains("HTTP 403"))) AppGraph.markBlocked(sess.venue)
                }
            }
        }
    }

    private fun key(v: Venue, symbol: String) = "$v|$symbol"

    private fun switchTo(v: Venue, symbol: String) {
        val cur = _session.value
        if (cur != null && cur.venue == v && cur.symbol == symbol) return
        if (cur != null) {
            cur.pause()
            kept.remove(key(cur.venue, cur.symbol))
            kept[key(cur.venue, cur.symbol)] = cur
            while (kept.size > KEEP) {
                val oldest = kept.keys.first()
                kept.remove(oldest)?.stop()
            }
        }
        val next = kept.remove(key(v, symbol)) ?: LiveMarketSession(v, symbol)
        _session.value = next
        if (AppGraph.foreground.value) next.start()
    }

    override fun onCleared() {
        _session.value?.stop()
        kept.values.forEach { it.stop() }
        kept.clear()
    }

    companion object {
        /** Paused markets kept in memory (each recording holds about 15 MB). */
        const val KEEP = 2
    }
}
