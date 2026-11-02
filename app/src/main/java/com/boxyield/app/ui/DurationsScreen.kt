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
import androidx.compose.foundation.lazy.items
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
import com.boxyield.core.BucketSummary

@Composable
fun DurationsScreen(state: UiState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(state) }
        if (state.buckets.isEmpty()) {
            item { Text("Waiting for box trades… (${state.printsSeen} prints scanned)", style = MaterialTheme.typography.bodyMedium) }
        }
        val maxSpread = state.buckets.maxOfOrNull { rankValue(it) }?.coerceAtLeast(1.0) ?: 1.0
        items(state.buckets, key = { it.bucket.label }) { b ->
            BucketCard(b, rank = state.buckets.indexOf(b) + 1, maxSpread = maxSpread, taxAdvantaged = state.settings.taxAdvantaged)
        }
        item { Benchmarks(state) }
    }
}

private fun rankValue(b: BucketSummary) = b.avgAfterTaxSpreadBps ?: b.avgSpreadBps ?: 0.0

@Composable
private fun Header(state: UiState) {
    Column {
        Text("Box yield vs. Treasuries", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val c = state.curve
        Text(
            if (c == null) "Treasury curve: loading"
            else "Treasury curve: ${c.date}" + if (state.curveLive) "" else " (OFFLINE PLACEHOLDER — not live)",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.curveLive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        Text(
            "Ranked by ${if (state.settings.taxAdvantaged) "spread" else "after-tax spread"} over the same-maturity Treasury, weighted by size.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BucketCard(b: BucketSummary, rank: Int, maxSpread: Double, taxAdvantaged: Boolean) {
    val top = rank == 1
    Card(
        colors = if (top) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("#$rank  ${b.bucket.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(pct(b.avgYieldPct), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (rankValue(b) / maxSpread).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Stat("Treasury (same maturity)", pct(b.avgTreasuryPct))
            Stat("Spread over Treasury", bps(b.avgSpreadBps))
            if (!taxAdvantaged) Stat("After-tax spread", bps(b.avgAfterTaxSpreadBps))
            Stat("Best print", pct(b.bestYieldPct))
            Stat("Boxes / face value", "${b.count} / ${money(b.faceValue)}")
            Stat("Latest", "${b.latest.box.root} ${b.latest.box.expiry} · ${time(b.latest.box.timestampMillis)}")
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Compare the best box bucket to what the user's cash earns elsewhere, after tax. */
@Composable
private fun Benchmarks(state: UiState) {
    val s = state.settings
    val tax = s.tax
    val best = state.buckets.firstOrNull() ?: return
    val shortest = state.buckets.minByOrNull { it.bucket.minDays }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("After-tax comparison", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Box tax rate ${pct(tax.boxRate * 100, 1)} (60/40 + state) · Treasury ${pct(tax.treasuryRate * 100, 1)} (state-exempt) · " +
                    "savings/MMF ${pct(tax.fullyTaxableRate * 100, 1)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Stat("Box, best duration (${best.bucket.label})", pct(tax.afterTaxBox(best.avgYieldPct)))
            best.avgTreasuryPct?.let { Stat("Treasury, same duration", pct(tax.afterTaxTreasury(it))) }
            shortest?.let { Stat("Box, shortest (${it.bucket.label})", pct(tax.afterTaxBox(it.avgYieldPct))) }
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
