package com.boxyield.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxyield.app.UiState
import com.boxyield.core.RankedRate

@Composable
fun ExpiriesScreen(state: UiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    var byDate by rememberSaveable { mutableStateOf(false) }
    val rows = if (byDate) state.ranked.sortedBy { it.rate.days } else state.ranked
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SnapshotHeader("All expirations", state, onRefresh)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { byDate = false }) { Text(if (byDate) "Best spread" else "● Best spread") }
                TextButton(onClick = { byDate = true }) { Text(if (byDate) "● By date" else "By date") }
            }
        }
        items(rows) { r -> RateRow(r, state.settings.taxAdvantaged) }
    }
}

@Composable
private fun RateRow(r: RankedRate, taxAdvantaged: Boolean) {
    val e = r.rate
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${e.root} ${e.expiry} (${tenor(e.days)})", fontWeight = FontWeight.SemiBold)
                Text(pct(e.midYieldPct), fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("vs UST ${pct(e.treasuryPct)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (taxAdvantaged) bps(r.spreadBps) else "${bps(r.spreadBps)} · after-tax ${bps(r.afterTaxSpreadBps)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "Natural lend ${pct(e.lendYieldPct)} / borrow ${pct(e.borrowYieldPct)} · " +
                    "${strike(e.lowStrike)}–${strike(e.highStrike)} · ${e.strikes} strikes · vol ${e.volume}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
