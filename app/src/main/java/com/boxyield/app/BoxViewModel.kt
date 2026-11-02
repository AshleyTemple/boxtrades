package com.boxyield.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boxyield.app.data.AppSettings
import com.boxyield.app.data.FeedSource
import com.boxyield.app.data.PolygonFeed
import com.boxyield.app.data.SettingsStore
import com.boxyield.app.data.SimulatedFeed
import com.boxyield.app.data.TradierFeed
import com.boxyield.app.data.TreasuryRepository
import com.boxyield.core.BoxDetector
import com.boxyield.core.BoxObservation
import com.boxyield.core.BucketSummary
import com.boxyield.core.TreasuryCurve
import com.boxyield.core.YieldAnalytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class UiState(
    val settings: AppSettings = AppSettings(),
    val status: String = "Starting…",
    val curve: TreasuryCurve? = null,
    val curveLive: Boolean = false,
    /** Newest first. */
    val observations: List<BoxObservation> = emptyList(),
    val buckets: List<BucketSummary> = emptyList(),
    val printsSeen: Long = 0,
)

class BoxViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val treasury = TreasuryRepository(http)

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init { start() }

    fun updateSettings(s: AppSettings) {
        store.save(s)
        _state.update { it.copy(settings = s, observations = emptyList(), buckets = emptyList(), printsSeen = 0) }
        start()
    }

    fun clear() = _state.update { it.copy(observations = emptyList(), buckets = emptyList(), printsSeen = 0) }

    private fun start() {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.Default) {
            val s = _state.value.settings
            setStatus("Loading Treasury curve…")
            val t = treasury.latest()
            _state.update { it.copy(curve = t.curve, curveLive = t.live) }

            val analytics = YieldAnalytics(t.curve, s.tax, s.feePerContract)
            val detector = BoxDetector(
                BoxDetector.Config(
                    windowMillis = s.windowMillis,
                    minWidth = s.minWidth,
                    requireMultiLegFlag = s.requireMultiLegFlag,
                    roots = s.rootSet,
                )
            )
            val feed = when (s.source) {
                FeedSource.SIMULATED -> SimulatedFeed(t.curve)
                FeedSource.TRADIER -> TradierFeed(http, s.tradierToken, s.rootSet, s.maxDays)
                FeedSource.POLYGON -> PolygonFeed(http, s.polygonApiKey, s.rootSet, s.maxDays, s.multiLegConditionSet)
            }

            var backoff = 2_000L
            while (isActive) {
                try {
                    feed.trades(::setStatus).collect { trade ->
                        backoff = 2_000L
                        val box = detector.onTrade(trade)
                        _state.update { st ->
                            if (box == null) st.copy(printsSeen = st.printsSeen + 1)
                            else {
                                val obs = (listOf(analytics.observe(box)) + st.observations).take(MAX_OBSERVATIONS)
                                st.copy(printsSeen = st.printsSeen + 1, observations = obs, buckets = analytics.summarize(obs))
                            }
                        }
                    }
                    setStatus("Feed ended; reconnecting…")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setStatus("Error: ${e.message ?: e.javaClass.simpleName}. Retrying in ${backoff / 1000}s")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    private fun setStatus(msg: String) = _state.update { it.copy(status = msg) }

    companion object {
        const val MAX_OBSERVATIONS = 2_000
    }
}
