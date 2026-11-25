package com.ravito.app.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import com.ravito.app.data.RavitoRepository
import com.ravito.app.domain.Operator
import com.ravito.app.notif.WaveNotificationListener
import com.ravito.app.ui.components.InfoCard
import com.ravito.app.ui.components.OperatorBadge
import com.ravito.app.ui.components.VSpace
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.ui.lock.PinPad
import com.ravito.app.work.Scheduler
import kotlinx.coroutines.launch

private const val STEPS = 5

fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
fun Context.hasSmsPermission() = hasPermission(Manifest.permission.READ_SMS) && hasPermission(Manifest.permission.RECEIVE_SMS)

fun Context.isIgnoringBatteryOptimizations(): Boolean =
    (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

fun Context.openBatteryOptimizationRequest() {
    try {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    } catch (e: Exception) {
        openAppDetails()
    }
}

fun Context.openAppDetails() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
}

fun Context.openNotificationAccess() {
    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val app = rememberApp()
    var step by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        LinearProgressIndicator(
            progress = { (step + 1f) / STEPS },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            when (step) {
                0 -> WelcomeStep { step = 1 }
                1 -> PermissionsStep { step = 2 }
                2 -> OperatorsStep { step = 3 }
                3 -> PinStep { step = 4 }
                else -> FirstSyncStep {
                    app.settings.update { it.copy(onboardingDone = true) }
                    Scheduler.scheduleAll(app)
                    onDone()
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(next: () -> Unit) {
    VSpace(32)
    Text("Bienvenue sur Ravito", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    VSpace(16)
    Text(
        "Ravito prépare tout seul votre rapport du soir à partir des SMS de confirmation de vos envois mobile money.",
        style = MaterialTheme.typography.bodyLarge,
    )
    VSpace(20)
    InfoCard {
        Text("Ravito n'envoie aucun SMS et ne fait aucune transaction.", fontWeight = FontWeight.SemiBold)
        VSpace(8)
        Text("• Vos codes secrets ne sont jamais demandés.")
        Text("• L'ouverture est protégée par un code PIN.")
    }
    VSpace(12)
    DataCommitmentCard()
    var consent by remember { mutableStateOf(false) }
    ConsentCheckbox(consent) { consent = it }
    VSpace(8)
    val app = rememberApp()
    Button(
        onClick = {
            app.settings.update { it.copy(consentAt = System.currentTimeMillis()) }
            next()
        },
        enabled = consent,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Commencer") }
    VSpace(24)
}

@Composable
private fun PermissionsStep(next: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val smsOk = remember(refresh) { ctx.hasSmsPermission() }
    val notifOk = remember(refresh) {
        Build.VERSION.SDK_INT < 33 || ctx.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    }
    val contactsOk = remember(refresh) { ctx.hasPermission(Manifest.permission.READ_CONTACTS) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    VSpace(24)
    Text("Autorisations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    VSpace(16)
    PermissionRow("SMS (obligatoire)", "Lire les SMS de confirmation d'envoi de vos opérateurs.", smsOk) {
        launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_STATE))
    }
    if (Build.VERSION.SDK_INT >= 33) {
        PermissionRow("Notifications", "Pour le rappel du soir « Votre rapport est prêt ».", notifOk) {
            launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    PermissionRow("Contacts (facultatif)", "Afficher le nom des marchands enregistrés dans vos contacts.", contactsOk) {
        launcher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
    }
    VSpace(24)
    if (!smsOk) {
        Text(
            "Sans l'autorisation SMS, Ravito ne peut pas fonctionner. Si Android ne propose plus la fenêtre, ouvrez les paramètres de l'application.",
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { ctx.openAppDetails() }) { Text("Ouvrir les paramètres de l'application") }
    }
    Button(onClick = next, enabled = smsOk, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continuer") }
}

@Composable
private fun PermissionRow(title: String, text: String, granted: Boolean, onRequest: () -> Unit) {
    InfoCard(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Accordée", tint = MaterialTheme.colorScheme.primary)
            } else {
                OutlinedButton(onClick = onRequest) { Text("Autoriser") }
            }
        }
    }
}

@Composable
private fun OperatorsStep(next: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf(app.settings.current.enabledOperators) }
    var refresh by remember { mutableIntStateOf(0) }
    val waveAccess = remember(refresh) { WaveNotificationListener.isEnabled(ctx) }

    VSpace(24)
    Text("Vos opérateurs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    VSpace(8)
    Text("Cochez les comptes mobile money utilisés sur ce téléphone.")
    VSpace(12)
    Operator.entries.forEach { op ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OperatorBadge(op)
            Spacer(Modifier.width(12.dp))
            Text(op.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Checkbox(
                checked = op in selected,
                onCheckedChange = { c -> selected = if (c) selected + op else selected - op },
            )
        }
    }
    if (Operator.WAVE in selected) {
        VSpace(8)
        InfoCard(warning = !waveAccess) {
            Text("Wave", fontWeight = FontWeight.SemiBold)
            Text(
                if (waveAccess) "Accès aux notifications accordé : les envois Wave seront lus."
                else "Si Wave n'envoie pas de SMS, Ravito lit sa notification. Autorisez l'accès aux notifications pour Ravito.",
            )
            if (!waveAccess) {
                TextButton(onClick = { ctx.openNotificationAccess() }) { Text("Autoriser l'accès aux notifications") }
                TextButton(onClick = { refresh++ }) { Text("J'ai autorisé, vérifier") }
            }
        }
    }
    VSpace(24)
    Button(
        onClick = {
            app.settings.update { it.copy(enabledOperators = selected) }
            next()
        },
        enabled = selected.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Continuer") }
}

@Composable
private fun PinStep(next: () -> Unit) {
    val app = rememberApp()
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    VSpace(12)
    PinPad(
        title = if (first == null) "Créez votre code PIN" else "Confirmez le code PIN",
        subtitle = "4 chiffres, demandés à chaque ouverture de Ravito.",
        error = error,
    ) { pin ->
        val f = first
        if (f == null) {
            first = pin
            error = null
            true
        } else if (f == pin) {
            app.pin.setPin(pin)
            next()
            true
        } else {
            first = null
            error = "Les deux codes sont différents, recommencez."
            false
        }
    }
}

@Composable
private fun FirstSyncStep(done: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<RavitoRepository.SyncReport?>(null) }
    var batteryRefresh by remember { mutableIntStateOf(0) }
    val batteryOk = remember(batteryRefresh) { ctx.isIgnoringBatteryOptimizations() }

    LaunchedEffect(Unit) {
        result = app.repository.syncInbox(fullDays = app.settings.current.firstSyncDays)
    }

    VSpace(24)
    Text("Première lecture des SMS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    VSpace(16)
    val r = result
    if (r == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 16.dp))
            Text("Lecture des SMS des ${app.settings.current.firstSyncDays} derniers jours…")
        }
    } else if (r.error != null) {
        Text("Lecture impossible : ${r.error}", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { scope.launch { result = null; result = app.repository.syncInbox(fullDays = 30) } }) { Text("Réessayer") }
    } else {
        InfoCard {
            Text("${r.scanned} SMS mobile money lus", fontWeight = FontWeight.SemiBold)
            Text("${r.added} envois ajoutés à l'historique")
            if (r.toReview > 0) Text("${r.toReview} SMS à vérifier (format inconnu)")
        }
    }
    VSpace(20)
    InfoCard(warning = !batteryOk) {
        Text("Fonctionnement en arrière-plan", fontWeight = FontWeight.SemiBold)
        VSpace(4)
        Text(
            if (batteryOk) "Ravito n'est pas limité par l'économie de batterie."
            else "Sur Tecno, Infinix, itel et Xiaomi, l'économie de batterie ferme les applications. " +
                "Retirez Ravito de l'économie de batterie pour lire les envois en direct. " +
                "(Pas d'inquiétude : la relecture des SMS rattrape tout envoi manqué.)",
        )
        if (!batteryOk) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { ctx.openBatteryOptimizationRequest() }) { Text("Retirer de l'économie") }
                TextButton(onClick = { batteryRefresh++ }) { Text("Vérifier") }
            }
            Text(
                "Sur certains téléphones, activez aussi « Démarrage automatique » pour Ravito dans les paramètres de l'application.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { ctx.openAppDetails() }) { Text("Paramètres de l'application") }
        }
    }
    VSpace(24)
    Button(onClick = done, enabled = result != null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("Ouvrir l'historique")
    }
    VSpace(24)
}
