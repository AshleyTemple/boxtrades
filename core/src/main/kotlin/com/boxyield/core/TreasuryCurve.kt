package com.boxyield.core

import java.time.LocalDate

/**
 * Treasury par yield curve (CMT, bond-equivalent %), linearly interpolated by days.
 * Source: U.S. Treasury "Daily Treasury Par Yield Curve Rates".
 */
class TreasuryCurve(val date: LocalDate, points: Map<Int, Double>) {
    /** tenor in days -> yield % */
    val points: List<Pair<Int, Double>> = points.toList().sortedBy { it.first }

    init { require(this.points.isNotEmpty()) }

    fun yieldAt(days: Int): Double {
        val p = points
        if (days <= p.first().first) return p.first().second
        if (days >= p.last().first) return p.last().second
        val i = p.indexOfFirst { it.first >= days }
        val (d0, y0) = p[i - 1]
        val (d1, y1) = p[i]
        return y0 + (y1 - y0) * (days - d0).toDouble() / (d1 - d0)
    }

    companion object {
        /** Column header -> tenor in days. Covers Treasury's CSV labels. */
        private val TENORS = mapOf(
            "1 MO" to 30, "1.5 MONTH" to 45, "2 MO" to 61, "3 MO" to 91, "4 MO" to 122, "6 MO" to 182,
            "1 YR" to 365, "2 YR" to 730, "3 YR" to 1095, "5 YR" to 1826, "7 YR" to 2557,
            "10 YR" to 3652, "20 YR" to 7305, "30 YR" to 10957,
        )

        /**
         * Parse the CSV from
         * https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/{year}/all?type=daily_treasury_yield_curve&field_tdr_date_value={year}&_format=csv
         * Rows are newest-first; returns the newest row's curve.
         */
        fun parseTreasuryCsv(csv: String): TreasuryCurve? {
            val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.size < 2) return null
            val header = splitCsv(lines[0]).map { it.uppercase() }
            val rows = lines.drop(1).map { splitCsv(it) }.mapNotNull { row ->
                val date = parseDate(row.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
                date to row
            }
            val (date, row) = rows.maxByOrNull { it.first } ?: return null
            val pts = HashMap<Int, Double>()
            header.forEachIndexed { i, h ->
                val days = TENORS[h] ?: return@forEachIndexed
                row.getOrNull(i)?.toDoubleOrNull()?.let { pts[days] = it }
            }
            return if (pts.isEmpty()) null else TreasuryCurve(date, pts)
        }

        private fun splitCsv(line: String) = line.split(',').map { it.trim().trim('"') }

        private fun parseDate(s: String): LocalDate? = runCatching {
            if ('/' in s) {
                val (m, d, y) = s.split('/').map { it.toInt() }
                LocalDate.of(y, m, d)
            } else LocalDate.parse(s)
        }.getOrNull()
    }
}
