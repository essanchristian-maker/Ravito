@file:OptIn(ExperimentalMaterial3Api::class)

package com.ravito.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravito.app.data.MerchantEntity
import com.ravito.app.domain.Formats
import com.ravito.app.ui.components.ConfirmDialog
import com.ravito.app.ui.components.ravitoTopBarColors
import com.ravito.app.ui.components.rememberApp
import com.ravito.app.ui.components.toast
import kotlinx.coroutines.launch

/** Carnet des marchands : numéro -> nom affiché dans l'historique et le rapport. */
@Composable
fun MerchantsScreen(onBack: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember { app.repository.observeMerchants() }
    val merchants by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MerchantEntity?>(null) }
    var toDelete by remember { mutableStateOf<MerchantEntity?>(null) }

    // Le sélecteur de contacts donne accès au seul contact choisi (pas besoin de l'autorisation Contacts).
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            val picked = app.repository.readPickedPhone(uri)
            if (picked != null) editing = MerchantEntity(picked.second, picked.first)
            else ctx.toast("Numéro illisible")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = ravitoTopBarColors(),
                title = { Text("Carnet des marchands") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    IconButton(onClick = {
                        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    }) { Icon(Icons.Filled.Person, "Ajouter depuis les contacts") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = MerchantEntity("", "") }) { Icon(Icons.Filled.Add, "Ajouter") }
        },
    ) { padding ->
        val filtered = merchants.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) || it.phone.contains(query.filter(Char::isDigit).ifEmpty { "\u0000" })
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Rechercher") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            if (merchants.isEmpty()) {
                item {
                    Text(
                        "Ajoutez les marchands que vous ravitaillez pour voir leur nom dans le rapport " +
                            "(« Boutique Awa (0102030405) »). Vous pouvez aussi nommer un numéro depuis le détail d'un envoi.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filtered, key = { it.phone }) { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = m }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontWeight = FontWeight.SemiBold)
                        Text(Formats.prettyPhone(m.phone), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { toDelete = m }) { Icon(Icons.Filled.Delete, "Supprimer") }
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { m ->
        var phone by remember(m) { mutableStateOf(m.phone) }
        var name by remember(m) { mutableStateOf(m.name) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (m.phone.isEmpty()) "Nouveau marchand" else "Marchand") },
            text = {
                Column {
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it }, singleLine = true, label = { Text("Numéro") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nom") },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && Formats.normalizePhone(phone) != null,
                    onClick = {
                        scope.launch {
                            val newPhone = Formats.normalizePhone(phone)
                            if (m.phone.isNotEmpty() && m.phone != newPhone) app.repository.deleteMerchant(m.phone)
                            app.repository.saveMerchant(phone, name)
                        }
                        editing = null
                    },
                ) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } },
        )
    }

    toDelete?.let { m ->
        ConfirmDialog(
            title = "Supprimer ${m.name} ?",
            text = "Le numéro ${Formats.prettyPhone(m.phone)} s'affichera sans nom.",
            confirm = "Supprimer",
            onConfirm = { scope.launch { app.repository.deleteMerchant(m.phone) } },
            onDismiss = { toDelete = null },
        )
    }
}
