package com.boxyield.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Generates a plausible stream of SPX prints for demo mode and tests: box trades priced at
 * Treasury + a spread, interleaved with unrelated single-leg prints.
 */
class SimulatedMarket(
    private val curve: TreasuryCurve,
    private val random: Random = Random.Default,
    private val zone: ZoneId = ZoneId.of("America/New_York"),
) {
    fun nextBatch(nowMillis: Long): List<OptionTrade> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val out = ArrayList<OptionTrade>()
        repeat(random.nextInt(0, 4)) { out += noise(today, nowMillis) }
        if (random.nextDouble() < 0.5) out += box(today, nowMillis)
        return out.sortedBy { it.timestampMillis }
    }

    private fun expiry(today: LocalDate): LocalDate {
        val days = listOf(7, 21, 45, 90, 150, 270, 400, 700, 1000).random(random)
        var d = today.plusDays(days.toLong())
        while (d.dayOfWeek.value != 5) d = d.plusDays(1) // Fridays
        return d
    }

    private fun box(today: LocalDate, now: Long): List<OptionTrade> {
        val exp = expiry(today)
        val days = ChronoUnit.DAYS.between(today, exp).toInt()
        val width = listOf(500.0, 1000.0, 2000.0, 4000.0).random(random)
        val lo = (4000 + random.nextInt(0, 30) * 100).toDouble()
        val hi = lo + width
        // Boxes typically trade 20–60 bps over Treasuries; longer tenors a bit wider.
        val spreadPct = 0.15 + random.nextDouble() * 0.35 + days / 3650.0 * 0.2
        val price = YieldMath.priceForYield(curve.yieldAt(days) + spreadPct, width, days)
        // Split box price into plausible leg prices. Only the combination matters.
        val cLo = 2000.0 + random.nextDouble() * 100
        val pHi = random.nextDouble() * 200 + 100
        val pLo = random.nextDouble() * 50 + 5
        val cHi = cLo + pHi - pLo - price
        val qty = listOf(10, 25, 50, 100, 250, 500).random(random)
        val legs = listOf(
            Triple(OptionType.CALL, lo, cLo), Triple(OptionType.CALL, hi, cHi),
            Triple(OptionType.PUT, hi, pHi), Triple(OptionType.PUT, lo, pLo),
        ).shuffled(random)
        return legs.mapIndexed { i, (t, k, p) ->
            OptionTrade(OptionContract("SPXW", exp, t, k), round2(p), qty, now + i * random.nextLong(0, 5), multiLeg = true)
        }.let { fixRounding(it, price) }
    }

    /** Push rounding error into one leg so the box nets to [price] to the cent. */
    private fun fixRounding(legs: List<OptionTrade>, price: Double): List<OptionTrade> {
        fun sign(l: OptionTrade, lo: Double) = when {
            l.contract.type == OptionType.CALL && l.contract.strike == lo -> 1
            l.contract.type == OptionType.PUT && l.contract.strike != lo -> 1
            else -> -1
        }
        val lo = legs.minOf { it.contract.strike }
        val net = legs.sumOf { sign(it, lo) * it.price }
        val target = round2(price)
        val adj = legs.indexOfFirst { sign(it, lo) == 1 }
        return legs.mapIndexed { i, l -> if (i == adj) l.copy(price = round2(l.price + target - net)) else l }
    }

    private fun noise(today: LocalDate, now: Long) = OptionTrade(
        OptionContract("SPXW", expiry(today), if (random.nextBoolean()) OptionType.CALL else OptionType.PUT,
            (5000 + random.nextInt(-20, 20) * 25).toDouble()),
        round2(random.nextDouble() * 80 + 0.05), random.nextInt(1, 30), now + random.nextLong(0, 10),
    )

    private fun round2(x: Double) = Math.round(x * 100) / 100.0
}
