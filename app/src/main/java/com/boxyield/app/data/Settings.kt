package com.boxyield.app.data

import android.content.Context
import com.boxyield.core.TaxProfile

enum class FeedSource(val label: String) {
    SIMULATED("Demo (simulated)"),
    TRADIER("Tradier brokerage stream"),
    POLYGON("Massive / Polygon.io OPRA trades"),
}

data class AppSettings(
    val source: FeedSource = FeedSource.SIMULATED,
    val tradierToken: String = "",
    val polygonApiKey: String = "",
    /** OCC roots to watch, comma-separated. European cash-settled index options only. */
    val roots: String = "SPX,SPXW",
    /** Only subscribe to expirations within this many days (keeps subscriptions manageable). */
    val maxDays: Int = 1100,
    val minWidth: Double = 100.0,
    val windowMillis: Long = 1000,
    val requireMultiLegFlag: Boolean = false,
    /** Feed condition codes that mark a multi-leg print, comma-separated (vendor-specific). */
    val multiLegConditions: String = "",
    val feePerContract: Double = 0.65,
    val federalOrdinary: Double = 0.32,
    val federalLongTerm: Double = 0.15,
    val state: Double = 0.05,
    val niit: Double = 0.0,
    val taxAdvantaged: Boolean = false,
    /** Your alternatives, in %: high-yield savings / money market. */
    val savingsRatePct: Double = 3.75,
    val moneyMarketRatePct: Double = 3.90,
) {
    val rootSet: Set<String> get() = roots.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
    val multiLegConditionSet: Set<String> get() = multiLegConditions.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val tax: TaxProfile get() = TaxProfile(federalOrdinary, federalLongTerm, state, niit, taxAdvantaged)
}

/**
 * Plain SharedPreferences store. API keys are kept on-device only. For production, move
 * secrets to EncryptedSharedPreferences / Android Keystore.
 */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            source = runCatching { FeedSource.valueOf(p.getString("source", d.source.name)!!) }.getOrDefault(d.source),
            tradierToken = p.getString("tradierToken", d.tradierToken)!!,
            polygonApiKey = p.getString("polygonApiKey", d.polygonApiKey)!!,
            roots = p.getString("roots", d.roots)!!,
            maxDays = p.getInt("maxDays", d.maxDays),
            minWidth = p.getFloat("minWidth", d.minWidth.toFloat()).toDouble(),
            windowMillis = p.getLong("windowMillis", d.windowMillis),
            requireMultiLegFlag = p.getBoolean("requireMultiLegFlag", d.requireMultiLegFlag),
            multiLegConditions = p.getString("multiLegConditions", d.multiLegConditions)!!,
            feePerContract = p.getFloat("feePerContract", d.feePerContract.toFloat()).toDouble(),
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
            .putString("tradierToken", s.tradierToken)
            .putString("polygonApiKey", s.polygonApiKey)
            .putString("roots", s.roots)
            .putInt("maxDays", s.maxDays)
            .putFloat("minWidth", s.minWidth.toFloat())
            .putLong("windowMillis", s.windowMillis)
            .putBoolean("requireMultiLegFlag", s.requireMultiLegFlag)
            .putString("multiLegConditions", s.multiLegConditions)
            .putFloat("feePerContract", s.feePerContract.toFloat())
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
