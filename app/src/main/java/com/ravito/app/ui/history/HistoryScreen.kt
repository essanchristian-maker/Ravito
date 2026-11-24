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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historique") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Filled.Search, "Rechercher") }
                    if (syncing) {
                        CircularProgressIndicator(
                            Modifier.padding(12.dp).size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { vm.sync { ctx.toast(it) } }) { Icon(Icons.Filled.Refresh, "Synchroniser") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddManual) { Icon(Icons.Filled.Add, "Ajouter un envoi à la main") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            item { TodaySummary(today, settings.operatorsOrdered) }

            if (pending > 0) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onOpenReview)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${Formats.plural(pending, "SMS", "SMS")} à vérifier",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Voir", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (showSearch || query.isNotEmpty()) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { vm.query.value = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Numéro, nom ou référence") },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Clear, "Effacer") }
                                }
                            },
                        )
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = date != null,
                            onClick = { showDate = true },
                            label = { Text(date?.format(shortDate) ?: "Toutes les dates") },
                            leadingIcon = { Icon(Icons.Filled.DateRange, null, Modifier.size(18.dp)) },
                        )
                        if (date != null) {
                            TextButton(onClick = { vm.date.value = null }) { Text("Effacer") }
                        }
                        FilterChip(selected = op == null, onClick = { vm.operator.value = null }, label = { Text("Tous") })
                        settings.operatorsOrdered.forEach { o ->
                            FilterChip(
                                selected = op == o,
                                onClick = { vm.operator.value = if (op == o) null else o },
                                label = { Text(o.shortLabel) },
                            )
                        }
                    }
                }
            }

            val list = items
            if (list == null) {
                item { Text("Chargement…", Modifier.padding(24.dp)) }
            } else if (list.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Aucun envoi", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Les envois apparaissent ici dès réception du SMS de confirmation. " +
                                "Touchez ↻ pour relire la boîte de réception.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                val groups = list.groupBy { Instant.ofEpochMilli(it.tx.timestamp).atZone(zone).toLocalDate() }
                groups.forEach { (day, txs) ->
                    stickyHeader(key = "h$day") {
                        val ok = txs.filter { it.tx.status == TxStatus.SUCCESS && !it.tx.excluded }
                        Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(dayLabel(day), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                "${Formats.plural(ok.size, "envoi")} · ${Formats.amount(ok.sumOf { it.tx.amount })}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(txs, key = { it.tx.id }) { v ->
                        TransactionRow(v, zone) { onOpenTransaction(v.tx.id) }
                        HorizontalDivider(Modifier.padding(start = 68.dp))
                    }
                }
            }
        }
    }

    if (showDate) {
        DatePickDialog(initial = date ?: LocalDate.now(), onPick = { vm.date.value = it }, onDismiss = { showDate = false })
    }
}

@Composable
private fun TodaySummary(s: ReportGenerator.Summary?, operators: List<Operator>) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Text("Aujourd'hui", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
        Text(
            Formats.amount(s?.total ?: 0),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            Formats.plural(s?.count ?: 0, "envoi"),
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
        )
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            operators.forEach { op ->
                val o = s?.perOperator?.get(op)
                Row(
                    Modifier.background(Color.White.copy(alpha = 0.15f), MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OperatorBadge(op, size = 22)
                    Spacer(Modifier.width(6.dp))
                    Text("${o?.count ?: 0}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TransactionRow(v: TransactionView, zone: ZoneId, onClick: () -> Unit) {
    val tx = v.tx
    val dimmed = tx.excluded || tx.status == TxStatus.FAILED
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OperatorBadge(tx.operator)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                v.displayName ?: Formats.prettyPhone(tx.recipientNumber).ifEmpty { "Bénéficiaire inconnu" },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                add(Instant.ofEpochMilli(tx.timestamp).atZone(zone).format(timeFmt))
                if (v.displayName != null && tx.recipientNumber != null) add(Formats.prettyPhone(tx.recipientNumber))
                tx.simSlot?.let { add("SIM $it") }
                if (tx.status == TxStatus.FAILED) add("Échoué")
                if (tx.excluded) add("Exclu du rapport")
            }.joinToString(" · ")
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = if (tx.status == TxStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            Formats.amount(tx.amount),
            fontWeight = FontWeight.Bold,
            textDecoration = if (dimmed) TextDecoration.LineThrough else null,
            color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
