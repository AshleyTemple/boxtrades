package com.boxyield.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boxyield.app.data.AppSettings
import com.boxyield.app.data.FeedSource
import com.boxyield.app.data.SettingsStore
import com.boxyield.app.data.SnapshotRepository
import com.boxyield.app.data.TreasuryRepository
import com.boxyield.core.BucketBest
import com.boxyield.core.RankedRate
import com.boxyield.core.RateRanking
import com.boxyield.core.RateSnapshot
import com.boxyield.core.SimulatedMarket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.TimeUnit

data class UiState(
    val settings: AppSettings = AppSettings(),
    val status: String = "Loading…",
    val loading: Boolean = false,
    val snapshot: RateSnapshot? = null,
    val demo: Boolean = false,
    /** All expirations, best after-tax spread first. */
    val ranked: List<RankedRate> = emptyList(),
    val buckets: List<BucketBest> = emptyList(),
)

class BoxViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val snapshots = SnapshotRepository(http)
    private val treasury = TreasuryRepository(http)

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init { start() }

    fun updateSettings(s: AppSettings) {
        store.save(s)
        _state.update { it.copy(settings = s) }
        start()
    }

    fun refresh() = start()

    private fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            while (isActive) {
                val s = _state.value.settings
                _state.update { it.copy(loading = true) }
                try {
                    val snap = when (s.source) {
                        FeedSource.SCHWAB_FILE -> snapshots.load(s.snapshotUrl)
                        FeedSource.SIMULATED -> SimulatedMarket(treasury.latest().curve).snapshot(Instant.now())
                    }
                    val ranked = RateRanking.rank(snap.expiries, s.tax)
                    _state.update {
                        it.copy(
                            snapshot = snap, demo = s.source == FeedSource.SIMULATED, ranked = ranked,
                            buckets = RateRanking.byBucket(ranked), status = "Updated", loading = false,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(status = "Error: ${e.message ?: e.javaClass.simpleName}", loading = false) }
                }
                delay(s.refreshMinutes.coerceAtLeast(1) * 60_000L)
            }
        }
    }
}
