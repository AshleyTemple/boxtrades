package com.boxyield.core

import java.time.LocalDate

enum class OptionType { CALL, PUT }

/** A listed option contract, identified the same way OCC/OPRA symbols identify it. */
data class OptionContract(
    /** OCC root, e.g. "SPX", "SPXW", "XSP". Different roots have different settlement, so a box must use one root. */
    val root: String,
    val expiry: LocalDate,
    val type: OptionType,
    /** Strike in index points / dollars. */
    val strike: Double,
) {
    val occSymbol: String get() = OccSymbol.format(this)
}

/** A single printed option trade (one leg), as delivered by a market-data feed. */
data class OptionTrade(
    val contract: OptionContract,
    /** Per-share premium. */
    val price: Double,
    /** Number of contracts. */
    val size: Int,
    /** Exchange timestamp, epoch milliseconds. */
    val timestampMillis: Long,
    /** Raw sale-condition codes from the feed (may be empty). */
    val conditions: Set<String> = emptySet(),
    val exchange: String? = null,
    /** True if the feed flagged this print as part of a multi-leg/complex order. */
    val multiLeg: Boolean = false,
)

/**
 * Four legs on the same root/expiry forming a box with strikes [lowStrike] < [highStrike]:
 *   +C(low) -C(high) +P(high) -P(low)
 * Its value at expiry is always (high - low) for European, cash-settled options, so the
 * price paid today implies a risk-free-like interest rate.
 */
data class BoxTrade(
    val root: String,
    val expiry: LocalDate,
    val lowStrike: Double,
    val highStrike: Double,
    val quantity: Int,
    /** Net price per share of the long box (what the lender pays). */
    val price: Double,
    val timestampMillis: Long,
    val legs: List<OptionTrade>,
    /** Max timestamp gap between the legs, ms. Smaller = higher confidence it was one order. */
    val legSpreadMillis: Long,
    val flaggedMultiLeg: Boolean,
) {
    val width: Double get() = highStrike - lowStrike
    /** Cash paid by the lender for the whole trade (index multiplier 100). */
    val notional: Double get() = price * 100.0 * quantity
    /** Cash received at expiry. */
    val faceValue: Double get() = width * 100.0 * quantity
}
