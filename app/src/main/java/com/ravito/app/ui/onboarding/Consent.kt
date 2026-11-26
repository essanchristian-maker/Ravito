package com.ravito.app.ui.onboarding

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ravito.app.ui.components.InfoCard

/** Texte d'engagement sur les données, affiché avant la case de consentement. */
@Composable
fun DataCommitmentCard() {
    InfoCard {
        Text("Vos données et votre consentement", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("• Ravito lit uniquement les SMS de vos opérateurs mobile money (Orange, MTN, Moov, Wave).")
        Text("• Ces informations servent seulement à préparer votre historique et votre rapport du soir.")
        Text("• Vos transactions restent sur ce téléphone, dans une base chiffrée : montants, numéros et noms ne sont jamais envoyés.")
        Text("• Pour le suivi du service, Ravito envoie seulement des informations de fonctionnement : nombre d'envois lus, erreurs, version, forme des SMS non reconnus (chiffres et noms masqués).")
        Text("• Elles ne sont ni vendues, ni partagées, ni exploitées à d'autres fins.")
        Text("• Vous restez seul à décider d'envoyer votre rapport (WhatsApp, copier).")
        Text("• Désinstaller Ravito efface toutes ces données.")
    }
}

/** Case à cocher obligatoire. */
@Composable
fun ConsentCheckbox(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(
            "J'accepte que Ravito lise mes SMS mobile money sur ce téléphone, uniquement pour préparer mon rapport.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Écran affiché une fois aux personnes qui utilisaient Ravito avant l'ajout du consentement. */
@Composable
fun ConsentScreen(onAccept: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Avant de continuer", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        DataCommitmentCard()
        Spacer(Modifier.height(8.dp))
        ConsentCheckbox(checked) { checked = it }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAccept, enabled = checked, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continuer") }
    }
}
