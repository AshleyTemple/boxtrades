package com.boxyield.core

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A detected box, enriched with comparisons. */
data class BoxObservation(
    val box: BoxTrade,
    val days: Int,
    val yieldPct: Double,
    /** Yield after the user's per-contract fees, at the trade's size. */
    val netYieldPct: Double,
    val treasuryPct: Double?,
    val afterTaxYieldPct: Double,
    val afterTaxTreasuryPct: Double?,
) {
    val spreadBps: Double? get() = treasuryPct?.let { (yieldPct - it) * 100 }
    val afterTaxSpreadBps: Double? get() = afterTaxTreasuryPct?.let { (afterTaxYieldPct - it) * 100 }
}

data class DurationBucket(val label: String, val minDays: Int, val maxDays: Int) {
    operator fun contains(days: Int) = days in minDays..maxDays

    companion object {
        val DEFAULT = listOf(
            DurationBucket("≤ 1 wk", 1, 7),
            DurationBucket("1 wk – 1 mo", 8, 31),
            DurationBucket("1 – 3 mo", 32, 92),
            DurationBucket("3 – 6 mo", 93, 183),
            DurationBucket("6 – 12 mo", 184, 366),
            DurationBucket("1 – 2 yr", 367, 731),
            DurationBucket("2 – 3 yr", 732, 1096),
            DurationBucket("3 yr +", 1097, 4000),
        )
    }
}

data class BucketSummary(
    val bucket: DurationBucket,
    val count: Int,
    /** Sum of face value of boxes in this bucket ($). */
    val faceValue: Double,
    /** Notional-weighted average yield. */
    val avgYieldPct: Double,
    val avgTreasuryPct: Double?,
    val avgSpreadBps: Double?,
    val avgAfterTaxSpreadBps: Double?,
    val bestYieldPct: Double,
    val latest: BoxObservation,
)

class YieldAnalytics(
    var curve: TreasuryCurve? = null,
    var tax: TaxProfile = TaxProfile(),
    var feePerContract: Double = 0.65,
    private val zone: ZoneId = ZoneId.of("America/New_York"),
) {
    fun observe(box: BoxTrade): BoxObservation {
        val tradeDate = Instant.ofEpochMilli(box.timestampMillis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(tradeDate, box.expiry).toInt().coerceAtLeast(1)
        val y = YieldMath.annualizedYieldPct(box.price, box.width, days)
        val tsy = curve?.yieldAt(days)
        return BoxObservation(
            box = box,
            days = days,
            yieldPct = y,
            netYieldPct = YieldMath.netYieldPct(box, days, feePerContract),
            treasuryPct = tsy,
            afterTaxYieldPct = tax.afterTaxBox(y),
            afterTaxTreasuryPct = tsy?.let { tax.afterTaxTreasury(it) },
        )
    }

    /**
     * Per-duration summary, ranked by after-tax spread over Treasuries (falls back to
     * pre-tax spread, then yield). Weights by face value so a 1-lot doesn't outvote a 500-lot.
     */
    fun summarize(obs: List<BoxObservation>, buckets: List<DurationBucket> = DurationBucket.DEFAULT): List<BucketSummary> =
        buckets.mapNotNull { b ->
            val inB = obs.filter { it.days in b }
            if (inB.isEmpty()) return@mapNotNull null
            val w = inB.sumOf { it.box.faceValue }
            fun wavg(f: (BoxObservation) -> Double?): Double? {
                val xs = inB.mapNotNull { o -> f(o)?.let { it to o.box.faceValue } }
                if (xs.isEmpty()) return null
                return xs.sumOf { it.first * it.second } / xs.sumOf { it.second }
            }
            BucketSummary(
                bucket = b,
                count = inB.size,
                faceValue = w,
                avgYieldPct = wavg { it.yieldPct }!!,
                avgTreasuryPct = wavg { it.treasuryPct },
                avgSpreadBps = wavg { it.spreadBps },
                avgAfterTaxSpreadBps = wavg { it.afterTaxSpreadBps },
                bestYieldPct = inB.maxOf { it.yieldPct },
                latest = inB.maxBy { it.box.timestampMillis },
            )
        }.sortedByDescending { it.avgAfterTaxSpreadBps ?: it.avgSpreadBps ?: it.avgYieldPct }
}
