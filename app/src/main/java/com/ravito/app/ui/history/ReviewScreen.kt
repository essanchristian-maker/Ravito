@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravito.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravito.app.ui.components.InfoCard
import com.ravito.app.ui.components.OperatorBadge
import com.ravito.app.ui.components.ravitoTopBarColors
import com.ravito.app.ui.components.rememberApp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/** SMS d'opérateur au format inconnu : le marchand les ajoute à la main ou les ignore. */
@Composable
fun ReviewScreen(onBack: () -> Unit, onAdd: (Long) -> Unit) {
    val app = rememberApp()
    val scope = rememberCoroutineScope()
    val flow = remember { app.repository.observePendingReview() }
    val items by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val zone = remember { ZoneId.systemDefault() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = ravitoTopBarColors(),
                title = { Text("À vérifier") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Ces SMS viennent de vos opérateurs mais leur format n'est pas reconnu. " +
                        "Ajoutez-les s'il s'agit d'un envoi, sinon ignorez-les.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isEmpty()) {
                item { Text("Rien à vérifier.", Modifier.padding(top = 24.dp), fontWeight = FontWeight.SemiBold) }
            }
            items(items, key = { it.id }) { r ->
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OperatorBadge(r.operator, size = 28)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(r.sender ?: r.operator.label, fontWeight = FontWeight.SemiBold)
                            Text(Instant.ofEpochMilli(r.timestamp).atZone(zone).format(fmt), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(r.body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAdd(r.id) }) { Text("Ajouter comme envoi") }
                        OutlinedButton(onClick = { scope.launch { app.repository.dismissReview(r.id) } }) { Text("Ignorer") }
                    }
                }
            }
        }
    }
}
