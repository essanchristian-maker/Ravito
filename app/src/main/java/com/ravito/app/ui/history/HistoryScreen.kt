@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalCoroutinesApi::class)

package com.ravito.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.ravito.app.ui.components.CircleAction
import com.ravito.app.ui.components.GroupCard
import com.ravito.app.ui.components.RowDivider
import com.ravito.app.ui.components.ScreenHeader
import com.ravito.app.ui.theme.AmountHero
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ravito.app.RavitoApp
import com.ravito.app.data.TransactionView
import com.ravito.app.domain.Formats
import com.ravito.app.domain.Operator
import com.ravito.app.domain.ReportGenerator
import com.ravito.app.domain.TxKind
import com.ravito.app.domain.TxStatus
import com.ravito.app.ui.components.DatePickDialog
import com.ravito.app.ui.components.OperatorBadge
import com.ravito.app.ui.components.dayLabel
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.ui.components.shortDate
import com.ravito.app.ui.components.toast
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HistoryViewModel(private val app: RavitoApp) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    val date = MutableStateFlow<LocalDate?>(null)
    val operator = MutableStateFlow<Operator?>(null)
    val query = MutableStateFlow("")

    private val all = date.flatMapLatest { d ->
        if (d == null) app.repository.observeTransactions(0, Long.MAX_VALUE)
        else {
            val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            app.repository.observeTransactions(from, to)
        }
    }

    val items: StateFlow<List<TransactionView>?> = combine(all, operator, query) { list, op, q ->
        val needle = q.trim().lowercase()
        val digits = needle.filter { it.isDigit() }
        list.filter { v ->
            (op == null || v.tx.operator == op) &&
                (
                    needle.isEmpty() ||
                        (v.displayName?.lowercase()?.contains(needle) == true) ||
                        (digits.isNotEmpty() && v.tx.recipientNumber?.contains(digits) == true) ||
                        (v.tx.reference?.lowercase()?.contains(needle) == true)
                    )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Résumé d'aujourd'hui, sur la même période que le rapport. */
    val today: StateFlow<ReportGenerator.Summary?> = app.settings.state.flatMapLatest { s ->
        val range = s.period.range(LocalDate.now(zone), zone)
        app.repository.observeTransactions(range.first, range.last + 1).map { list ->
            ReportGenerator.summarize(list.map { it.toReportTx() }, s.reportOptions())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pendingReview = app.repository.observePendingReview().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val syncing = app.repository.syncing

    fun sync(onDone: (String) -> Unit) = viewModelScope.launch {
        val r = app.repository.syncInbox()
        onDone(
            when {
                r.error != null -> "Synchronisation impossible : ${r.error}"
                r.added == 0 && r.toReview == 0 -> "À jour : aucun nouvel envoi"
                else -> buildString {
                    append(Formats.plural(r.added, "envoi ajouté", "envois ajoutés"))
                    if (r.toReview > 0) append(", ${r.toReview} à vérifier")
                }
            },
        )
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun HistoryScreen(
    onOpenTransaction: (Long) -> Unit,
    onOpenReview: () -> Unit,
    onAddManual: () -> Unit,
) {
    val app = rememberApp()
    val vm: HistoryViewModel = viewModel { HistoryViewModel(app) }
    val ctx = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val pending by vm.pendingReview.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val op by vm.operator.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var showDate by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { ScreenHeader("Ravito") }

            item { TodayHero(today, settings.operatorsOrdered) }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CircleAction(Icons.Filled.Add, "Ajouter", onClick = onAddManual)
                    CircleAction(Icons.Filled.Refresh, "Synchro", busy = syncing) { vm.sync { ctx.toast(it) } }
                    CircleAction(Icons.Filled.Search, "Chercher") { showSearch = !showSearch }
                    CircleAction(Icons.Filled.Warning, "Vérifier", badge = pending, onClick = onOpenReview)
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (showSearch || query.isNotEmpty()) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { vm.query.value = it },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            placeholder = { Text("Numéro, nom ou référence") },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Clear, "Effacer") }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                        )
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Pill(date?.format(shortDate) ?: "Toutes les dates", selected = date != null) { showDate = true }
                        if (date != null) Pill("✕", selected = false) { vm.date.value = null }
                        Pill("Tous", selected = op == null) { vm.operator.value = null }
                        settings.operatorsOrdered.forEach { o ->
                            Pill(o.shortLabel, selected = op == o) { vm.operator.value = if (op == o) null else o }
                        }
                    }
                }
            }

            val list = items
            if (list == null) {
                item { Text("Chargement…", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (list.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Aucune opération", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Les envois apparaissent ici dès réception du SMS de confirmation.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                val groups = list.groupBy { Instant.ofEpochMilli(it.tx.timestamp).atZone(zone).toLocalDate() }
                groups.forEach { (day, txs) ->
                    item(key = "d$day") {
                        val ok = txs.filter { it.tx.status == TxStatus.SUCCESS && !it.tx.excluded && it.tx.kind.isSend }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(dayLabel(day), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(
                                Formats.amount(ok.sumOf { it.tx.amount }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GroupCard {
                            txs.forEachIndexed { i, v ->
                                if (i > 0) RowDivider(start = 72)
                                TransactionRow(v, zone) { onOpenTransaction(v.tx.id) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDate) {
        DatePickDialog(initial = date ?: LocalDate.now(), onPick = { vm.date.value = it }, onDismiss = { showDate = false })
    }
}

/** Puce de filtre arrondie : verte quand elle est active, blanche sinon. */
@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/** Carte d'accueil : total envoyé aujourd'hui en très grand, comme le solde dans Wave. */
@Composable
private fun TodayHero(s: ReportGenerator.Summary?, operators: List<Operator>) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(vertical = 26.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Envoyé aujourd'hui", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(Formats.amount(s?.total ?: 0), style = AmountHero, color = Color.White)
        Spacer(Modifier.height(6.dp))
        val received = s?.received?.total ?: 0
        Text(
            Formats.plural(s?.count ?: 0, "envoi") + if (received > 0) "  ·  UV reçues ${Formats.amount(received)}" else "",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (operators.size > 1) {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                operators.forEach { op ->
                    val o = s?.perOperator?.get(op)
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OperatorBadge(op, size = 24)
                        Spacer(Modifier.width(6.dp))
                        Text("${o?.count ?: 0}", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionRow(v: TransactionView, zone: ZoneId, onClick: () -> Unit) {
    val tx = v.tx
    val dimmed = tx.excluded || tx.status == TxStatus.FAILED
    val received = tx.kind == TxKind.RECEIVED
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OperatorBadge(tx.operator, size = 42)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                v.displayName ?: Formats.prettyPhone(tx.recipientNumber).ifEmpty { if (received) "Expéditeur inconnu" else "Bénéficiaire inconnu" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                add(Instant.ofEpochMilli(tx.timestamp).atZone(zone).format(timeFmt))
                if (received) add("UV reçues") else v.category?.let { add(it.label) }
                tx.simSlot?.let { add("SIM $it") }
                if (tx.status == TxStatus.FAILED) add("Échoué")
                if (tx.excluded) add("Exclu")
            }.joinToString(" · ")
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = if (tx.status == TxStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (received) "+" else "-") + Formats.amount(tx.amount),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textDecoration = if (dimmed) TextDecoration.LineThrough else null,
            color = when {
                dimmed -> MaterialTheme.colorScheme.onSurfaceVariant
                received -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
