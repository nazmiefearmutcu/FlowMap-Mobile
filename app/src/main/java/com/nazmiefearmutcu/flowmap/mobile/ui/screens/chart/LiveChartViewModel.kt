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

/** Owns the live session for the selected market; pauses it while the app is in the background. */
class LiveChartViewModel : ViewModel() {
    private val _session = MutableStateFlow<LiveMarketSession?>(null)
    val session: StateFlow<LiveMarketSession?> = _session.asStateFlow()

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

    private fun switchTo(v: Venue, symbol: String) {
        val cur = _session.value
        if (cur != null && cur.venue == v && cur.symbol == symbol) return
        cur?.stop()
        val next = LiveMarketSession(v, symbol)
        _session.value = next
        if (AppGraph.foreground.value) next.start()
    }

    override fun onCleared() {
        _session.value?.stop()
    }
}
