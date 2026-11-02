package com.boxyield.core

import java.time.Instant
import java.time.LocalDate

/** Box rate implied by option quotes for one root + expiration (from the collector). */
data class ExpiryRate(
    val underlying: String,
    val root: String,
    val expiry: LocalDate,
    val days: Int,
    /** Rate from a put-call-parity fit at mid prices: where box orders typically fill. */
    val midYieldPct: Double,
    /** Buying the box at natural prices (worst case for a lender). */
    val lendYieldPct: Double?,
    /** Selling the box at natural prices (worst case for a borrower). */
    val borrowYieldPct: Double?,
    val treasuryPct: Double?,
    val lowStrike: Double,
    val highStrike: Double,
    val strikes: Int,
    val volume: Long,
)

data class RateSnapshot(
    val generatedAt: Instant,
    val tradeDate: LocalDate,
    val curve: TreasuryCurve?,
    val expiries: List<ExpiryRate>,
)

data class RankedRate(
    val rate: ExpiryRate,
    val spreadBps: Double?,
    val afterTaxYieldPct: Double,
    val afterTaxSpreadBps: Double?,
    /** Spread of the worst-case lending rate over Treasury. */
    val lendSpreadBps: Double?,
) {
    /** What the ranking sorts on. */
    val score: Double get() = afterTaxSpreadBps ?: spreadBps ?: rate.midYieldPct * 100
}

data class BucketBest(
    val bucket: DurationBucket,
    val best: RankedRate,
    val count: Int,
    val avgSpreadBps: Double?,
    val avgAfterTaxSpreadBps: Double?,
)

object RateRanking {
    fun rank(rates: List<ExpiryRate>, tax: TaxProfile): List<RankedRate> = rates.map { r ->
        val tsy = r.treasuryPct
        RankedRate(
            rate = r,
            spreadBps = tsy?.let { (r.midYieldPct - it) * 100 },
            afterTaxYieldPct = tax.afterTaxBox(r.midYieldPct),
            afterTaxSpreadBps = tsy?.let { (tax.afterTaxBox(r.midYieldPct) - tax.afterTaxTreasury(it)) * 100 },
            lendSpreadBps = if (tsy != null && r.lendYieldPct != null) (r.lendYieldPct - tsy) * 100 else null,
        )
    }.sortedByDescending { it.score }

    /** Best expiration in each duration bucket, buckets ordered best-first. */
    fun byBucket(ranked: List<RankedRate>, buckets: List<DurationBucket> = DurationBucket.DEFAULT): List<BucketBest> =
        buckets.mapNotNull { b ->
            val inB = ranked.filter { it.rate.days in b }
            if (inB.isEmpty()) return@mapNotNull null
            BucketBest(
                bucket = b,
                best = inB.maxBy { it.score },
                count = inB.size,
                avgSpreadBps = inB.mapNotNull { it.spreadBps }.takeIf { it.isNotEmpty() }?.average(),
                avgAfterTaxSpreadBps = inB.mapNotNull { it.afterTaxSpreadBps }.takeIf { it.isNotEmpty() }?.average(),
            )
        }.sortedByDescending { it.best.score }
}
