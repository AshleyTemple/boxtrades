package com.boxyield.core

import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * OCC option symbology: ROOT + YYMMDD + C/P + strike*1000 zero-padded to 8 digits.
 * e.g. "SPXW261218C05000000" = SPXW 2026-12-18 5000 call.
 * Accepts optional padding spaces (OSI format) and vendor prefixes such as Polygon's "O:".
 */
object OccSymbol {
    private val REGEX = Regex("""^(?:O:)?([A-Z0-9]{1,6})\s*(\d{6})([CP])(\d{8})$""")

    fun parse(symbol: String): OptionContract? {
        val m = REGEX.matchEntire(symbol.trim().uppercase()) ?: return null
        val (root, ymd, cp, strike) = m.destructured
        val expiry = runCatching {
            LocalDate.of(2000 + ymd.substring(0, 2).toInt(), ymd.substring(2, 4).toInt(), ymd.substring(4, 6).toInt())
        }.getOrNull() ?: return null
        return OptionContract(
            root = root,
            expiry = expiry,
            type = if (cp == "C") OptionType.CALL else OptionType.PUT,
            strike = strike.toLong() / 1000.0,
        )
    }

    fun format(c: OptionContract): String {
        val yy = c.expiry.year % 100
        val strike = (c.strike * 1000).roundToLong()
        return "%s%02d%02d%02d%s%08d".format(
            c.root, yy, c.expiry.monthValue, c.expiry.dayOfMonth,
            if (c.type == OptionType.CALL) "C" else "P", strike,
        )
    }
}
