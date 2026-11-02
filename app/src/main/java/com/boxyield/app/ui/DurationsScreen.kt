package com.boxyield.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxyield.app.UiState
import com.boxyield.core.BucketBest

@Composable
fun DurationsScreen(state: UiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val taxAdv = state.settings.taxAdvantaged
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SnapshotHeader("Best durations", state, onRefresh)
            Text(
                "Best expiration in each duration bucket, ranked by ${if (taxAdv) "spread" else "after-tax spread"} " +
                    "over the same-maturity Treasury. Box rate = put-call parity fit at mid prices.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val max = state.buckets.maxOfOrNull { it.best.score }?.coerceAtLeast(1.0) ?: 1.0
        itemsIndexed(state.buckets, key = { _, b -> b.bucket.label }) { i, b -> BucketCard(b, i + 1, max, taxAdv) }
        item { Benchmarks(state) }
    }
}

@Composable
private fun BucketCard(b: BucketBest, rank: Int, max: Double, taxAdvantaged: Boolean) {
    val r = b.best
    Card(
        colors = if (rank == 1) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("#$rank  ${b.bucket.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(pct(r.rate.midYieldPct), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text("${r.rate.root} ${r.rate.expiry} (${tenor(r.rate.days)})", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { (r.score / max).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Stat("Treasury (same maturity)", pct(r.rate.treasuryPct))
            Stat("Spread over Treasury", bps(r.spreadBps))
            if (!taxAdvantaged) Stat("After-tax spread", bps(r.afterTaxSpreadBps))
            Stat("Worst-case lend rate", "${pct(r.rate.lendYieldPct)} (${bps(r.lendSpreadBps)})")
            Stat("Bucket average spread", bps(if (taxAdvantaged) b.avgSpreadBps else b.avgAfterTaxSpreadBps))
            Stat("Expirations in bucket", b.count.toString())
        }
    }
}

/** Compare the best box duration to what the user's cash earns elsewhere, after tax. */
@Composable
private fun Benchmarks(state: UiState) {
    val s = state.settings
    val tax = s.tax
    val best = state.buckets.firstOrNull()?.best ?: return
    val shortest = state.buckets.minByOrNull { it.bucket.minDays }?.best
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("After-tax comparison", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Tax rates — box ${pct(tax.boxRate * 100, 1)} (60/40 + state) · Treasury ${pct(tax.treasuryRate * 100, 1)} " +
                    "(state-exempt) · savings/MMF ${pct(tax.fullyTaxableRate * 100, 1)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Stat("Box, best (${tenor(best.rate.days)})", pct(best.afterTaxYieldPct))
            best.rate.treasuryPct?.let { Stat("Treasury, same maturity", pct(tax.afterTaxTreasury(it))) }
            shortest?.let { Stat("Box, shortest (${tenor(it.rate.days)})", pct(it.afterTaxYieldPct)) }
            Stat("High-yield savings (${pct(s.savingsRatePct)})", pct(tax.afterTaxFullyTaxable(s.savingsRatePct)))
            Stat("Money market (${pct(s.moneyMarketRatePct)})", pct(tax.afterTaxFullyTaxable(s.moneyMarketRatePct)))
            Spacer(Modifier.height(6.dp))
            Text(
                "Government money funds are often partly state-exempt, so their after-tax yield may be higher than shown. Not tax advice.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
