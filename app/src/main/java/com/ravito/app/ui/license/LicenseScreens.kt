package com.ravito.app.ui.license

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ravito.app.domain.Formats
import com.ravito.app.license.License
import com.ravito.app.license.LicenseManager
import com.ravito.app.license.LicenseState
import com.ravito.app.license.Release
import com.ravito.app.ui.components.InfoCard
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.update.Updater
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun date(ms: Long) = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(ms))

/** Mise en page commune : grande icône ronde, titre, texte, contenu. */
@Composable
private fun GateLayout(icon: ImageVector, tint: Color, title: String, text: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(84.dp).background(tint.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        content()
    }
}

/** Premier lancement : saisie du code d'invitation reçu par WhatsApp. */
@Composable
fun ActivationScreen(unauthorized: Boolean) {
    val app = rememberApp()
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    GateLayout(
        icon = Icons.Filled.Lock,
        tint = if (unauthorized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        title = if (unauthorized) "Copie non autorisée" else "Activez Ravito",
        text = if (unauthorized) {
            "Cette application est une licence personnelle liée à un autre téléphone. " +
                "L'utiliser sans licence est interdit. Pour obtenir votre propre accès, contactez le vendeur de Ravito."
        } else {
            "Entrez le code personnel reçu par WhatsApp. Il ne fonctionne que sur un seul téléphone. " +
                "Une connexion Internet est nécessaire pour cette étape."
        },
    ) {
        if (unauthorized) {
            InfoCard(warning = true) {
                Text("Vous avez reçu votre propre code ? Entrez-le ci-dessous.", fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase(); error = null },
            label = { Text("Code personnel (ex. KOFFI-4821)") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    when (val r = app.license.activate(code)) {
                        is LicenseManager.Result.Ok -> app.checkinNow()
                        is LicenseManager.Result.Error -> error = r.message
                    }
                    busy = false
                }
            },
            enabled = !busy && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Activer")
        }
    }
}

/** Fin de l'essai ou de l'abonnement : instructions de paiement Orange Money. */
@Composable
fun ExpiredScreen(license: License) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val om = license.omNumber.ifBlank { "07 48 86 96 03" }

    GateLayout(
        icon = Icons.Filled.Lock,
        tint = MaterialTheme.colorScheme.secondary,
        title = if (license.isTrial) "Votre essai gratuit est terminé" else "Votre abonnement a expiré",
        text = "Ravito continue d'enregistrer vos envois en arrière-plan : vous retrouverez tout dès le paiement.",
    ) {
        InfoCard {
            Text("Pour continuer", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            PayLine("1", "Envoyez ${Formats.amount(license.priceFcfa)} par Orange Money au")
            Text(
                om, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 34.dp, top = 2.dp, bottom = 8.dp),
            )
            PayLine("2", "Indiquez votre code : ${license.code}")
            Spacer(Modifier.height(8.dp))
            PayLine("3", "Appuyez sur « J'ai payé » ci-dessous. L'accès revient dès que le paiement est validé.")
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val ok = app.checkinNow()
                    busy = false
                    info = when {
                        !ok -> "Pas de connexion Internet. Réessayez quand vous avez du réseau."
                        app.license.state.value is LicenseState.Active -> null
                        else -> "Paiement pas encore validé. Réessayez dans quelques minutes."
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("J'ai payé, vérifier")
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = {
                val digits = om.filter { it.isDigit() }
                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Appeler le vendeur") }
        info?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PayLine(n: String, text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(n, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(10.dp))
        Text(text)
    }
}

@Composable
fun BlockedScreen(license: License) {
    GateLayout(
        icon = Icons.Filled.Warning,
        tint = MaterialTheme.colorScheme.error,
        title = "Accès suspendu",
        text = "L'accès à Ravito a été suspendu pour ce téléphone (code ${license.code}). " +
            "Contactez le vendeur au ${license.omNumber.ifBlank { "07 48 86 96 03" }}.",
    ) { RetryButton() }
}

@Composable
fun NeedsConnectionScreen(license: License) {
    GateLayout(
        icon = Icons.Filled.Refresh,
        tint = MaterialTheme.colorScheme.primary,
        title = "Connexion Internet requise",
        text = "Ravito doit vérifier votre abonnement au moins une fois par semaine. " +
            "Activez les données mobiles ou le Wi-Fi, puis appuyez sur Réessayer. Vos envois continuent d'être enregistrés.",
    ) { RetryButton() }
}

@Composable
private fun RetryButton() {
    val app = rememberApp()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    Button(
        onClick = {
            busy = true
            scope.launch {
                info = if (app.checkinNow()) null else "Toujours pas de connexion."
                busy = false
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) { Text(if (busy) "Vérification…" else "Réessayer") }
    info?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Bandeaux de l'accueil : jours d'essai restants, message du vendeur, mise à jour disponible. */
@Composable
fun LicenseBanners(state: LicenseState, latest: Release?, updater: Updater) {
    val active = state as? LicenseState.Active ?: return
    val lic = active.license
    val scope = rememberCoroutineScope()
    val upd by updater.state.collectAsState()
    var askPermission by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (updater.isNewer(latest)) {
            val r = latest!!
            Banner(
                title = "Nouvelle version ${r.versionName} disponible",
                text = when (val u = upd) {
                    is Updater.State.Downloading -> "Téléchargement… ${u.percent} %"
                    is Updater.State.Error -> u.message
                    else -> "Appuyez ici pour mettre à jour. Vos données sont conservées."
                },
                color = MaterialTheme.colorScheme.primaryContainer,
                progress = (upd as? Updater.State.Downloading)?.percent,
            ) {
                if (!updater.canInstall()) askPermission = true
                else scope.launch { updater.downloadAndInstall(r) }
            }
        }
        val showDays = lic.isTrial || active.daysLeft <= 5 || active.inGrace
        if (showDays) {
            val text = when {
                active.inGrace -> "Paiement attendu : ${Formats.amount(lic.priceFcfa)} par Orange Money au ${lic.omNumber} (code ${lic.code})."
                lic.isTrial -> "Il reste ${Formats.plural(active.daysLeft, "jour")} (jusqu'au ${date(lic.expiresAt)}). Ensuite ${Formats.amount(lic.priceFcfa)} par mois."
                else -> "Votre abonnement se termine le ${date(lic.expiresAt)}. Renouvelez : ${Formats.amount(lic.priceFcfa)} par Orange Money au ${lic.omNumber}."
            }
            Banner(
                title = when {
                    active.inGrace -> "Abonnement expiré"
                    lic.isTrial -> "Essai gratuit"
                    else -> "Abonnement : ${Formats.plural(active.daysLeft, "jour")} restants"
                },
                text = text,
                color = if (active.inGrace || active.daysLeft <= 3) com.ravito.app.ui.theme.AmberSoft else Color.White,
            )
        }
        if (lic.notice.isNotBlank()) Banner("Message", lic.notice, Color.White)
    }

    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            title = { Text("Autoriser les mises à jour") },
            text = { Text("Une seule fois : Android demande d'autoriser Ravito à installer ses mises à jour. Activez l'interrupteur, puis revenez et appuyez à nouveau sur la mise à jour.") },
            confirmButton = { TextButton(onClick = { askPermission = false; updater.openInstallPermission() }) { Text("Ouvrir le réglage") } },
            dismissButton = { TextButton(onClick = { askPermission = false }) { Text("Plus tard") } },
        )
    }
}

@Composable
private fun Banner(title: String, text: String, color: Color, progress: Int? = null, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth()
            .background(color, RoundedCornerShape(18.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}
