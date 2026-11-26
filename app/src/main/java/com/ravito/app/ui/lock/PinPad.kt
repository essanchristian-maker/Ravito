package com.ravito.app.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravito.app.ui.components.rememberApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Clavier à 4 chiffres. [onComplete] renvoie true si le code est accepté ; sinon le champ est vidé.
 */
@Composable
fun PinPad(
    title: String,
    subtitle: String? = null,
    error: String? = null,
    extraAction: (@Composable () -> Unit)? = null,
    onComplete: (String) -> Boolean,
) {
    var pin by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(4) { i ->
                val filled = i < pin.length
                Box(
                    Modifier.size(18.dp)
                        .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(error ?: " ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { key ->
                    if (key.isEmpty()) {
                        Box(Modifier.size(72.dp))
                    } else {
                        FilledTonalButton(
                            onClick = {
                                if (key == "⌫") {
                                    pin = pin.dropLast(1)
                                } else if (pin.length < 4) {
                                    pin += key
                                    if (pin.length == 4) {
                                        val entered = pin
                                        pin = ""
                                        onComplete(entered)
                                    }
                                }
                            },
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                        ) {
                            Text(key, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        if (extraAction != null) {
            Spacer(Modifier.height(12.dp))
            extraAction()
        }
    }
}

@Composable
fun LockScreen(biometricAvailable: Boolean, onBiometric: () -> Unit, onUnlocked: () -> Unit) {
    val app = rememberApp()
    var error by remember { mutableStateOf<String?>(null) }
    var wait by remember { mutableStateOf(app.pin.waitSeconds()) }
    var forgot by remember { mutableStateOf(false) }
    var newPinStep by remember { mutableStateOf(0) } // 0 = déverrouillage, 1 = nouveau PIN, 2 = confirmation
    var firstPin by remember { mutableStateOf("") }

    if (newPinStep > 0) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            androidx.compose.runtime.key(newPinStep) {
                PinPad(
                    title = if (newPinStep == 1) "Nouveau code PIN" else "Confirmez le code PIN",
                    subtitle = "4 chiffres. Vos données sont conservées.",
                    error = error,
                ) { pin ->
                    if (newPinStep == 1) { firstPin = pin; error = null; newPinStep = 2; true }
                    else if (pin == firstPin) { app.pin.setPin(pin); error = null; newPinStep = 0; onUnlocked(); true }
                    else { error = "Les deux codes sont différents, recommencez."; newPinStep = 1; false }
                }
            }
        }
        return
    }

    if (forgot) {
        ForgotPinDialog(onDismiss = { forgot = false }) { forgot = false; error = null; newPinStep = 1 }
    }

    LaunchedEffect(biometricAvailable) { if (biometricAvailable) onBiometric() }
    LaunchedEffect(wait) {
        if (wait > 0) {
            delay(1000)
            wait = app.pin.waitSeconds()
            error = if (wait > 0) "Trop d'essais. Réessayez dans $wait s" else null
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        PinPad(
            title = "Ravito",
            subtitle = "Entrez votre code PIN",
            error = error,
            extraAction = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (biometricAvailable) TextButton(onClick = onBiometric) { Text("Utiliser l'empreinte digitale") }
                    TextButton(onClick = { forgot = true }) { Text("Code PIN oublié ?") }
                }
            },
        ) { pin ->
            if (app.pin.waitSeconds() > 0) {
                wait = app.pin.waitSeconds()
                false
            } else if (app.pin.check(pin)) {
                error = null
                onUnlocked()
                true
            } else {
                wait = app.pin.waitSeconds()
                error = if (wait > 0) "Trop d'essais. Réessayez dans $wait s" else "Code incorrect"
                false
            }
        }
    }
}

/**
 * Code PIN oublié : la personne entre son code personnel Ravito (reçu par WhatsApp)
 * ou le code à 6 chiffres envoyé par le vendeur. Internet nécessaire.
 */
@Composable
private fun ForgotPinDialog(onDismiss: () -> Unit, onVerified: () -> Unit) {
    val app = rememberApp()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Code PIN oublié") },
        text = {
            Column {
                Text("Entrez votre code personnel Ravito (ex. KOFFI-4821, reçu par WhatsApp) ou le code à 6 chiffres envoyé par le vendeur.")
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.uppercase(); err = null },
                    singleLine = true,
                    label = { Text("Code") },
                    modifier = Modifier.padding(top = 12.dp),
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && value.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    when (val r = app.license.verifyPinReset(value)) {
                        is com.ravito.app.license.LicenseManager.Result.Ok -> onVerified()
                        is com.ravito.app.license.LicenseManager.Result.Error -> err = r.message
                    }
                    busy = false
                }
            }) { Text(if (busy) "Vérification…" else "Valider") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
