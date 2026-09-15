package com.lixirian.notesdefrais.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.export.CsvExporter
import com.lixirian.notesdefrais.export.ExportScope
import com.lixirian.notesdefrais.export.ZipExporter
import kotlinx.coroutines.launch

/**
 * Feuille d'export : choix de la période (semaine, mois, tout, ou une sélection imposée) et du
 * format (ZIP avec un dossier par ticket + images, ou CSV seul), puis partage Android.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportSheet(
    allExpenses: List<Expense>,
    /** Période imposée (sélection filtrée, dépense unique) ; sinon l'utilisateur choisit. */
    fixedScope: ExportScope? = null,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val choices = remember { listOf(ExportScope.thisWeek(), ExportScope.lastWeek(), ExportScope.thisMonth(), ExportScope.lastMonth(), ExportScope.All) }
    var selected by remember { mutableStateOf<ExportScope>(fixedScope ?: choices[2]) }
    var busy by remember { mutableStateOf(false) }
    val included = remember(selected, allExpenses) { allExpenses.filter(selected::matches).sortedBy { it.dateEpochDay } }

    fun run(zip: Boolean) {
        if (busy || included.isEmpty()) return
        busy = true
        scope.launch {
            runCatching {
                val base = "notes-de-frais-${selected.fileSuffix}"
                if (zip) {
                    val uri = ZipExporter.export(context, base, selected.label, included)
                    ZipExporter.shareIntent(uri, "Notes de frais — ${selected.label}")
                } else {
                    val uri = CsvExporter.export(context, base, included)
                    CsvExporter.shareIntent(uri, "Notes de frais — ${selected.label}")
                }
            }.onSuccess { intent -> context.startActivity(intent); onDismiss() }
                .onFailure { onError("Export impossible : ${it.message}") }
            busy = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Exporter", style = MaterialTheme.typography.titleLarge)
            if (fixedScope == null) {
                Text("Période", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { choice ->
                        FilterChip(
                            selected = selected == choice,
                            onClick = { selected = choice },
                            label = { Text(shortLabel(choice)) },
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }
            }
            Text(
                "${selected.label} · ${included.size} ${if (included.size > 1) "dépenses" else "dépense"} · ${Formatters.euros(included.sumOf { it.amountCents })} · ${included.count { it.receiptPath != null }} ${if (included.count { it.receiptPath != null } > 1) "tickets" else "ticket"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = { run(zip = true) }, enabled = !busy && included.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                if (busy) CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Rounded.FolderZip, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Archive ZIP", style = MaterialTheme.typography.titleSmall)
                    Text("Un dossier par ticket : image + données, et le CSV", style = MaterialTheme.typography.labelSmall)
                }
            }
            OutlinedButton(onClick = { run(zip = false) }, enabled = !busy && included.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.TableChart, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Tableau CSV seul", style = MaterialTheme.typography.titleSmall)
                    Text("Pour Excel : une ligne par dépense, TVA par taux", style = MaterialTheme.typography.labelSmall)
                }
            }
            Row { Spacer(Modifier.height(4.dp)) }
        }
    }
}

private fun shortLabel(scope: ExportScope): String = when (scope) {
    is ExportScope.Week -> if (scope == ExportScope.thisWeek()) "Cette semaine" else "Semaine dernière"
    is ExportScope.Month -> if (scope == ExportScope.thisMonth()) "Ce mois" else "Mois dernier"
    ExportScope.All -> "Tout"
    is ExportScope.Single -> scope.title
    is ExportScope.Selection -> scope.label
}
