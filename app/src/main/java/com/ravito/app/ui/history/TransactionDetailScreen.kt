@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravito.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravito.app.domain.Formats
import com.ravito.app.domain.TxSource
import com.ravito.app.domain.TxStatus
import com.ravito.app.ui.components.ConfirmDialog
import com.ravito.app.ui.components.InfoCard
import com.ravito.app.ui.components.KeyValue
import com.ravito.app.ui.components.OperatorBadge
import com.ravito.app.ui.components.SectionTitle
import com.ravito.app.ui.components.ravitoTopBarColors
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.ui.components.toast
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fullFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm")

@Composable
fun TransactionDetailScreen(id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember(id) { app.repository.observeTransaction(id) }
    val view by flow.collectAsStateWithLifecycle(initialValue = null)
    var confirmDelete by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = ravitoTopBarColors(),
                title = { Text("Détail de l'envoi") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    IconButton(onClick = { onEdit(id) }) { Icon(Icons.Filled.Edit, "Modifier") }
                    if (view?.tx?.source == TxSource.MANUAL) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Supprimer") }
                    }
                },
            )
        },
    ) { padding ->
        val v = view
        if (v == null) {
            Text("Envoi introuvable", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val tx = v.tx
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OperatorBadge(tx.operator, size = 52)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(Formats.amount(tx.amount), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (tx.status == TxStatus.FAILED) "Échoué — non compté" else if (tx.excluded) "Exclu du rapport" else "Compté dans le rapport",
                        color = if (tx.status == TxStatus.FAILED || tx.excluded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(1.dp))
            SectionTitle("Informations", Modifier.padding(start = 0.dp))
            InfoCard {
                KeyValue("Opérateur", tx.operator.label)
                KeyValue("Type", tx.kind.label)
                KeyValue("Bénéficiaire", v.displayName ?: "—")
                KeyValue("Numéro", Formats.prettyPhone(tx.recipientNumber).ifEmpty { "—" })
                KeyValue("Frais", tx.fee?.let { Formats.amount(it) } ?: "—")
                KeyValue("Solde après", tx.balanceAfter?.let { Formats.amount(it) } ?: "—")
                KeyValue("Référence", tx.reference ?: "—")
                KeyValue("Date et heure", Instant.ofEpochMilli(tx.timestamp).atZone(ZoneId.systemDefault()).format(fullFmt))
                KeyValue("SIM", tx.simSlot?.let { "SIM $it" } ?: "—")
                KeyValue("Source", tx.source.label + if (tx.edited && tx.source != TxSource.MANUAL) " (corrigé)" else "")
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tx.status == TxStatus.SUCCESS) {
                    Button(
                        onClick = { scope.launch { app.repository.setExcluded(tx.id, !tx.excluded) } },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (tx.excluded) "Remettre dans le rapport" else "Exclure du rapport") }
                }
                if (tx.recipientNumber != null) {
                    OutlinedButton(onClick = { nameDialog = true }, modifier = Modifier.weight(1f)) {
                        Text(if (v.merchantName == null) "Nommer ce marchand" else "Renommer")
                    }
                }
            }

            if (!tx.rawText.isNullOrBlank()) {
                SectionTitle("SMS d'origine", Modifier.padding(start = 0.dp))
                InfoCard {
                    tx.sender?.let { Text("De : $it", style = MaterialTheme.typography.labelMedium) }
                    SelectionContainer {
                        Text(tx.rawText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (nameDialog) {
            var name by remember { mutableStateOf(v.displayName.orEmpty()) }
            AlertDialog(
                onDismissRequest = { nameDialog = false },
                title = { Text("Nom du marchand") },
                text = {
                    Column {
                        Text(Formats.prettyPhone(tx.recipientNumber))
                        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nom") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            app.repository.saveMerchant(tx.recipientNumber!!, name)
                            ctx.toast("Enregistré dans le carnet")
                        }
                        nameDialog = false
                    }, enabled = name.isNotBlank()) { Text("Enregistrer") }
                },
                dismissButton = { TextButton(onClick = { nameDialog = false }) { Text("Annuler") } },
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Supprimer cet envoi ?",
            text = "Cet envoi saisi à la main sera retiré de l'historique.",
            confirm = "Supprimer",
            onConfirm = {
                scope.launch {
                    app.repository.deleteTransaction(id)
                    onBack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
