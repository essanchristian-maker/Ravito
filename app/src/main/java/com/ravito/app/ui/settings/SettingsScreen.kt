@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravito.app.ui.settings

import com.ravito.app.ui.components.GroupCard
import com.ravito.app.ui.components.ScreenHeader
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravito.app.BuildConfig
import com.ravito.app.domain.Operator
import com.ravito.app.domain.ReportPeriod
import com.ravito.app.notif.WaveNotificationListener
import com.ravito.app.ui.components.OperatorBadge
import com.ravito.app.ui.components.SectionTitle
import com.ravito.app.ui.components.TimePickDialog
import com.ravito.app.ui.components.minutesLabel
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.ui.components.toast
import com.ravito.app.ui.lock.PinPad
import com.ravito.app.ui.onboarding.isIgnoringBatteryOptimizations
import com.ravito.app.ui.onboarding.openAppDetails
import com.ravito.app.ui.onboarding.openBatteryOptimizationRequest
import com.ravito.app.ui.onboarding.openNotificationAccess
import com.ravito.app.work.Scheduler
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

private val simLabels = listOf("Automatique", "SIM 1", "SIM 2", "Application")

@Composable
fun SettingsScreen(onOpenMerchants: () -> Unit, onOpenTemplate: () -> Unit, onOpenTestSms: () -> Unit = {}) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.state.collectAsStateWithLifecycle()
    val syncing by app.repository.syncing.collectAsStateWithLifecycle()

    var refresh by remember { mutableIntStateOf(0) }
    val batteryOk = remember(refresh) { ctx.isIgnoringBatteryOptimizations() }
    val notifAccess = remember(refresh) { WaveNotificationListener.isEnabled(ctx) }
    val biometricAvailable = remember {
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    var sendersFor by remember { mutableStateOf<Operator?>(null) }
    var pickTime by remember { mutableStateOf<String?>(null) }
    var changePin by remember { mutableStateOf(false) }
    var rereadDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { app.repository.exportCsv(it) }
                    ctx.toast("Export terminé")
                } catch (e: Exception) {
                    ctx.toast("Export impossible : ${e.message}")
                }
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ScreenHeader("Paramètres")

            // ---------------------------------------------------------- Opérateurs
            SectionTitle("Opérateurs actifs et SIM")
            GroupCard {
            Operator.entries.forEach { op ->
                val active = op in s.enabledOperators
                var simMenu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OperatorBadge(op, size = 34)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(op.label, fontWeight = FontWeight.SemiBold)
                        if (active) {
                            Row {
                                Box {
                                    Text(
                                        simLabels[s.simByOperator[op] ?: 0] + " ▾",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.clickable { simMenu = true }.padding(vertical = 4.dp),
                                    )
                                    DropdownMenu(expanded = simMenu, onDismissRequest = { simMenu = false }) {
                                        simLabels.forEachIndexed { i, label ->
                                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                                app.settings.update { it.copy(simByOperator = it.simByOperator + (op to i)) }
                                                simMenu = false
                                            })
                                        }
                                    }
                                }
                                Text(
                                    "  ·  Expéditeurs",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.clickable { sendersFor = op }.padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Switch(checked = active, onCheckedChange = { on ->
                        app.settings.update {
                            it.copy(enabledOperators = if (on) it.enabledOperators + op else it.enabledOperators - op)
                        }
                    })
                }
            }
            if (Operator.WAVE in s.enabledOperators && !notifAccess) {
                SettingRow(
                    "Accès aux notifications (Wave)",
                    "Non accordé : les notifications Wave ne sont pas lues. Touchez pour autoriser.",
                ) { ctx.openNotificationAccess() }
            }

            }
            // ---------------------------------------------------------- Rapport
            SectionTitle("Rapport")
            GroupCard {
            SettingRow("Tester un SMS", "Coller un SMS pour voir ce que Ravito en comprend", onClick = onOpenTestSms)
            SettingRow("Modèle de rapport", "Rubriques cabines, agents, UV reçues ; zones comme {date}, {total}", onClick = onOpenTemplate)
            SettingRow("Période du rapport", "De ${minutesLabel(s.period.startMinutes)} à ${minutesLabel(s.period.endMinutes)}") {
                pickTime = "start"
            }
            SwitchRow("Compter les frais dans le total", s.includeFees) { v -> app.settings.update { it.copy(includeFees = v) } }
            SwitchRow("Afficher le solde de fin de journée", s.showBalances) { v -> app.settings.update { it.copy(showBalances = v) } }
            SettingRow("Carnet des marchands", "Numéro → nom affiché dans le rapport", onClick = onOpenMerchants)

            }
            // ---------------------------------------------------------- Rappel
            SectionTitle("Rappel du soir")
            GroupCard {
            SwitchRow("Rappel « Votre rapport est prêt »", s.reminderEnabled) { v ->
                app.settings.update { it.copy(reminderEnabled = v) }
                Scheduler.scheduleReminder(ctx)
            }
            if (s.reminderEnabled) {
                SettingRow("Heure du rappel", minutesLabel(s.reminderMinutes)) { pickTime = "reminder" }
            }

            }
            // ---------------------------------------------------------- Sécurité
            SectionTitle("Sécurité")
            GroupCard {
            SettingRow("Changer le code PIN", null) { changePin = true }
            SwitchRow("Autoriser captures et partage d'écran", s.allowScreenCapture) { v ->
                app.settings.update { it.copy(allowScreenCapture = v) }
            }
            if (biometricAvailable) {
                SwitchRow("Déverrouiller avec l'empreinte digitale", s.biometricEnabled) { v ->
                    app.settings.update { it.copy(biometricEnabled = v) }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Verrouiller après")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Immédiatement", 60 to "1 min", 300 to "5 min", 900 to "15 min").forEach { (sec, label) ->
                        FilterChip(
                            colors = com.ravito.app.ui.components.ravitoChipColors(),
                            selected = s.lockTimeoutSeconds == sec,
                            onClick = { app.settings.update { it.copy(lockTimeoutSeconds = sec) } },
                            label = { Text(label) },
                        )
                    }
                }
            }

            }
            // ---------------------------------------------------------- Données
            SectionTitle("Données")
            GroupCard {
            if (s.consentAt > 0) {
                SettingRow(
                    "Vos données restent sur ce téléphone",
                    "Base chiffrée, rien n'est envoyé sur Internet. Consentement donné le " +
                        java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date(s.consentAt)) + ".",
                ) {}
            }
            SettingRow("Exporter (fichier Excel / CSV)", "Enregistrer tout l'historique dans un fichier") {
                exportLauncher.launch("ravito-${LocalDate.now()}.csv")
            }
            SettingRow("Partager l'export", "Envoyer le fichier CSV (WhatsApp, e-mail, Drive…)") {
                scope.launch {
                    try {
                        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                        val file = File(dir, "ravito-${LocalDate.now()}.csv")
                        file.outputStream().use { app.repository.exportCsv(it) }
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(intent, "Partager l'export"))
                    } catch (e: Exception) {
                        ctx.toast("Export impossible : ${e.message}")
                    }
                }
            }
            SettingRow(
                "Relire les SMS",
                if (syncing) "Lecture en cours…" else "24 h, 48 h ou 30 jours : récupère les envois manquants (sans doublon)",
            ) { if (!syncing) rereadDialog = true }

            }
            // ---------------------------------------------------------- Arrière-plan
            SectionTitle("Fonctionnement en arrière-plan")
            GroupCard {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                SettingRow(
                    "Garder les autorisations",
                    "Désactivez « Suspendre l'activité si l'appli n'est pas utilisée » pour qu'Android ne retire jamais l'accès aux SMS",
                ) {
                    try {
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_AUTO_REVOKE_PERMISSIONS,
                                android.net.Uri.fromParts("package", ctx.packageName, null),
                            ),
                        )
                    } catch (e: Exception) {
                        ctx.openAppDetails()
                    }
                }
            }
            SettingRow(
                "Économie de batterie",
                if (batteryOk) "Ravito n'est pas limité ✓" else "Ravito est limité : touchez pour le retirer de l'économie de batterie",
            ) {
                ctx.openBatteryOptimizationRequest()
                refresh++
            }
            SettingRow(
                "Démarrage automatique",
                "Tecno, Infinix, itel, Xiaomi : autorisez le démarrage automatique de Ravito dans les paramètres de l'application",
            ) { ctx.openAppDetails() }
            TextButton(onClick = { refresh++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Actualiser l'état") }

            }
            Text(
                "Ravito ${BuildConfig.VERSION_NAME} · données stockées uniquement sur ce téléphone, base chiffrée.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    // ---------------------------------------------------------- Dialogues
    if (rereadDialog) {
        AlertDialog(
            onDismissRequest = { rereadDialog = false },
            title = { Text("Relire les SMS") },
            text = {
                Column {
                    Text("Quelle période voulez-vous relire ? Les envois déjà enregistrés ne sont pas ajoutés deux fois.")
                    com.ravito.app.ui.components.RereadPeriodButtons(Modifier.padding(top = 16.dp)) { hours ->
                        rereadDialog = false
                        scope.launch {
                            val r = app.repository.syncInbox(fullHours = hours)
                            ctx.toast(r.error?.let { "Erreur : $it" } ?: "${r.scanned} SMS lus, ${r.added} envois ajoutés")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { rereadDialog = false }) { Text("Annuler") } },
        )
    }
    sendersFor?.let { op ->
        var text by remember(op) { mutableStateOf((s.extraSenders[op] ?: emptyList()).joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { sendersFor = null },
            title = { Text("Expéditeurs ${op.label}") },
            text = {
                Column {
                    Text("Reconnus par défaut : ${op.defaultSenders.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Autres expéditeurs (séparés par des virgules)") },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Ajoutez ici le nom exact affiché comme expéditeur des SMS si votre opérateur en utilise un autre.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    app.settings.update { it.copy(extraSenders = it.extraSenders + (op to list)) }
                    sendersFor = null
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { sendersFor = null }) { Text("Annuler") } },
        )
    }

    when (pickTime) {
        "start" -> TimePickDialog("Début de la période", s.period.startMinutes, onPick = { v ->
            app.settings.update { it.copy(period = ReportPeriod(v, it.period.endMinutes)) }
            pickTime = "end"
        }, onDismiss = { if (pickTime == "start") pickTime = null })
        "end" -> TimePickDialog("Fin de la période", s.period.endMinutes, onPick = { v ->
            app.settings.update { it.copy(period = ReportPeriod(it.period.startMinutes, v)) }
        }, onDismiss = { pickTime = null })
        "reminder" -> TimePickDialog("Heure du rappel", s.reminderMinutes, onPick = { v ->
            app.settings.update { it.copy(reminderMinutes = v) }
            Scheduler.scheduleReminder(ctx)
        }, onDismiss = { pickTime = null })
    }

    if (changePin) ChangePinDialog(onDone = { changePin = false })
}

@Composable
private fun ChangePinDialog(onDone: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var newPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PinPad(
                    title = when (step) {
                        0 -> "Code PIN actuel"
                        1 -> "Nouveau code PIN"
                        else -> "Confirmez le nouveau code"
                    },
                    error = error,
                    extraAction = { TextButton(onClick = onDone) { Text("Annuler") } },
                ) { pin ->
                    when (step) {
                        0 -> if (app.pin.check(pin)) { step = 1; error = null; true } else {
                            val w = app.pin.waitSeconds()
                            error = if (w > 0) "Trop d'essais. Réessayez dans $w s" else "Code incorrect"
                            false
                        }
                        1 -> { newPin = pin; step = 2; error = null; true }
                        else -> if (pin == newPin) {
                            app.pin.setPin(pin)
                            ctx.toast("Code PIN modifié")
                            onDone()
                            true
                        } else {
                            step = 1
                            error = "Les codes ne correspondent pas"
                            false
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
