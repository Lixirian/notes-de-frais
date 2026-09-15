package com.lixirian.notesdefrais.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.lixirian.notesdefrais.data.Category
import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.ui.appContext
import com.lixirian.notesdefrais.ui.components.CategoryBadge
import com.lixirian.notesdefrais.ui.components.Formatters
import com.lixirian.notesdefrais.ui.components.visual
import com.lixirian.notesdefrais.ui.components.ExportSheet
import com.lixirian.notesdefrais.export.ExportScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import java.io.File
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Écran « Justificatifs » : tous les tickets chargés, en grille, filtrables par année, mois,
 * catégorie et présence d'un justificatif, avec le total du filtre et son export CSV.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onTrash: () -> Unit) {
    val app = appContext()
    val viewModel: ArchiveViewModel = viewModel(factory = viewModelFactory {
        initializer { ArchiveViewModel(app.expenseRepository) }
    })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val trashCount by app.expenseRepository.observeTrashCount().collectAsStateWithLifecycle(initialValue = 0)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val filter = state.filter
    val hasFilter = filter != ArchiveFilter()
    var showExport by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Justificatifs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour") } },
                actions = {
                    IconButton(onClick = onTrash) {
                        androidx.compose.material3.BadgedBox(badge = { if (trashCount > 0) androidx.compose.material3.Badge { Text(trashCount.toString()) } }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Corbeille")
                        }
                    }
                    if (hasFilter) {
                        IconButton(onClick = viewModel::reset) { Icon(Icons.Rounded.FilterAltOff, contentDescription = "Effacer les filtres") }
                    }
                    IconButton(enabled = state.expenses.isNotEmpty(), onClick = { showExport = true }) {
                        Icon(Icons.Rounded.IosShare, contentDescription = "Exporter la sélection (ZIP ou CSV)")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        if (showExport) {
            ExportSheet(
                allExpenses = state.expenses,
                fixedScope = ExportScope.Selection(state.expenses.map { it.uid }.toSet(), state.filter.label, state.filter.fileSuffix),
                onDismiss = { showExport = false },
                onError = { msg -> showExport = false; scope.launch { snackbar.showSnackbar(msg) } },
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterRow(label = "Année") {
                        Chip("Toutes", filter.year == null) { viewModel.setYear(null) }
                        state.availableYears.forEach { y -> Chip(y.toString(), filter.year == y) { viewModel.setYear(y) } }
                    }
                    FilterRow(label = "Mois") {
                        Chip("Tous", filter.month == null) { viewModel.setMonth(null) }
                        Month.entries.forEach { m ->
                            Chip(m.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.FRENCH).replace(".", "").replaceFirstChar { it.titlecase(Locale.FRENCH) }, filter.month == m) { viewModel.setMonth(m) }
                        }
                    }
                    FilterRow(label = "Catégorie") {
                        Chip("Toutes", filter.category == null) { viewModel.setCategory(null) }
                        Category.entries.forEach { c ->
                            Chip(c.label, filter.category == c, leading = { Icon(c.visual.icon, null, tint = c.visual.tint, modifier = Modifier.size(16.dp)) }) { viewModel.setCategory(c) }
                        }
                    }
                    FilterRow(label = "Ticket") {
                        Chip("Tous", !filter.withReceiptOnly) { viewModel.setWithReceiptOnly(false) }
                        Chip("Avec justificatif", filter.withReceiptOnly, leading = { Icon(Icons.Rounded.Receipt, null, modifier = Modifier.size(16.dp)) }) { viewModel.setWithReceiptOnly(true) }
                    }
                    SummaryCard(state)
                    Spacer(Modifier.height(4.dp))
                }
            }

            if (state.loaded && state.expenses.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Aucune dépense pour ce filtre.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            items(state.expenses, key = { it.id }) { expense ->
                ReceiptCard(expense = expense, onClick = { onOpen(expense.id) })
            }
        }
    }
}

@Composable
private fun FilterRow(label: String, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { chips() }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = leading,
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun SummaryCard(state: ArchiveUiState) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.filter.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${state.expenses.size} ${if (state.expenses.size > 1) "dépenses" else "dépense"} · ${state.receiptCount} ${if (state.receiptCount > 1) "tickets" else "ticket"} · HT ${Formatters.euros(state.expenses.sumOf { it.amountHtCentsOrDerived })} · TVA ${Formatters.euros(state.vatCents)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(Formatters.euros(state.totalCents), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ReceiptCard(expense: Expense, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.88f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(expense.category.visual.container),
                contentAlignment = Alignment.Center,
            ) {
                if (expense.receiptPath != null) {
                    AsyncImage(
                        model = File(expense.receiptPath),
                        contentDescription = "Justificatif ${expense.merchant}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CategoryBadge(expense.category, size = 48.dp)
                        Text("Sans ticket", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (expense.aiExtracted) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    ) { Icon(Icons.Rounded.AutoAwesome, contentDescription = "Lu par l'IA", modifier = Modifier.padding(4.dp).size(14.dp)) }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(expense.merchant.ifBlank { "Sans commerçant" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Formatters.shortDay(expense.date)} ${expense.date.year} · ${expense.category.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(Formatters.euros(expense.amountCents), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
