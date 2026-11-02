package com.boxyield.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.boxyield.app.UiState
import java.time.Duration
import java.time.Instant

@Composable
internal fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Title, data age (red when stale), status and a refresh button. */
@Composable
internal fun SnapshotHeader(title: String, state: UiState, onRefresh: () -> Unit) {
    val snap = state.snapshot
    val age = snap?.let { Duration.between(it.generatedAt, Instant.now()) }
    val stale = age != null && age.toHours() >= 24
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text(if (state.loading) "Loading…" else "Refresh") }
        }
        val src = if (state.demo) "DEMO data" else "Schwab quotes"
        Text(
            if (snap == null) state.status
            else "$src · ${ago(age!!)} · Treasury curve ${snap.curve?.date ?: "missing"}",
            style = MaterialTheme.typography.bodySmall,
            color = if (stale || state.demo) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (snap != null && state.status.startsWith("Error")) {
            Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun ago(d: Duration): String = when {
    d.toMinutes() < 1 -> "just now"
    d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
    d.toHours() < 48 -> "${d.toHours()} h ago"
    else -> "${d.toDays()} days ago"
}
