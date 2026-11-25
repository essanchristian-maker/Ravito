@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.ravito.app.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.ravito.app.ui.components.ScreenHeader
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
import com.ravito.app.domain.Category
import com.ravito.app.domain.Formats
import com.ravito.app.domain.ReportGenerator
import com.ravito.app.ui.components.DatePickDialog
import com.ravito.app.ui.history.CategoryChooser
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
        /** Numéros des envois pas encore rangés (cabine / agent) -> nom connu. */
        val toClassify: List<Pair<String, String?>>,
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
                val summary = ReportGenerator.summarize(report, options)
                val names = report.associate { it.recipientNumber to it.recipientName }
                State(
                    date = d,
                    generated = ReportGenerator.generate(d, report, options, zone),
                    draft = draft?.text,
                    summary = summary,
                    pendingReview = pending,
                    toClassify = summary.unclassifiedNumbers.map { it to names[it] },
                    periodLabel = s.period.label(),
                    fullDay = s.period.isFullDay,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveDraft(text: String) = viewModelScope.launch {
        app.repository.drafts.upsert(ReportDraftEntity(date.value.toString(), text))
    }

    fun discardDraft() = viewModelScope.launch { app.repository.drafts.delete(date.value.toString()) }

    fun classify(phone: String, category: Category?) = viewModelScope.launch { app.repository.setCategory(phone, category) }
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

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ScreenHeader("Rapport") {
                IconButton(onClick = { showDate = true }) {
                    Icon(Icons.Filled.DateRange, "Choisir la date", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Choix de la date
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

            if (s.toClassify.isNotEmpty()) {
                InfoCard(Modifier.padding(vertical = 8.dp), warning = true) {
                    Text(
                        "${Formats.plural(s.toClassify.size, "numéro à classer", "numéros à classer")}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Indiquez une fois pour toutes si c'est une cabine ou un agent Mobile Money. " +
                            "Les prochains envois vers ce numéro iront directement dans la bonne rubrique.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    s.toClassify.forEach { (phone, name) ->
                        Text(
                            listOfNotNull(name?.takeIf { it.isNotBlank() }, Formats.prettyPhone(phone)).joinToString(" · "),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        CategoryChooser(null) { c -> vm.classify(phone, c) }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Envoyé", Formats.amount(s.summary.total), Modifier.weight(1f))
                StatTile("Envois", "${s.summary.count}", Modifier.weight(0.6f))
                if (s.summary.received.count > 0) {
                    StatTile("UV reçues", Formats.amount(s.summary.received.total), Modifier.weight(1f))
                }
            }
            if (s.summary.failed > 0) {
                Text(
                    "${Formats.plural(s.summary.failed, "envoi échoué", "envois échoués")} (non comptés)",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (editing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(20.dp),
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
                        // Affichage seulement : espaces insécables pour qu'un montant ne soit jamais coupé en fin de ligne.
                        Text(unbreakableAmounts(s.text), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp))
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
                Button(
                    onClick = { shareToWhatsApp(ctx, s.text) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
                ) {
                    Icon(Icons.Filled.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Partager sur WhatsApp")
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { editText = s.text; editing = true }, modifier = Modifier.weight(1f).height(50.dp)) {
                        Icon(Icons.Filled.Edit, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Modifier")
                    }
                    FilledTonalButton(onClick = { copyToClipboard(ctx, s.text); ctx.toast("Rapport copié") }, modifier = Modifier.weight(1f).height(50.dp)) {
                        Text("Copier")
                    }
                }
            }
            }
        }
    }

    if (showDate) {
        DatePickDialog(date, onPick = { editing = false; vm.date.value = if (it.isAfter(LocalDate.now())) LocalDate.now() else it }, onDismiss = { showDate = false })
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private val thousands = Regex("(\\d) (?=\\d{3}\\b)")
private val currency = Regex("(\\d) F\\b")

/** « 200 000 F » reste sur une ligne à l'écran (le texte copié ou partagé n'est pas modifié). */
private fun unbreakableAmounts(text: String): String =
    currency.replace(thousands.replace(text, "$1 "), "$1 F")
