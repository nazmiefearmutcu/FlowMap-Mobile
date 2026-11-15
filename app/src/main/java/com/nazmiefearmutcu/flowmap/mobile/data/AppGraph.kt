package com.nazmiefearmutcu.flowmap.mobile.data

import android.content.Context
import com.nazmiefearmutcu.flowmap.mobile.data.exchange.MarketApi
import com.nazmiefearmutcu.flowmap.mobile.data.exchange.Venues
import com.nazmiefearmutcu.flowmap.mobile.data.live.LiquidationHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide singletons. Initialised once from [com.nazmiefearmutcu.flowmap.mobile.FlowMapApp]. */
object AppGraph {
    lateinit var settings: Settings
        private set
    val liquidations = LiquidationHub()

    private val _blocked = MutableStateFlow<Set<Venue>>(emptySet())
    /** Venues that refused this device's region; requests fall back to the other venue. */
    val blocked: StateFlow<Set<Venue>> = _blocked.asStateFlow()

    private val _foreground = MutableStateFlow(false)
    /** True while the app is visible; live streams pause in the background to save battery and data. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun init(context: Context) {
        if (!::settings.isInitialized) settings = Settings(context.applicationContext)
    }

    /** Suspends while the app is in the background; polling loops call it before each request. */
    suspend fun awaitForeground() {
        foreground.first { it }
    }

    fun setForeground(visible: Boolean) {
        _foreground.value = visible
        if (visible) liquidations.start() else liquidations.stop()
    }

    fun markBlocked(v: Venue) {
        _blocked.value = _blocked.value + v
    }

    /**
     * Runs [block] on [preferred]; if that venue blocks the region, retries on the other venue and
     * remembers the block. Returns the venue that actually answered.
     */
    suspend fun <T> withVenue(preferred: Venue, block: suspend (MarketApi) -> T): Pair<Venue, T> {
        val first = if (preferred in _blocked.value) preferred.other else preferred
        return try {
            first to block(Venues.api(first))
        } catch (e: HttpError) {
            if (!e.isRegionBlock) throw e
            _blocked.value = _blocked.value + first
            first.other to block(Venues.api(first.other))
        }
    }

    private val tickerMutexes = Venue.entries.associateWith { Mutex() }
    private val tickerCache = java.util.concurrent.ConcurrentHashMap<Venue, Pair<Long, List<Ticker>>>()

    /** 24 h tickers with a 15-second cache. One lock per venue, so the two venues load in parallel. */
    suspend fun tickers(venue: Venue, maxAgeMs: Long = 15_000): List<Ticker> = tickerMutexes.getValue(venue).withLock {
        val hit = tickerCache[venue]
        if (hit != null && System.currentTimeMillis() - hit.first < maxAgeMs) return hit.second
        val fresh = Venues.api(venue).tickers()
        tickerCache[venue] = System.currentTimeMillis() to fresh
        fresh
    }
}
