@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravito.app.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravito.app.RavitoApp
import com.ravito.app.domain.Operator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun rememberApp(): RavitoApp = LocalContext.current.applicationContext as RavitoApp

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Aujourd'hui"
    today.minusDays(1) -> "Hier"
    else -> date.format(dayFormatter).replaceFirstChar { it.titlecase(Locale.FRENCH) }
}

fun minutesLabel(m: Int) = "%02d:%02d".format(m / 60, m % 60)

/** Pastille de couleur qui identifie l'opérateur (pas de logo de marque). */
@Composable
fun OperatorBadge(op: Operator, size: Int = 40) {
    val bg = Color(op.color)
    val fg = if (op == Operator.MTN) Color.Black else Color.White
    Box(
        modifier = Modifier.size(size.dp).background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Taille fixe : le badge doit tenir dans son rond quelle que soit la taille de police du téléphone.
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        Text(op.badge, color = fg, fontWeight = FontWeight.Bold, fontSize = (size / 3.2 / fontScale).sp, maxLines = 1, softWrap = false)
    }
}

/** Petit titre gris au-dessus d'un groupe (style réglages iPhone). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.FRENCH),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, warning: Boolean = false, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) com.ravito.app.ui.theme.AmberSoft else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

/** Bloc blanc arrondi qui regroupe des lignes (listes, réglages). */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column { content() }
    }
}

/** Séparateur fin, décalé à gauche comme sur iPhone. */
@Composable
fun RowDivider(start: Int = 16) {
    androidx.compose.material3.HorizontalDivider(
        Modifier.padding(start = start.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Grand titre de page (style iPhone), avec des actions discrètes à droite. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Bouton rond avec libellé dessous, comme les actions de Wave. */
@Composable
fun CircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Column(modifier.width(82.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .clip(CircleShape).clickable(enabled = !busy, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    androidx.compose.material3.CircularProgressIndicator(
                        Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    androidx.compose.material3.Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                }
            }
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String = "Confirmer",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun DatePickDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) {
        DatePicker(state = state)
    }
}

@Composable
fun TimePickDialog(title: String, initialMinutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initialMinutes / 60, initialMinute = initialMinutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute); onDismiss() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))

/** Barre de titre claire, fondue dans le fond (style iPhone). */
@Composable
fun ravitoTopBarColors() = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
    titleContentColor = MaterialTheme.colorScheme.onBackground,
    navigationIconContentColor = MaterialTheme.colorScheme.primary,
    actionIconContentColor = MaterialTheme.colorScheme.primary,
)

/** Puces de choix : vertes et pleines quand elles sont choisies (bien visibles). */
@Composable
fun ravitoChipColors() = androidx.compose.material3.FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surface,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
)

/** Périodes proposées pour relire les SMS : libellé -> nombre d'heures. */
val RereadPeriods: List<Pair<String, Int>> = listOf("24 heures" to 24, "48 heures" to 48, "30 jours" to 30 * 24)

/** Trois boutons pour choisir la période à relire. */
@Composable
fun RereadPeriodButtons(modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RereadPeriods.forEach { (label, hours) ->
            androidx.compose.material3.FilledTonalButton(
                onClick = { onPick(hours) },
                modifier = Modifier.weight(1f).height(50.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
            ) { Text(label, maxLines = 1, softWrap = false) }
        }
    }
}

/** Fonction activée par le portail ? (vrai par défaut, ou tant que la licence n'est pas chargée) */
@Composable
fun rememberFeature(key: String): Boolean {
    val app = rememberApp()
    val st = app.license.state.collectAsState().value
    return (st as? com.ravito.app.license.LicenseState.Active)?.license?.feature(key) ?: true
}
