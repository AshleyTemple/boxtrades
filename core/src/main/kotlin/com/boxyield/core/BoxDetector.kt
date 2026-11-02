package com.boxyield.core

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Finds box spreads in a stream of individual option prints.
 *
 * Feeds report each leg of a complex order as a separate print, but the legs share a
 * root, expiry and size, and arrive within milliseconds of each other. When we see two
 * strikes with a call and a put each, all with the same size and close timestamps, and
 * the net price implies a plausible interest rate, we call it a box.
 *
 * Not thread-safe; feed it from one coroutine/thread.
 */
class BoxDetector(
    private val config: Config = Config(),
    private val zone: ZoneId = ZoneId.of("America/New_York"),
) {
    data class Config(
        /** Max time between the first and last leg. */
        val windowMillis: Long = 1_000,
        /** Ignore boxes narrower than this (tiny boxes are dominated by tick rounding). */
        val minWidth: Double = 100.0,
        /** Reject implied annual yields outside this range (catches mismatched legs). */
        val minYieldPct: Double = -1.0,
        val maxYieldPct: Double = 15.0,
        /** If true, only accept legs the feed flagged as multi-leg/complex. */
        val requireMultiLegFlag: Boolean = false,
        /** Roots to accept. European, cash-settled index options only: no early-exercise risk. */
        val roots: Set<String> = setOf("SPX", "SPXW", "XSP", "NDX", "NDXP", "RUT", "RUTW"),
    )

    private data class Key(val root: String, val expiry: java.time.LocalDate, val size: Int)

    private val buffers = HashMap<Key, ArrayDeque<OptionTrade>>()

    /** Feed one print; returns any box it completes. */
    fun onTrade(trade: OptionTrade): BoxTrade? {
        val c = trade.contract
        if (c.root !in config.roots) return null
        if (config.requireMultiLegFlag && !trade.multiLeg) return null
        if (trade.size <= 0 || trade.price < 0) return null

        evictOlderThan(trade.timestampMillis - config.windowMillis)

        val key = Key(c.root, c.expiry, trade.size)
        val buf = buffers.getOrPut(key) { ArrayDeque() }

        val box = findBox(trade, buf)
        if (box != null) {
            // Consume the matched legs so they can't be reused.
            box.legs.forEach { leg -> if (leg !== trade) buf.remove(leg) }
        } else {
            buf.addLast(trade)
        }
        return box
    }

    private fun findBox(newLeg: OptionTrade, buf: ArrayDeque<OptionTrade>): BoxTrade? {
        val k = newLeg.contract.strike
        val t = newLeg.contract.type
        val other = if (t == OptionType.CALL) OptionType.PUT else OptionType.CALL

        // Most recent first: when legs repeat, pair with the freshest.
        val sameStrikeOther = buf.lastOrNull { it.contract.strike == k && it.contract.type == other } ?: return null

        val candidates = buf.asReversed().filter { it.contract.type == t && it.contract.strike != k }
        for (sameTypeOtherStrike in candidates) {
            val k2 = sameTypeOtherStrike.contract.strike
            val fourth = buf.lastOrNull { it.contract.strike == k2 && it.contract.type == other } ?: continue
            val legs = listOf(newLeg, sameStrikeOther, sameTypeOtherStrike, fourth)
            build(legs)?.let { return it }
        }
        return null
    }

    private fun build(legs: List<OptionTrade>): BoxTrade? {
        val lo = legs.minOf { it.contract.strike }
        val hi = legs.maxOf { it.contract.strike }
        val width = hi - lo
        if (width < config.minWidth) return null
        fun leg(type: OptionType, strike: Double) = legs.first { it.contract.type == type && it.contract.strike == strike }

        val price = leg(OptionType.CALL, lo).price - leg(OptionType.CALL, hi).price +
            leg(OptionType.PUT, hi).price - leg(OptionType.PUT, lo).price
        if (price <= 0 || price > width * 1.02) return null

        val ts = legs.maxOf { it.timestampMillis }
        val spread = ts - legs.minOf { it.timestampMillis }
        if (spread > config.windowMillis) return null

        val first = legs.first().contract
        val tradeDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(tradeDate, first.expiry).toInt()
        if (days < 1) return null
        val y = YieldMath.annualizedYieldPct(price, width, days, Convention.BOND_EQUIVALENT)
        if (y < config.minYieldPct || y > config.maxYieldPct) return null

        return BoxTrade(
            root = first.root,
            expiry = first.expiry,
            lowStrike = lo,
            highStrike = hi,
            quantity = legs.first().size,
            price = price,
            timestampMillis = ts,
            legs = legs,
            legSpreadMillis = spread,
            flaggedMultiLeg = legs.all { it.multiLeg },
        )
    }

    private fun evictOlderThan(cutoff: Long) {
        val it = buffers.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val q = e.value
            while (q.isNotEmpty() && q.first().timestampMillis < cutoff) q.removeFirst()
            if (q.isEmpty()) it.remove()
        }
    }
}
