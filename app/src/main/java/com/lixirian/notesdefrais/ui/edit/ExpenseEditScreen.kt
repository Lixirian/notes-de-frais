package com.lixirian.notesdefrais.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.lixirian.notesdefrais.data.AiProvider
import com.lixirian.notesdefrais.data.Category
import com.lixirian.notesdefrais.data.PaymentMethod
import com.lixirian.notesdefrais.data.VatLine
import com.lixirian.notesdefrais.export.ExportScope
import com.lixirian.notesdefrais.ui.appContext
import com.lixirian.notesdefrais.ui.components.ExportSheet
import com.lixirian.notesdefrais.ui.components.Formatters
import com.lixirian.notesdefrais.ui.components.visual
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpenseEditScreen(args: EditArgs, argsKey: String, onDone: () -> Unit, onSettings: () -> Unit) {
    val app = appContext()
    val viewModel: ExpenseEditViewModel = viewModel(key = "edit:$argsKey", factory = viewModelFactory {
        initializer {
            ExpenseEditViewModel(args, app, app.expenseRepository, app.settingsRepository, app.analyzerFactory)
        }
    })
    val form = viewModel.form
    val analysis = viewModel.analysis
    val snackbar = remember { SnackbarHostState() }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var showReceiptViewer by rememberSaveable { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    val allExpenses by app.expenseRepository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

    val cancel = {
        viewModel.discardIfNew()
        onDone()
    }
    BackHandler(onBack = cancel)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isExisting) "Dépense" else "Nouvelle dépense") },
                navigationIcon = {
                    IconButton(onClick = cancel) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    if (viewModel.isExisting) {
                        IconButton(onClick = { showExport = true }) { Icon(Icons.Rounded.FolderZip, contentDescription = "Exporter ce ticket (ZIP)") }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            Surface(color = Color.Transparent) {
                Button(
                    onClick = {
                        showErrors = true
                        if (form.isValid) viewModel.save(onDone)
                    },
                    enabled = !viewModel.saving && analysis !is AnalysisState.Running && analysis !is AnalysisState.Importing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enregistrer", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            viewModel.receiptPath?.let { path ->
                ReceiptPreview(path = path, analysis = analysis, onClick = { showReceiptViewer = true })
            }

            AnalysisBanner(analysis = analysis, onRetry = viewModel::retryAnalysis, onSettings = onSettings)

            // --- Montants -----------------------------------------------------------------
            SectionCard(title = "Montants") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.amount,
                        onValueChange = { v -> viewModel.update { copy(amount = v) } },
                        label = { Text("Montant TTC") },
                        suffix = { Text("€") },
                        singleLine = true,
                        isError = showErrors && form.amountError != null,
                        supportingText = if (showErrors && form.amountError != null) ({ Text(form.amountError!!) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.amountHt,
                        onValueChange = { v -> viewModel.update { copy(amountHt = v) } },
                        label = { Text("Montant HT") },
                        suffix = { Text("€") },
                        placeholder = { Text(form.derivedHtCents?.let { Formatters.csvAmount(it) } ?: "—") },
                        singleLine = true,
                        isError = showErrors && form.amountHtError != null,
                        supportingText = { Text(if (showErrors && form.amountHtError != null) form.amountHtError!! else "Vide = TTC − TVA") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (form.vatLines.isEmpty()) {
                    OutlinedTextField(
                        value = form.vat,
                        onValueChange = { v -> viewModel.update { copy(vat = v) } },
                        label = { Text("TVA totale") },
                        suffix = { Text("€") },
                        placeholder = { Text("—") },
                        singleLine = true,
                        isError = showErrors && form.vatError != null,
                        supportingText = { Text(if (showErrors && form.vatError != null) form.vatError!! else "Ou détaillez par taux ci-dessous") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("TVA totale", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(form.vatCents?.let { Formatters.euros(it) } ?: "—", style = MaterialTheme.typography.titleMedium)
                    }
                    if (showErrors && form.vatError != null) Text(form.vatError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            // --- Détail TVA par taux --------------------------------------------------------
            SectionCard(title = "Détail de la TVA par taux", subtitle = "Tel qu'imprimé sur le ticket, s'il le détaille") {
                form.vatLines.forEachIndexed { index, line ->
                    VatLineRow(
                        line = line,
                        onChange = { transform -> viewModel.updateVatLine(index, transform) },
                        onRemove = { viewModel.removeVatLine(index) },
                    )
                }
                AssistChip(
                    onClick = viewModel::addVatLine,
                    label = { Text(if (form.vatLines.isEmpty()) "Ajouter un taux de TVA" else "Ajouter un autre taux") },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }

            // --- Informations ---------------------------------------------------------------
            SectionCard(title = "Informations") {
                DateField(label = Formatters.fullDay(form.date), onClick = { showDatePicker = true })

                OutlinedTextField(
                    value = form.merchant,
                    onValueChange = { v -> viewModel.update { copy(merchant = v) } },
                    label = { Text("Commerçant") },
                    singleLine = true,
                    isError = showErrors && form.merchantError != null,
                    supportingText = if (showErrors && form.merchantError != null) ({ Text(form.merchantError!!) }) else null,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Catégorie", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Category.entries.forEach { category ->
                        val v = category.visual
                        val selected = form.category == category
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.update { copy(category = category) } },
                            label = { Text(category.label) },
                            leadingIcon = {
                                Icon(v.icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else v.tint, modifier = Modifier.size(18.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }

                Text("Mode de paiement", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    PaymentMethod.entries.forEach { method ->
                        FilterChip(
                            selected = form.paymentMethod == method,
                            onClick = { viewModel.update { copy(paymentMethod = if (paymentMethod == method) null else method) } },
                            label = { Text(method.label) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = form.invoiceNumber,
                    onValueChange = { v -> viewModel.update { copy(invoiceNumber = v) } },
                    label = { Text("N° de ticket / facture (facultatif)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = form.note,
                    onValueChange = { v -> viewModel.update { copy(note = v) } },
                    label = { Text("Note (facultatif)") },
                    minLines = 2,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showExport) {
        val current = viewModel.currentExpense()
        if (current != null) {
            ExportSheet(
                allExpenses = allExpenses,
                fixedScope = ExportScope.Single(current.uid, "${current.merchant} · ${Formatters.shortDay(current.date)}"),
                onDismiss = { showExport = false },
                onError = { msg -> showExport = false },
            )
        }
    }

    if (showReceiptViewer) {
        viewModel.receiptPath?.let { path -> ReceiptViewer(path = path, onClose = { showReceiptViewer = false }) }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        viewModel.update { copy(date = date) }
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Annuler") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Supprimer cette dépense ?") },
            text = { Text("La dépense et son justificatif iront dans la corbeille, récupérables pendant 30 jours.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; viewModel.delete(onDone) }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Annuler") } },
        )
    }
}

/** Carte de section translucide posée sur le fond « aurore ». */
@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.82f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun VatLineRow(line: VatLineForm, onChange: ((VatLineForm.() -> VatLineForm)) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            VatLine.USUAL_RATES.forEach { rate ->
                val text = VatLineForm.rateText(rate)
                FilterChip(
                    selected = line.rate == text,
                    onClick = { onChange { copy(rate = text) } },
                    label = { Text("$text %") },
                    shape = RoundedCornerShape(10.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Retirer ce taux", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = line.rate,
                onValueChange = { v -> onChange { copy(rate = v) } },
                label = { Text("Taux") },
                suffix = { Text("%") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(0.8f),
            )
            OutlinedTextField(
                value = line.base,
                onValueChange = { v -> onChange { copy(base = v) } },
                label = { Text("Base HT") },
                suffix = { Text("€") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = line.vat,
                onValueChange = { v -> onChange { copy(vat = v) } },
                label = { Text("TVA") },
                suffix = { Text("€") },
                singleLine = true,
                isError = line.vat.isNotBlank() && line.vatCents == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReceiptPreview(path: String, analysis: AnalysisState, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = "Justificatif",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
        ) { Icon(Icons.Rounded.ZoomIn, contentDescription = "Agrandir le justificatif", modifier = Modifier.padding(6.dp).size(20.dp)) }
        if (analysis is AnalysisState.Running || analysis is AnalysisState.Importing) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        if (analysis is AnalysisState.Importing) "Préparation de la photo…" else "Lecture du ticket…",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

/** Visualiseur plein écran du ticket : pincer pour zoomer, glisser pour déplacer. */
@Composable
private fun ReceiptViewer(path: String, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = File(path),
                contentDescription = "Justificatif en plein écran",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(transformState),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)),
            ) { Icon(Icons.Rounded.Close, contentDescription = "Fermer", tint = Color.White) }
        }
    }
}

@Composable
private fun AnalysisBanner(analysis: AnalysisState, onRetry: () -> Unit, onSettings: () -> Unit) {
    AnimatedContent(targetState = analysis, label = "analysis") { state ->
        when (state) {
            AnalysisState.Idle, AnalysisState.Importing -> Unit
            is AnalysisState.Running -> InfoCard(
                icon = Icons.Rounded.AutoAwesome,
                title = "Analyse en cours par ${state.provider.label}",
                body = "Montants, TVA par taux, date, commerçant et paiement seront pré-remplis dans quelques secondes.",
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            is AnalysisState.Success -> InfoCard(
                icon = Icons.Rounded.AutoAwesome,
                title = if (state.filledFields > 0) "Champs pré-remplis par ${state.provider.label}" else "Ticket illisible pour ${state.provider.label}",
                body = if (state.filledFields > 0) "Vérifiez et corrigez si besoin avant d'enregistrer." else "Saisissez les informations à la main.",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            AnalysisState.NoKey -> InfoCard(
                icon = Icons.Rounded.Edit,
                title = "Saisie manuelle : lecture automatique inactive",
                body = "Configurez le relais NAS (abonnement Claude) ou une clé API dans les réglages pour que les champs se remplissent tout seuls. La photo est conservée comme justificatif.",
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                actionLabel = "Réglages",
                onAction = onSettings,
            )
            is AnalysisState.Failed -> InfoCard(
                icon = Icons.Rounded.ErrorOutline,
                title = "Analyse impossible",
                body = state.message,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                actionLabel = "Réessayer",
                actionIcon = Icons.Rounded.Refresh,
                onAction = onRetry,
            )
        }
    }
}

@Composable
private fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    container: Color,
    content: Color,
    actionLabel: String? = null,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                    if (actionIcon != null) {
                        Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun DateField(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text("Date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
