package com.boxyield.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val ny = ZoneId.of("America/New_York")
    private val tradeDay = LocalDate.of(2026, 11, 2)
    private val t0 = tradeDay.atTime(10, 0).atZone(ny).toInstant().toEpochMilli()
    private val exp = LocalDate.of(2027, 5, 1) // 180 days

    private fun leg(type: OptionType, k: Double, p: Double, size: Int = 100, dt: Long = 0, root: String = "SPXW") =
        OptionTrade(OptionContract(root, exp, type, k), p, size, t0 + dt)

    @Test fun occRoundTrip() {
        val c = OccSymbol.parse("O:SPXW261218C05000000")!!
        assertEquals("SPXW", c.root)
        assertEquals(LocalDate.of(2026, 12, 18), c.expiry)
        assertEquals(OptionType.CALL, c.type)
        assertEquals(5000.0, c.strike)
        assertEquals("SPXW261218C05000000", c.occSymbol)
        assertEquals(5012.5, OccSymbol.parse("SPX   261218P05012500")!!.strike)
        assertNull(OccSymbol.parse("AAPL"))
    }

    @Test fun detectsBoxRegardlessOfLegOrder() {
        // width 1000; price 980 -> (1000/980-1)*365/180 = 4.138%
        val d = BoxDetector()
        assertNull(d.onTrade(leg(OptionType.PUT, 5000.0, 50.0, dt = 3)))
        assertNull(d.onTrade(leg(OptionType.CALL, 6000.0, 300.0, dt = 1)))
        assertNull(d.onTrade(leg(OptionType.CALL, 5000.0, 1200.0, dt = 2)))
        val box = assertNotNull(d.onTrade(leg(OptionType.PUT, 6000.0, 130.0, dt = 4)))
        assertEquals(980.0, box.price, 1e-9)
        assertEquals(1000.0, box.width)
        assertEquals(5000.0, box.lowStrike)
        assertEquals(100, box.quantity)
        assertEquals(3, box.legSpreadMillis)
        assertEquals(4.138, YieldMath.annualizedYieldPct(box.price, box.width, 180), 1e-3)
    }

    @Test fun rejectsMismatchedSizeTimeAndRoot() {
        val d = BoxDetector()
        d.onTrade(leg(OptionType.CALL, 5000.0, 1200.0))
        d.onTrade(leg(OptionType.CALL, 6000.0, 300.0))
        d.onTrade(leg(OptionType.PUT, 5000.0, 50.0))
        assertNull(d.onTrade(leg(OptionType.PUT, 6000.0, 130.0, size = 99)))
        assertNull(d.onTrade(leg(OptionType.PUT, 6000.0, 130.0, dt = 5_000)))
        assertNull(BoxDetector().apply {
            onTrade(leg(OptionType.CALL, 5000.0, 1200.0)); onTrade(leg(OptionType.CALL, 6000.0, 300.0))
            onTrade(leg(OptionType.PUT, 5000.0, 50.0))
        }.onTrade(leg(OptionType.PUT, 6000.0, 130.0, root = "SPX")))
    }

    @Test fun rejectsImplausibleRate() {
        val d = BoxDetector()
        d.onTrade(leg(OptionType.CALL, 5000.0, 1000.0))
        d.onTrade(leg(OptionType.CALL, 6000.0, 300.0))
        d.onTrade(leg(OptionType.PUT, 5000.0, 50.0))
        // box price 780 on width 1000 over 180 days = ~57% annualized: unrelated prints, not a box
        assertNull(d.onTrade(leg(OptionType.PUT, 6000.0, 130.0)))
    }

    @Test fun bondEquivalentMatchesInverse() {
        for (days in listOf(7, 90, 182, 183, 365, 1000)) {
            val p = YieldMath.priceForYield(4.25, 1000.0, days)
            assertEquals(4.25, YieldMath.annualizedYieldPct(p, 1000.0, days), 1e-9)
        }
    }

    @Test fun treasuryCsvAndInterpolation() {
        val csv = """
            Date,"1 Mo","1.5 Month","2 Mo","3 Mo","4 Mo","6 Mo","1 Yr","2 Yr","3 Yr","5 Yr","7 Yr","10 Yr","20 Yr","30 Yr"
            10/30/2026,4.00,3.98,3.96,3.90,3.85,3.80,3.70,3.60,3.62,3.75,3.90,4.10,4.50,4.60
            10/29/2026,9,9,9,9,9,9,9,9,9,9,9,9,9,9
        """.trimIndent()
        val c = assertNotNull(TreasuryCurve.parseTreasuryCsv(csv))
        assertEquals(LocalDate.of(2026, 10, 30), c.date)
        assertEquals(4.00, c.yieldAt(10))
        assertEquals(3.90, c.yieldAt(91))
        assertEquals(3.80 + (3.70 - 3.80) * (273 - 182) / (365.0 - 182), c.yieldAt(273), 1e-12)
    }

    @Test fun taxMath() {
        val t = TaxProfile(federalOrdinary = 0.35, federalLongTerm = 0.15, state = 0.093)
        // box: 0.6*0.15+0.4*0.35+0.093 = 0.323 ; treasury 0.35
        assertEquals(0.323, t.boxRate, 1e-12)
        assertEquals(4.5 * 0.677, t.afterTaxBox(4.5), 1e-12)
        assertEquals(0.0, t.copy(taxAdvantagedAccount = true).boxRate)
    }

    @Test fun simulatedStreamIsFullyRecovered() {
        val curve = TreasuryCurve(tradeDay, mapOf(30 to 4.0, 365 to 3.7, 1826 to 3.75))
        val sim = SimulatedMarket(curve, Random(42))
        val d = BoxDetector()
        val a = YieldAnalytics(curve)
        val obs = ArrayList<BoxObservation>()
        var now = t0
        repeat(500) {
            sim.nextBatch(now).forEach { tr -> d.onTrade(tr)?.let { obs += a.observe(it) } }
            now += 2_000
        }
        assertTrue(obs.size > 150, "found ${obs.size}")
        obs.forEach { o ->
            val s = o.spreadBps!!
            assertTrue(s in 10.0..80.0, "spread $s for ${o.days}d")
            assertTrue(o.netYieldPct < o.yieldPct)
        }
        val summary = a.summarize(obs)
        assertTrue(summary.isNotEmpty())
        val ranks = summary.map { it.avgAfterTaxSpreadBps!! }
        assertEquals(ranks.sortedDescending(), ranks)
    }
}
