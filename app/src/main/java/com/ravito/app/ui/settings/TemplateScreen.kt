@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravito.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ravito.app.domain.ReportGenerator
import com.ravito.app.ui.components.InfoCard
import com.ravito.app.ui.components.ravitoTopBarColors
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.ui.components.toast
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/** Édition du modèle de rapport, avec aperçu sur les envois du jour. */
@Composable
fun TemplateScreen(onBack: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    var value by remember { mutableStateOf(TextFieldValue(app.settings.current.template)) }
    var preview by remember { mutableStateOf("") }

    LaunchedEffect(value.text) {
        val s = app.settings.current
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val range = s.period.range(today, zone)
        val txs = app.repository.observeTransactions(range.first, range.last + 1).first().map { it.toReportTx() }
        preview = ReportGenerator.generate(today, txs, s.reportOptions().copy(template = value.text), zone)
    }

    fun insert(token: String) {
        val t = value.text
        val start = value.selection.min.coerceIn(0, t.length)
        val end = value.selection.max.coerceIn(0, t.length)
        val next = t.substring(0, start) + token + t.substring(end)
        value = TextFieldValue(next, TextRange(start + token.length))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = ravitoTopBarColors(),
                title = { Text("Modèle de rapport") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Collez ici le texte exact que vous envoyez dans WhatsApp, puis remplacez les valeurs par les zones ci-dessous.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    app.settings.update { it.copy(template = value.text) }
                    ctx.toast("Modèle enregistré")
                    onBack()
                }) { Text("Enregistrer") }
                OutlinedButton(onClick = { value = TextFieldValue(ReportGenerator.DEFAULT_TEMPLATE) }) { Text("Modèle par défaut") }
            }

            Text("Zones disponibles (touchez pour insérer)", style = MaterialTheme.typography.titleSmall)
            ReportGenerator.PLACEHOLDERS.forEach { (token, help) ->
                val first = token.split(' ').first()
                Row(Modifier.fillMaxWidth().clickable { insert(first) }.padding(vertical = 4.dp)) {
                    Text(token, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(150.dp))
                    Text(help, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }

            Text("Aperçu (envois d'aujourd'hui)", style = MaterialTheme.typography.titleSmall)
            InfoCard { Text(preview, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
