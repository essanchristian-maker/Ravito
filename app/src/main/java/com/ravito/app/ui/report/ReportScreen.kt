@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.ravito.app.ui.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ravito.app.RavitoApp
import com.ravito.app.data.ReportDraftEntity
import com.ravito.app.domain.Formats
import com.ravito.app.domain.ReportGenerator
import com.ravito.app.ui.components.DatePickDialog
import com.ravito.app.ui.components.InfoCard
import com.ravito.app.ui.components.dayLabel
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.ui.components.toast
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class ReportViewModel(private val app: RavitoApp) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    val date = MutableStateFlow(LocalDate.now())

    data class State(
        val date: LocalDate,
        val generated: String,
        val draft: String?,
        val summary: ReportGenerator.Summary,
        val pendingReview: Int,
        val periodLabel: String,
        val fullDay: Boolean,
    ) {
        val text: String get() = draft ?: generated
    }

    val state: StateFlow<State?> = combine(date, app.settings.state) { d, s -> d to s }
        .flatMapLatest { (d, s) ->
            val range = s.period.range(d, zone)
            combine(
                app.repository.observeTransactions(range.first, range.last + 1),
                app.repository.drafts.observe(d.toString()),
                app.repository.observePendingReviewCount(range.first, range.last + 1),
            ) { txs, draft, pending ->
                val report = txs.map { it.toReportTx() }
                val options = s.reportOptions()
                State(
                    date = d,
                    generated = ReportGenerator.generate(d, report, options, zone),
                    draft = draft?.text,
                    summary = ReportGenerator.summarize(report, options),
                    pendingReview = pending,
                    periodLabel = s.period.label(),
                    fullDay = s.period.isFullDay,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveDraft(text: String) = viewModelScope.launch {
        app.repository.drafts.upsert(ReportDraftEntity(date.value.toString(), text))
    }

    fun discardDraft() = viewModelScope.launch { app.repository.drafts.delete(date.value.toString()) }
}

fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Rapport Ravito", text))
}

/** Ouvre WhatsApp (ou WhatsApp Business) avec le texte ; sinon le menu de partage Android. */
fun shareToWhatsApp(ctx: Context, text: String) {
    val base = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val pm = ctx.packageManager
    val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { p ->
        try {
            pm.getPackageInfo(p, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    try {
        if (pkg != null) ctx.startActivity(Intent(base).setPackage(pkg))
        else ctx.startActivity(Intent.createChooser(base, "Partager le rapport"))
    } catch (e: Exception) {
        ctx.startActivity(Intent.createChooser(base, "Partager le rapport"))
    }
}

@Composable
fun ReportScreen(onOpenReview: () -> Unit) {
    val app = rememberApp()
    val vm: ReportViewModel = viewModel { ReportViewModel(app) }
    val ctx = LocalContext.current
    val st by vm.state.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var editText by rememberSaveable { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rapport") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    IconButton(onClick = { showDate = true }) { Icon(Icons.Filled.DateRange, "Choisir la date") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // Choix de la date
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { editing = false; vm.date.value = date.minusDays(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Jour précédent")
                }
                Column(Modifier.weight(1f).clickable { showDate = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayLabel(date), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    st?.let { if (!it.fullDay) Text("Période ${it.periodLabel}", style = MaterialTheme.typography.bodySmall) }
                }
                IconButton(
                    onClick = { editing = false; vm.date.value = date.plusDays(1) },
                    enabled = date.isBefore(LocalDate.now()),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Jour suivant")
                }
            }

            val s = st ?: return@Column

            if (s.pendingReview > 0) {
                InfoCard(Modifier.padding(vertical = 8.dp).clickable(onClick = onOpenReview), warning = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${Formats.plural(s.pendingReview, "SMS non reconnu", "SMS non reconnus")} ce jour-là",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("Ils ne sont pas dans le rapport. Touchez pour vérifier.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Total", style = MaterialTheme.typography.labelMedium)
                    Text(Formats.amount(s.summary.total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Envois", style = MaterialTheme.typography.labelMedium)
                    Text("${s.summary.count}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (s.summary.failed > 0) {
                    Column {
                        Text("Échoués", style = MaterialTheme.typography.labelMedium)
                        Text("${s.summary.failed}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (editing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.saveDraft(editText)
                        editing = false
                        ctx.toast("Modifications enregistrées")
                    }) {
                        Icon(Icons.Filled.Check, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Valider")
                    }
                    OutlinedButton(onClick = { editing = false }) { Text("Annuler") }
                }
            } else {
                InfoCard {
                    SelectionContainer {
                        Text(s.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (s.draft != null) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Texte modifié à la main." + if (s.draft != s.generated) " L'historique a peut-être changé depuis." else "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.discardDraft() }) {
                            Icon(Icons.Filled.Refresh, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Régénérer")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { editText = s.text; editing = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Edit, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Modifier")
                    }
                    Button(onClick = { copyToClipboard(ctx, s.text); ctx.toast("Rapport copié") }, modifier = Modifier.weight(1f)) {
                        Text("Copier")
                    }
                }
                Button(
                    onClick = { shareToWhatsApp(ctx, s.text) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Icon(Icons.Filled.Share, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Partager sur WhatsApp")
                }
            }
        }
    }

    if (showDate) {
        DatePickDialog(date, onPick = { editing = false; vm.date.value = if (it.isAfter(LocalDate.now())) LocalDate.now() else it }, onDismiss = { showDate = false })
    }
}
