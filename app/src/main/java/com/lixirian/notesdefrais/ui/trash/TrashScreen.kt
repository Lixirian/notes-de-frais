package com.lixirian.notesdefrais.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.ui.appContext
import com.lixirian.notesdefrais.ui.components.CategoryBadge
import com.lixirian.notesdefrais.ui.components.Formatters
import kotlinx.coroutines.launch
import java.io.File

/**
 * Corbeille : dépenses supprimées, conservées avec leur justificatif pendant 30 jours.
 * Restaurer, supprimer définitivement, ou vider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val app = appContext()
    val repository = app.expenseRepository
    val items by repository.observeTrash().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<Expense?>(null) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Corbeille") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour") } },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { confirmEmpty = true }) { Icon(Icons.Rounded.DeleteSweep, contentDescription = "Vider la corbeille") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("La corbeille est vide", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Les dépenses supprimées y restent ${Expense.RETENTION_DAYS} jours avec leur justificatif, puis sont effacées.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Conservées ${Expense.RETENTION_DAYS} jours, justificatif compris, puis effacées définitivement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            items(items, key = { it.uid }) { expense ->
                TrashRow(
                    expense = expense,
                    onRestore = {
                        scope.launch { repository.restore(expense.uid); snackbar.showSnackbar("Dépense restaurée") }
                    },
                    onPurge = { confirmPurge = expense },
                )
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Vider la corbeille ?") },
            text = { Text("${items.size} ${if (items.size > 1) "dépenses et leurs justificatifs seront effacés" else "dépense et son justificatif seront effacés"} définitivement, sur cet appareil et sur le NAS.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    scope.launch { items.forEach { repository.deletePermanently(it.uid) } }
                }) { Text("Vider", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Annuler") } },
        )
    }
    confirmPurge?.let { expense ->
        AlertDialog(
            onDismissRequest = { confirmPurge = null },
            title = { Text("Supprimer définitivement ?") },
            text = { Text("« ${expense.merchant} » et son justificatif seront effacés sans possibilité de récupération.") },
            confirmButton = {
                TextButton(onClick = { confirmPurge = null; scope.launch { repository.deletePermanently(expense.uid) } }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun TrashRow(expense: Expense, onRestore: () -> Unit, onPurge: () -> Unit) {
    val dayMs = 24L * 60 * 60 * 1000
    val daysLeft = ((expense.purgeAt - System.currentTimeMillis() + dayMs - 1) / dayMs).coerceAtLeast(0)
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.88f), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (expense.receiptPath != null) {
                AsyncImage(
                    model = File(expense.receiptPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            } else {
                CategoryBadge(expense.category, size = 56.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(expense.merchant.ifBlank { "Sans commerçant" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Formatters.euros(expense.amountCents)} · ${Formatters.shortDay(expense.date)} ${expense.date.year}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (daysLeft > 0) "Effacée dans $daysLeft ${if (daysLeft > 1) "jours" else "jour"}" else "Effacement imminent",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (daysLeft <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRestore) { Icon(Icons.Rounded.RestoreFromTrash, contentDescription = "Restaurer", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onPurge) { Icon(Icons.Rounded.DeleteForever, contentDescription = "Supprimer définitivement", tint = MaterialTheme.colorScheme.error) }
        }
    }
}
