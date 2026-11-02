package com.boxyield.core

/**
 * Marginal tax rates (as fractions, e.g. 0.35). Box spreads on index options (SPX, XSP, NDX, RUT)
 * are Section 1256 contracts: gains taxed 60% long-term / 40% short-term regardless of holding
 * period, and fully taxable by states. Treasury interest is federal-ordinary and state-exempt.
 * Not tax advice — verify your own situation.
 */
data class TaxProfile(
    val federalOrdinary: Double = 0.32,
    val federalLongTerm: Double = 0.15,
    val state: Double = 0.05,
    /** Net investment income tax; applies to both. */
    val niit: Double = 0.0,
    /** If true (IRA/401k), taxes are ignored. */
    val taxAdvantagedAccount: Boolean = false,
) {
    val boxRate: Double
        get() = if (taxAdvantagedAccount) 0.0
        else 0.6 * federalLongTerm + 0.4 * federalOrdinary + niit + state

    val treasuryRate: Double
        get() = if (taxAdvantagedAccount) 0.0 else federalOrdinary + niit

    /** Bank savings, CDs, corporate bonds, most non-government money funds. */
    val fullyTaxableRate: Double
        get() = if (taxAdvantagedAccount) 0.0 else federalOrdinary + niit + state

    fun afterTaxBox(yieldPct: Double) = yieldPct * (1 - boxRate)
    fun afterTaxTreasury(yieldPct: Double) = yieldPct * (1 - treasuryRate)
    fun afterTaxFullyTaxable(yieldPct: Double) = yieldPct * (1 - fullyTaxableRate)
}
