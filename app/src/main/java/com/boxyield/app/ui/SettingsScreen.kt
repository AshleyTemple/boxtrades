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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.boxyield.app.data.AppSettings
import com.boxyield.app.data.FeedSource

@Composable
fun SettingsScreen(initial: AppSettings, onSave: (AppSettings) -> Unit, modifier: Modifier = Modifier) {
    var s by remember(initial) { mutableStateOf(initial) }
    // Text fields edit strings so partially typed numbers ("0.") aren't lost.
    val nums = remember(initial) {
        mutableStateOf(
            mapOf(
                "fee" to initial.feePerContract.toString(),
                "fed" to (initial.federalOrdinary * 100).toString(),
                "ltcg" to (initial.federalLongTerm * 100).toString(),
                "state" to (initial.state * 100).toString(),
                "niit" to (initial.niit * 100).toString(),
                "hysa" to initial.savingsRatePct.toString(),
                "mmf" to initial.moneyMarketRatePct.toString(),
                "minWidth" to initial.minWidth.toString(),
                "window" to initial.windowMillis.toString(),
                "maxDays" to initial.maxDays.toString(),
            )
        )
    }
    fun n(key: String) = nums.value.getValue(key)
    fun setN(key: String, v: String) { nums.value = nums.value + (key to v) }

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
        when (s.source) {
            FeedSource.TRADIER -> Secret("Tradier API access token", s.tradierToken) { s = s.copy(tradierToken = it) }
            FeedSource.POLYGON -> {
                Secret("Massive / Polygon API key", s.polygonApiKey) { s = s.copy(polygonApiKey = it) }
                Field("Multi-leg condition codes (comma-separated)", s.multiLegConditions) { s = s.copy(multiLegConditions = it) }
            }
            FeedSource.SIMULATED -> Unit
        }

        Section("Detection")
        Field("Option roots", s.roots) { s = s.copy(roots = it) }
        Num("Max days to expiry to subscribe", n("maxDays")) { setN("maxDays", it) }
        Num("Min box width (points)", n("minWidth")) { setN("minWidth", it) }
        Num("Max ms between legs", n("window")) { setN("window", it) }
        Toggle("Only accept prints flagged multi-leg", s.requireMultiLegFlag) { s = s.copy(requireMultiLegFlag = it) }

        Section("Costs & taxes (marginal rates, %)")
        Num("Commission + fees per contract ($)", n("fee")) { setN("fee", it) }
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
                        feePerContract = d("fee", s.feePerContract),
                        federalOrdinary = d("fed", s.federalOrdinary * 100) / 100,
                        federalLongTerm = d("ltcg", s.federalLongTerm * 100) / 100,
                        state = d("state", s.state * 100) / 100,
                        niit = d("niit", s.niit * 100) / 100,
                        savingsRatePct = d("hysa", s.savingsRatePct),
                        moneyMarketRatePct = d("mmf", s.moneyMarketRatePct),
                        minWidth = d("minWidth", s.minWidth),
                        windowMillis = n("window").toLongOrNull() ?: s.windowMillis,
                        maxDays = n("maxDays").toIntOrNull() ?: s.maxDays,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save & restart feed") }
        Text(
            "Keys are stored only on this device. Box spreads carry broker margin, assignment-on-American-style and " +
                "execution risks; this app only analyzes printed trades. Not investment or tax advice.",
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
private fun Field(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())

@Composable
private fun Num(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )

@Composable
private fun Secret(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
    )

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
