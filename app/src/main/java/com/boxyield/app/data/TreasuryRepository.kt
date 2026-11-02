package com.boxyield.app.data

import com.boxyield.core.TreasuryCurve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate

/** Daily Treasury par yield curve from home.treasury.gov (free, no key, updated ~3:30pm ET). */
class TreasuryRepository(private val http: OkHttpClient) {

    data class Result(val curve: TreasuryCurve, val live: Boolean)

    suspend fun latest(): Result = withContext(Dispatchers.IO) {
        val year = LocalDate.now().year
        for (y in listOf(year, year - 1)) {
            val curve = runCatching { fetch(y) }.getOrNull()
            if (curve != null) return@withContext Result(curve, live = true)
        }
        Result(FALLBACK, live = false)
    }

    private fun fetch(year: Int): TreasuryCurve? {
        val url = "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/" +
            "daily-treasury-rates.csv/$year/all?type=daily_treasury_yield_curve" +
            "&field_tdr_date_value=$year&page&_format=csv"
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            return TreasuryCurve.parseTreasuryCsv(r.body?.string() ?: return null)
        }
    }

    companion object {
        /** Used only when offline. Placeholder values — the UI marks it as not live. */
        val FALLBACK = TreasuryCurve(
            LocalDate.of(2026, 1, 1),
            mapOf(30 to 4.0, 91 to 3.9, 182 to 3.8, 365 to 3.7, 730 to 3.6, 1095 to 3.6, 1826 to 3.75),
        )
    }
}
