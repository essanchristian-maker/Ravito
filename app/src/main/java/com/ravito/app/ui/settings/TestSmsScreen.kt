@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravito.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ravito.app.data.RavitoRepository
import com.ravito.app.domain.Formats
import com.ravito.app.domain.Operator
import com.ravito.app.domain.SmsParser
import com.ravito.app.domain.TxKind
import com.ravito.app.domain.TxStatus
import com.ravito.app.ui.components.InfoCard
import com.ravito.app.ui.components.KeyValue
import com.ravito.app.ui.components.ravitoTopBarColors
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.ui.components.toast
import kotlinx.coroutines.launch

/**
 * Coller le texte d'un SMS (copié depuis Messages) pour voir ce que Ravito en comprend,
 * puis l'ajouter à l'historique si on veut le voir dans le rapport.
 */
@Composable
fun TestSmsScreen(onBack: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var op by rememberSaveable { mutableStateOf(Operator.MTN) }
    val result = remember(text, op) { if (text.isBlank()) null else SmsParser.parse(op, text) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = ravitoTopBarColors(),
                title = { Text("Tester un SMS") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Dans Messages, appuyez longuement sur un SMS → Copier, puis collez-le ici.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Opérateur", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Operator.entries.forEach { o ->
                    FilterChip(colors = com.ravito.app.ui.components.ravitoChipColors(), selected = o == op, onClick = { op = o }, label = { Text(o.shortLabel) })
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Texte du SMS") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { text = it } }) { Text("Coller") }
                OutlinedButton(onClick = { text = "" }) { Text("Effacer") }
            }

            when (val r = result) {
                null -> {}
                is SmsParser.Result.Ignored -> InfoCard(Modifier.padding(top = 16.dp), warning = true) {
                    Text("Message ignoré", fontWeight = FontWeight.SemiBold)
                    Text(
                        when (r.reason) {
                            SmsParser.IgnoreReason.RECEIVED -> "Entrée d'argent qui ne vient pas d'une personne (remboursement, bonus…)."
                            SmsParser.IgnoreReason.CODE -> "Code de confirmation."
                            SmsParser.IgnoreReason.PROMO -> "Publicité ou achat de forfait."
                            SmsParser.IgnoreReason.NO_AMOUNT -> "Aucun montant trouvé."
                            SmsParser.IgnoreReason.INFO -> "Simple information de solde."
                        },
                    )
                }
                is SmsParser.Result.Unrecognized -> InfoCard(Modifier.padding(top = 16.dp), warning = true) {
                    Text("Format inconnu", fontWeight = FontWeight.SemiBold)
                    Text("Ce SMS irait dans « À vérifier ». Envoyez-le au développeur pour l'ajouter.")
                }
                is SmsParser.Result.Transaction -> {
                    val t = r.tx
                    val received = t.kind == TxKind.RECEIVED
                    InfoCard(Modifier.padding(top = 16.dp)) {
                        Text(
                            when {
                                received -> "Reconnu : UV reçues (rubrique 3)"
                                t.status == TxStatus.FAILED -> "Reconnu : envoi ÉCHOUÉ (non compté)"
                                else -> "Reconnu : envoi"
                            },
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        KeyValue("Montant", Formats.amount(t.amount))
                        KeyValue(if (received) "Expéditeur" else "Bénéficiaire", Formats.prettyPhone(t.recipientNumber).ifEmpty { "—" })
                        t.recipientName?.let { KeyValue("Nom", it) }
                        KeyValue("Frais", t.fee?.let { Formats.amount(it) } ?: "—")
                        KeyValue("Solde après", t.balanceAfter?.let { Formats.amount(it) } ?: "—")
                        KeyValue("Référence", t.reference ?: "—")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val out = app.repository.addTestSms(op, text)
                                ctx.toast(
                                    when (out) {
                                        RavitoRepository.Outcome.ADDED -> "Ajouté à l'historique d'aujourd'hui"
                                        RavitoRepository.Outcome.DUPLICATE -> "Déjà dans l'historique"
                                        else -> "Non ajouté"
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("Ajouter à l'historique") }
                }
            }
        }
    }
}
