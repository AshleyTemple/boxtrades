package com.boxyield.app.data

import android.content.Context
import com.boxyield.core.TaxProfile

enum class FeedSource(val label: String) {
    SCHWAB_FILE("Schwab snapshot file (from your collector)"),
    SIMULATED("Demo (simulated data)"),
}

data class AppSettings(
    val source: FeedSource = FeedSource.SIMULATED,
    /** Google Drive share link (or any HTTPS URL) of boxyield-latest.json. */
    val snapshotUrl: String = "",
    val refreshMinutes: Int = 15,
    val federalOrdinary: Double = 0.32,
    val federalLongTerm: Double = 0.15,
    val state: Double = 0.05,
    val niit: Double = 0.0,
    val taxAdvantaged: Boolean = false,
    /** Your alternatives, in %: high-yield savings / money market. */
    val savingsRatePct: Double = 3.75,
    val moneyMarketRatePct: Double = 3.90,
) {
    val tax: TaxProfile get() = TaxProfile(federalOrdinary, federalLongTerm, state, niit, taxAdvantaged)
}

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            source = runCatching { FeedSource.valueOf(p.getString("source", d.source.name)!!) }.getOrDefault(d.source),
            snapshotUrl = p.getString("snapshotUrl", d.snapshotUrl)!!,
            refreshMinutes = p.getInt("refreshMinutes", d.refreshMinutes),
            federalOrdinary = p.getFloat("federalOrdinary", d.federalOrdinary.toFloat()).toDouble(),
            federalLongTerm = p.getFloat("federalLongTerm", d.federalLongTerm.toFloat()).toDouble(),
            state = p.getFloat("state", d.state.toFloat()).toDouble(),
            niit = p.getFloat("niit", d.niit.toFloat()).toDouble(),
            taxAdvantaged = p.getBoolean("taxAdvantaged", d.taxAdvantaged),
            savingsRatePct = p.getFloat("savingsRatePct", d.savingsRatePct.toFloat()).toDouble(),
            moneyMarketRatePct = p.getFloat("moneyMarketRatePct", d.moneyMarketRatePct.toFloat()).toDouble(),
        )
    }

    fun save(s: AppSettings) {
        p.edit()
            .putString("source", s.source.name)
            .putString("snapshotUrl", s.snapshotUrl)
            .putInt("refreshMinutes", s.refreshMinutes)
            .putFloat("federalOrdinary", s.federalOrdinary.toFloat())
            .putFloat("federalLongTerm", s.federalLongTerm.toFloat())
            .putFloat("state", s.state.toFloat())
            .putFloat("niit", s.niit.toFloat())
            .putBoolean("taxAdvantaged", s.taxAdvantaged)
            .putFloat("savingsRatePct", s.savingsRatePct.toFloat())
            .putFloat("moneyMarketRatePct", s.moneyMarketRatePct.toFloat())
            .apply()
    }
}
