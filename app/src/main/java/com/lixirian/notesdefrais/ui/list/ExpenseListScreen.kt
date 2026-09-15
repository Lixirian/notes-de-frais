package com.lixirian.notesdefrais.ui.list

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lixirian.notesdefrais.ai.ReceiptImages
import com.lixirian.notesdefrais.data.AiProvider
import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.ui.appContext
import com.lixirian.notesdefrais.ui.components.CategoryBadge
import com.lixirian.notesdefrais.ui.components.Formatters
import com.lixirian.notesdefrais.ui.components.ExportSheet
import com.lixirian.notesdefrais.ui.components.UpdateBanner
import com.lixirian.notesdefrais.ui.components.UpdateSheet
import com.lixirian.notesdefrais.ui.components.WhatsNewDialog
import androidx.compose.runtime.LaunchedEffect
import com.lixirian.notesdefrais.export.ExportScope
import com.lixirian.notesdefrais.ui.theme.AmountTextStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ExpenseListScreen(
    onAddManual: () -> Unit,
    onAddFromImage: (Uri) -> Unit,
    onOpen: (Long) -> Unit,
    onArchive: () -> Unit,
    onSettings: () -> Unit,
) {
    val app = appContext()
    val viewModel: ExpenseListViewModel = viewModel(factory = viewModelFactory {
        initializer { ExpenseListViewModel(app.expenseRepository, app.settingsRepository) }
    })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var exportScope by remember { mutableStateOf<ExportScope?>(null) }
    var showExportChooser by rememberSaveable { mutableStateOf(false) }
    val allExpenses = state.groups.flatMap { it.expenses }
    var cameraTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var showUpdateSheet by rememberSaveable { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { if (app.updateManager.consumeWhatsNew()) whatsNew = app.updateManager.currentReleaseNotes() }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        app.appLock.endExternalActivity()
        val target = cameraTarget
        if (ok && target != null) onAddFromImage(Uri.parse(target))
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        app.appLock.endExternalActivity()
        if (uri != null) onAddFromImage(uri)
    }
    val securityConfig by app.securityRepository.config.collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text("Mes notes de frais") },
                actions = {
                    IconButton(onClick = { showExportChooser = true }) { Icon(Icons.Rounded.Download, contentDescription = "Exporter (ZIP ou CSV)") }
                    IconButton(onClick = onArchive) { Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Justificatifs") }
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Réglages") }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Ajouter") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 96.dp,
            ),
        ) {
            item(key = "hero") {
                HeroCard(state = state, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item(key = "update") {
                UpdateBanner(manager = app.updateManager, onClick = { showUpdateSheet = true }, modifier = Modifier.padding(horizontal = 16.dp))
            }
            item(key = "pairing") {
                PairingBanner(onClick = onSettings, modifier = Modifier.padding(horizontal = 16.dp))
            }
            item(key = "ai-status") {
                AiStatusRow(provider = state.aiProvider, onClick = onSettings, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (securityConfig?.hasPin == false) {
                item(key = "security-hint") {
                    SecurityHintRow(onClick = onSettings, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            if (state.isEmpty) {
                item(key = "empty") { EmptyState(modifier = Modifier.padding(32.dp)) }
            }

            state.groups.forEach { group ->
                stickyHeader(key = "header-${group.month}") {
                    MonthHeader(
                        group = group,
                        onExport = { exportScope = ExportScope.Month(group.month) },
                    )
                }
                itemsIndexed(group.expenses, key = { _, e -> e.id }) { index, expense ->
                    val shape = rowShape(index, group.expenses.size)
                    Box(modifier = Modifier.animateItem().padding(horizontal = 16.dp).padding(bottom = 2.dp)) {
                        DismissibleExpenseRow(
                            expense = expense,
                            shape = shape,
                            onClick = { onOpen(expense.id) },
                            onDelete = {
                                viewModel.delete(expense) { deleted ->
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Déplacée dans la corbeille (30 jours)",
                                            actionLabel = "Annuler",
                                        )
                                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(deleted)
                                    }
                                }
                            },
                        )
                    }
                }
                item(key = "spacer-${group.month}") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (showUpdateSheet) {
        UpdateSheet(manager = app.updateManager, onDismiss = { showUpdateSheet = false })
    }
    whatsNew?.let { notes ->
        WhatsNewDialog(version = app.updateManager.currentVersion, notes = notes, onDismiss = { whatsNew = null })
    }
    if (showExportChooser) {
        ExportSheet(allExpenses = allExpenses, onDismiss = { showExportChooser = false }, onError = { msg -> showExportChooser = false; scope.launch { snackbarHostState.showSnackbar(msg) } })
    }
    exportScope?.let { fixed ->
        ExportSheet(allExpenses = allExpenses, fixedScope = fixed, onDismiss = { exportScope = null }, onError = { msg -> exportScope = null; scope.launch { snackbarHostState.showSnackbar(msg) } })
    }

    if (showAddSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }, sheetState = sheetState) {
            AddOptions(
                provider = state.aiProvider,
                onCamera = {
                    showAddSheet = false
                    val uri = ReceiptImages.newCameraTarget(context)
                    cameraTarget = uri.toString()
                    app.appLock.beginExternalActivity()
                    takePicture.launch(uri)
                },
                onGallery = {
                    showAddSheet = false
                    app.appLock.beginExternalActivity()
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onManual = {
                    showAddSheet = false
                    onAddManual()
                },
            )
        }
    }
}

private fun rowShape(index: Int, count: Int): RoundedCornerShape {
    val big = 22.dp
    val small = 6.dp
    val top = if (index == 0) big else small
    val bottom = if (index == count - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun HeroCard(state: ListUiState, modifier: Modifier = Modifier) {
    val group = state.currentGroup
    val total = group?.totalCents ?: 0L
    val count = group?.expenses?.size ?: 0
    val vat = group?.vatCents ?: 0L
    // Couleurs fixes (pas celles du thème) : le dégradé reste vif et lisible en clair comme en sombre.
    val gradient = Brush.linearGradient(
        colors = listOf(Color(0xFF5B4CF5), Color(0xFF8B5CF6), Color(0xFFEC4899)),
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(gradient)
            .padding(22.dp),
    ) {
        Column {
            Text(
                text = Formatters.monthLabel(state.currentMonth),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(6.dp))
            Text(text = Formatters.euros(total), style = AmountTextStyle, color = Color.White)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStat(label = if (count > 1) "dépenses" else "dépense", value = count.toString())
                HeroStat(label = "de TVA", value = Formatters.euros(vat))
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String) {
    Surface(color = Color.White.copy(alpha = 0.18f), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun AiStatusRow(provider: AiProvider?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (text, container, content) = when (provider) {
        AiProvider.ANTHROPIC -> Triple("Lecture des tickets par Claude", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        AiProvider.OPENAI -> Triple("Lecture des tickets par OpenAI", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        AiProvider.RELAY -> Triple("Lecture des tickets via le relais NAS", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        null -> Triple("Mode manuel — aucune clé API configurée", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    }
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (provider != null) Icons.Rounded.AutoAwesome else Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("Réglages", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Un autre appareil demande l'accès au relais : visible dès l'accueil, l'autorisation se fait dans Réglages. */
@Composable
private fun PairingBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val app = appContext()
    val pending by app.syncEngine.pendingPairings.collectAsStateWithLifecycle()
    if (pending.isEmpty()) return
    val first = pending.first()
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (pending.size == 1) "« ${first.device} » demande l'accès · code ${first.codeLabel}" else "${pending.size} appareils demandent l'accès", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("Autoriser", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SecurityHintRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Protégez l'app avec un code et l'empreinte", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("Activer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MonthHeader(group: MonthGroup, onExport: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.82f))
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(Formatters.monthLabel(group.month), style = MaterialTheme.typography.titleLarge)
            Text(
                "${group.expenses.size} ${if (group.expenses.size > 1) "dépenses" else "dépense"} · ${Formatters.euros(group.totalCents)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onExport) {
            Icon(Icons.Rounded.IosShare, contentDescription = "Exporter le mois en CSV", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DismissibleExpenseRow(expense: Expense, shape: RoundedCornerShape, onClick: () -> Unit, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete(); true
            } else false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        ExpenseRow(expense = expense, shape = shape, onClick = onClick)
    }
}

@Composable
private fun ExpenseRow(expense: Expense, shape: RoundedCornerShape, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.88f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryBadge(expense.category)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    expense.merchant.ifBlank { "Sans commerçant" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${Formatters.shortDay(expense.date)} · ${expense.category.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatters.euros(expense.amountCents), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (expense.aiExtracted) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = "Pré-rempli par l'IA",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Text(
                        expense.vatCents?.let { "TVA ${Formatters.euros(it)}" } ?: "TVA —",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("Aucune dépense pour l'instant", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Photographiez un ticket ou saisissez une dépense avec le bouton Ajouter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun AddOptions(provider: AiProvider?, onCamera: () -> Unit, onGallery: () -> Unit, onManual: () -> Unit) {
    Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(
            "Nouvelle dépense",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Text(
            when (provider) {
                AiProvider.ANTHROPIC -> "La photo du ticket sera lue par Claude pour pré-remplir les champs."
                AiProvider.OPENAI -> "La photo du ticket sera lue par OpenAI pour pré-remplir les champs."
                AiProvider.RELAY -> "La photo du ticket sera lue via votre relais NAS pour pré-remplir les champs."
                null -> "Aucune clé API : la photo est conservée comme justificatif, les champs sont à saisir à la main."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        SheetOption(Icons.Rounded.PhotoCamera, "Prendre en photo", "Ouvre l'appareil photo", onCamera)
        SheetOption(Icons.Rounded.PhotoLibrary, "Choisir dans la photothèque", "Ticket déjà photographié", onGallery)
        SheetOption(Icons.Rounded.Edit, "Saisie manuelle", "Sans justificatif", onManual)
    }
}

@Composable
private fun SheetOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Box(
                modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}
