@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravito.app.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ravito.app.data.TransactionEntity
import com.ravito.app.domain.Formats
import com.ravito.app.domain.Operator
import com.ravito.app.domain.SmsParser
import com.ravito.app.domain.TxStatus
import com.ravito.app.ui.components.DatePickDialog
import com.ravito.app.ui.components.InfoCard
import com.ravito.app.ui.components.TimePickDialog
import com.ravito.app.ui.components.minutesLabel
import com.ravito.app.ui.components.ravitoTopBarColors
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.ui.components.shortDate
import com.ravito.app.ui.components.toast
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Saisie ou correction d'un envoi.
 * - [txId] : corriger un envoi existant ;
 * - [reviewId] : ajouter un SMS « À vérifier » (champs pré-remplis) ;
 * - aucun : ajout manuel.
 */
@Composable
fun EditTransactionScreen(txId: Long?, reviewId: Long?, onDone: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val operators = app.settings.current.operatorsOrdered.ifEmpty { Operator.entries }

    var loaded by remember { mutableStateOf(txId == null && reviewId == null) }
    var original by remember { mutableStateOf<TransactionEntity?>(null) }
    var rawText by remember { mutableStateOf<String?>(null) }
    var op by remember { mutableStateOf(operators.first()) }
    var amount by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(TxStatus.SUCCESS) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var minutes by remember { mutableStateOf(java.time.LocalTime.now().let { it.hour * 60 + it.minute }) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    fun setTime(ts: Long) {
        val zdt = Instant.ofEpochMilli(ts).atZone(zone)
        date = zdt.toLocalDate()
        minutes = zdt.hour * 60 + zdt.minute
    }

    LaunchedEffect(txId, reviewId) {
        if (txId != null) {
            app.repository.getTransaction(txId)?.let { t ->
                original = t
                op = t.operator
                amount = t.amount.toString()
                fee = t.fee?.toString().orEmpty()
                number = t.recipientNumber.orEmpty()
                name = t.recipientName.orEmpty()
                reference = t.reference.orEmpty()
                status = t.status
                rawText = t.rawText
                setTime(t.timestamp)
            }
        } else if (reviewId != null) {
            app.repository.getReview(reviewId)?.let { r ->
                val hint = SmsParser.extract(r.operator, r.body)
                op = r.operator
                if (hint.amount > 0) amount = hint.amount.toString()
                fee = hint.fee?.toString().orEmpty()
                number = hint.recipientNumber.orEmpty()
                name = hint.recipientName.orEmpty()
                reference = hint.reference.orEmpty()
                rawText = r.body
                setTime(r.timestamp)
            }
        }
        loaded = true
    }

    val title = when {
        txId != null -> "Corriger l'envoi"
        reviewId != null -> "Ajouter ce SMS"
        else -> "Ajouter un envoi"
    }
    val amountValue = amount.filter { it.isDigit() }.toLongOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = ravitoTopBarColors(),
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rawText?.let {
                InfoCard {
                    Text("SMS d'origine", style = MaterialTheme.typography.labelMedium)
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (op in operators) operators else operators + op).forEach { o ->
                    FilterChip(colors = com.ravito.app.ui.components.ravitoChipColors(), selected = op == o, onClick = { op = o }, label = { Text(o.label) })
                }
            }
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() } },
                label = { Text("Montant (F)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { amountValue?.let { Text(Formats.amount(it)) } },
            )
            OutlinedTextField(
                value = number, onValueChange = { number = it },
                label = { Text("Numéro du bénéficiaire") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Nom (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) { Text(date.format(shortDate)) }
                OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) { Text(minutesLabel(minutes)) }
            }
            OutlinedTextField(
                value = fee, onValueChange = { fee = it.filter { c -> c.isDigit() } },
                label = { Text("Frais (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = reference, onValueChange = { reference = it },
                label = { Text("Référence (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(colors = com.ravito.app.ui.components.ravitoChipColors(), selected = status == TxStatus.SUCCESS, onClick = { status = TxStatus.SUCCESS }, label = { Text("Réussi") })
                FilterChip(colors = com.ravito.app.ui.components.ravitoChipColors(), selected = status == TxStatus.FAILED, onClick = { status = TxStatus.FAILED }, label = { Text("Échoué") })
            }
            Button(
                enabled = amountValue != null && amountValue > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    val ts = date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
                    scope.launch {
                        val o = original
                        if (o != null) {
                            app.repository.updateTransaction(
                                o.copy(
                                    operator = op,
                                    amount = amountValue!!,
                                    fee = fee.toLongOrNull(),
                                    recipientNumber = Formats.normalizePhone(number),
                                    recipientName = name.trim().ifEmpty { null },
                                    reference = reference.trim().ifEmpty { null },
                                    status = status,
                                    timestamp = ts,
                                ),
                            )
                            ctx.toast("Envoi corrigé")
                        } else {
                            val id = app.repository.addManual(
                                op = op, amount = amountValue!!, number = number, name = name, timestamp = ts,
                                fee = fee.toLongOrNull(), reference = reference.trim(), status = status,
                                rawText = rawText, fromReviewId = reviewId,
                            )
                            ctx.toast(if (id > 0) "Envoi ajouté" else "Cet envoi existe déjà (même référence)")
                        }
                        onDone()
                    }
                },
            ) { Text("Enregistrer") }
        }
    }

    if (showDate) DatePickDialog(date, onPick = { date = it }, onDismiss = { showDate = false })
    if (showTime) TimePickDialog("Heure de l'envoi", minutes, onPick = { minutes = it }, onDismiss = { showTime = false })
}
