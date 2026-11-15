package com.nazmiefearmutcu.flowmap.mobile.data

import android.content.Context
import android.content.SharedPreferences
import com.nazmiefearmutcu.flowmap.mobile.engine.HeatPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ChartStyle(val label: String) { PIXEL("Pixel"), SMOOTH("Smooth") }

/** User preferences, persisted in SharedPreferences and exposed as StateFlows. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("flowmap", Context.MODE_PRIVATE)

    private fun <T> state(initial: T) = MutableStateFlow(initial)

    private val _venue = state(Venue.entries.getOrElse(prefs.getInt("venue", 0)) { Venue.BINANCE })
    val venue: StateFlow<Venue> = _venue.asStateFlow()

    private val _palette = state(HeatPalette.entries.getOrElse(prefs.getInt("palette", 0)) { HeatPalette.VIRIDIS })
    val palette: StateFlow<HeatPalette> = _palette.asStateFlow()

    private val _threshold = state(prefs.getFloat("threshold", 0.40f))
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _style = state(ChartStyle.entries.getOrElse(prefs.getInt("style", 0)) { ChartStyle.PIXEL })
    val style: StateFlow<ChartStyle> = _style.asStateFlow()

    private val _symbol = state(prefs.getString("symbol", "BTCUSDT") ?: "BTCUSDT")
    val symbol: StateFlow<String> = _symbol.asStateFlow()

    private val _watchlist = state(prefs.getStringSet("watchlist", setOf("BTCUSDT", "ETHUSDT", "SOLUSDT"))!!.toSet())
    val watchlist: StateFlow<Set<String>> = _watchlist.asStateFlow()

    private val _haptics = state(prefs.getBoolean("haptics", true))
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _keepScreenOn = state(prefs.getBoolean("keep_screen_on", true))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _disclaimerAccepted = state(prefs.getBoolean("disclaimer_v1", false))
    val disclaimerAccepted: StateFlow<Boolean> = _disclaimerAccepted.asStateFlow()

    private val _gestureHintSeen = state(prefs.getBoolean("gesture_hint_v1", false))
    val gestureHintSeen: StateFlow<Boolean> = _gestureHintSeen.asStateFlow()

    fun setVenue(v: Venue) { _venue.value = v; prefs.edit().putInt("venue", v.ordinal).apply() }
    fun setPalette(p: HeatPalette) { _palette.value = p; prefs.edit().putInt("palette", p.ordinal).apply() }
    /** Live preview while a slider moves; call [commitThreshold] when the drag ends to persist it. */
    fun previewThreshold(t: Float) { _threshold.value = t }
    fun commitThreshold() { prefs.edit().putFloat("threshold", _threshold.value).apply() }
    fun setStyle(s: ChartStyle) { _style.value = s; prefs.edit().putInt("style", s.ordinal).apply() }
    fun setSymbol(s: String) { _symbol.value = s; prefs.edit().putString("symbol", s).apply() }
    fun setHaptics(b: Boolean) { _haptics.value = b; prefs.edit().putBoolean("haptics", b).apply() }
    fun setKeepScreenOn(b: Boolean) { _keepScreenOn.value = b; prefs.edit().putBoolean("keep_screen_on", b).apply() }
    fun acceptDisclaimer() { _disclaimerAccepted.value = true; prefs.edit().putBoolean("disclaimer_v1", true).apply() }
    fun markGestureHintSeen() { _gestureHintSeen.value = true; prefs.edit().putBoolean("gesture_hint_v1", true).apply() }

    fun toggleWatch(symbol: String) {
        val next = _watchlist.value.toMutableSet().apply { if (!add(symbol)) remove(symbol) }
        _watchlist.value = next
        prefs.edit().putStringSet("watchlist", next).apply()
    }
}
