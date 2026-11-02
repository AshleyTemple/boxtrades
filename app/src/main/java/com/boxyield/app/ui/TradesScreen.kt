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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxyield.app.UiState
import com.boxyield.core.BoxObservation
import java.util.Locale

@Composable
fun TradesScreen(state: UiState, onClear: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${state.observations.size} boxes · ${state.printsSeen} prints", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
        items(state.observations) { o ->
            TradeRow(o, state.settings.feePerContract)
        }
    }
}

@Composable
private fun TradeRow(o: BoxObservation, fee: Double) {
    val b = o.box
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${b.root} ${b.expiry} (${tenor(o.days)})", fontWeight = FontWeight.SemiBold)
                Text(pct(o.yieldPct), fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${strike(b.lowStrike)}/${strike(b.highStrike)} × ${b.quantity} @ ${String.format(Locale.US, "%.2f", b.price)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("vs UST ${pct(o.treasuryPct)} (${bps(o.spreadBps)})", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "${time(b.timestampMillis)} ET · face ${money(b.faceValue)} · legs within ${b.legSpreadMillis}ms" +
                    (if (b.flaggedMultiLeg) " · multi-leg" else "") +
                    " · net of \$${fee}/contract: ${pct(o.netYieldPct)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
