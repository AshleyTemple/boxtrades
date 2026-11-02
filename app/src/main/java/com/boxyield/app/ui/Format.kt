package com.boxyield.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun pct(x: Double?, digits: Int = 2) = x?.let { String.format(Locale.US, "%.${digits}f%%", it) } ?: "—"
internal fun bps(x: Double?) = x?.let { String.format(Locale.US, "%+.0f bp", it) } ?: "—"
internal fun money(x: Double): String = when {
    x >= 1e9 -> String.format(Locale.US, "$%.2fB", x / 1e9)
    x >= 1e6 -> String.format(Locale.US, "$%.1fM", x / 1e6)
    x >= 1e3 -> String.format(Locale.US, "$%.0fK", x / 1e3)
    else -> String.format(Locale.US, "$%.0f", x)
}
internal fun strike(x: Double) = if (x % 1.0 == 0.0) x.toLong().toString() else x.toString()

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("America/New_York"))
internal fun time(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms))

internal fun tenor(days: Int) = when {
    days < 60 -> "${days}d"
    days < 730 -> String.format(Locale.US, "%.1fmo", days / 30.44)
    else -> String.format(Locale.US, "%.1fy", days / 365.0)
}
