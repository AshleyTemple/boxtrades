package com.boxyield.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.boxyield.app.data.AppSettings
import com.boxyield.app.data.FeedSource

@Composable
fun SettingsScreen(initial: AppSettings, onSave: (AppSettings) -> Unit, modifier: Modifier = Modifier) {
    var s by remember(initial) { mutableStateOf(initial) }
    // Numbers are edited as text so partially typed values ("0.") aren't lost.
    var nums by remember(initial) {
        mutableStateOf(
            mapOf(
                "refresh" to initial.refreshMinutes.toString(),
                "fed" to (initial.federalOrdinary * 100).toString(),
                "ltcg" to (initial.federalLongTerm * 100).toString(),
                "state" to (initial.state * 100).toString(),
                "niit" to (initial.niit * 100).toString(),
                "hysa" to initial.savingsRatePct.toString(),
                "mmf" to initial.moneyMarketRatePct.toString(),
            )
        )
    }
    fun n(key: String) = nums.getValue(key)
    fun setN(key: String, v: String) { nums = nums + (key to v) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Section("Data source")
        FeedSource.entries.forEach { src ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = s.source == src, onClick = { s = s.copy(source = src) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.source == src, onClick = { s = s.copy(source = src) })
                Text(src.label)
            }
        }
        if (s.source == FeedSource.SCHWAB_FILE) {
            OutlinedTextField(
                s.snapshotUrl, { s = s.copy(snapshotUrl = it) },
                label = { Text("Share link to boxyield-latest.json") },
                supportingText = { Text("Google Drive: Share → General access → Anyone with the link → Copy link") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Num("Check for new data every (minutes)", n("refresh")) { setN("refresh", it) }
        }

        Section("Taxes (marginal rates, %)")
        Toggle("Tax-advantaged account (IRA/401k)", s.taxAdvantaged) { s = s.copy(taxAdvantaged = it) }
        if (!s.taxAdvantaged) {
            Num("Federal ordinary income", n("fed")) { setN("fed", it) }
            Num("Federal long-term capital gains", n("ltcg")) { setN("ltcg", it) }
            Num("State income", n("state")) { setN("state", it) }
            Num("Net investment income tax", n("niit")) { setN("niit", it) }
        }

        Section("Your alternatives (%)")
        Num("High-yield savings APY", n("hysa")) { setN("hysa", it) }
        Num("Money market yield", n("mmf")) { setN("mmf", it) }

        Button(
            onClick = {
                fun d(k: String, def: Double) = n(k).toDoubleOrNull() ?: def
                onSave(
                    s.copy(
                        refreshMinutes = n("refresh").toIntOrNull()?.coerceAtLeast(1) ?: s.refreshMinutes,
                        federalOrdinary = d("fed", s.federalOrdinary * 100) / 100,
                        federalLongTerm = d("ltcg", s.federalLongTerm * 100) / 100,
                        state = d("state", s.state * 100) / 100,
                        niit = d("niit", s.niit * 100) / 100,
                        savingsRatePct = d("hysa", s.savingsRatePct),
                        moneyMarketRatePct = d("mmf", s.moneyMarketRatePct),
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save & reload") }
        Text(
            "Rates come from option quotes, not executed trades. Real box orders usually fill between the natural " +
                "lend rate and the mid rate. Not investment or tax advice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Num(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
