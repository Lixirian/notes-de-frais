package com.lixirian.notesdefrais.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lixirian.notesdefrais.ai.AnthropicReceiptAnalyzer
import com.lixirian.notesdefrais.ai.OpenAiReceiptAnalyzer
import com.lixirian.notesdefrais.data.AiConfig
import com.lixirian.notesdefrais.data.AiProvider
import com.lixirian.notesdefrais.data.KeySource
import com.lixirian.notesdefrais.data.SecurityConfig
import com.lixirian.notesdefrais.sync.SyncState
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import com.lixirian.notesdefrais.data.RelayPairing
import com.lixirian.notesdefrais.update.UpdateManager
import com.lixirian.notesdefrais.update.UpdateState
import com.lixirian.notesdefrais.ui.components.UpdateSheet
import com.lixirian.notesdefrais.ui.components.SmallSpinner
import com.lixirian.notesdefrais.ui.components.WhatsNewDialog
import java.text.DateFormat
import com.lixirian.notesdefrais.ui.appContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = appContext()
    val settings = app.settingsRepository
    val config by settings.config.collectAsStateWithLifecycle(initialValue = null)
    val securityConfig by app.securityRepository.config.collectAsStateWithLifecycle(initialValue = SecurityConfig())
    val syncStatus by app.syncEngine.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var anthropicInput by rememberSaveable { mutableStateOf<String?>(null) }
    var openAiInput by rememberSaveable { mutableStateOf<String?>(null) }
    var relayUrlInput by rememberSaveable { mutableStateOf<String?>(null) }
    var relayTokenInput by rememberSaveable { mutableStateOf<String?>(null) }
    var pairCodeInput by rememberSaveable { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    val relayUrlError = relayUrlInput?.let { com.lixirian.notesdefrais.data.SettingsRepository.validateRelayUrl(it.trim()) }

    // Pré-remplit les champs avec les valeurs déjà saisies dans l'app (jamais avec la clé du .env).
    LaunchedEffect(config) {
        val c = config ?: return@LaunchedEffect
        if (anthropicInput == null) anthropicInput = if (c.anthropicSource == KeySource.APP_SETTINGS) c.anthropicKey else ""
        if (openAiInput == null) openAiInput = if (c.openAiSource == KeySource.APP_SETTINGS) c.openAiKey else ""
        if (relayUrlInput == null) relayUrlInput = c.relayUrl
        if (relayTokenInput == null || (relayTokenInput.isNullOrBlank() && c.relayToken.isNotBlank())) relayTokenInput = c.relayToken
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                config?.let { StatusCard(it) }

                SectionTitle("Sécurité")
                SecuritySection(
                    security = app.securityRepository,
                    config = securityConfig,
                    onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                )

                SectionTitle("Stockage NAS")
                val syncActive = config?.provider == AiProvider.RELAY
                Surface(
                    color = when { !syncActive -> MaterialTheme.colorScheme.surfaceContainerHigh; syncStatus.state is SyncState.Error -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.secondaryContainer },
                    contentColor = when { !syncActive -> MaterialTheme.colorScheme.onSurface; syncStatus.state is SyncState.Error -> MaterialTheme.colorScheme.onErrorContainer; else -> MaterialTheme.colorScheme.onSecondaryContainer },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(if (syncActive && syncStatus.state !is SyncState.Error) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(28.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (syncActive) "Dépenses et justificatifs stockés sur le NAS" else "Stockage local uniquement", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    !syncActive -> "Configurez le relais NAS ci-dessous : chaque appareil connecté récupère alors toutes les données."
                                    syncStatus.state is SyncState.Running -> "Synchronisation en cours…"
                                    syncStatus.state is SyncState.Error -> (syncStatus.state as SyncState.Error).message
                                    syncStatus.lastSuccessAt != null -> "Dernière synchronisation : " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(syncStatus.lastSuccessAt!!))
                                    else -> "Pas encore synchronisé."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (syncActive) {
                            IconButton(enabled = syncStatus.state !is SyncState.Running, onClick = { scope.launch { app.syncEngine.syncNow()?.let { snackbar.showSnackbar(it) } } }) {
                                Icon(Icons.Rounded.Sync, contentDescription = "Synchroniser maintenant")
                            }
                        }
                    }
                }

                SectionTitle("Relais NAS (recommandé)")
                Text(
                    "Le téléphone n'embarque aucune clé API : il appelle votre NAS avec un jeton longue durée, " +
                        "et c'est le NAS qui détient la clé (voir tools/nas-relay). Si un relais est configuré, il est utilisé en priorité. " +
                        "Pour associer cet appareil : saisissez l'URL puis « Demander l'accès ».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = relayUrlInput.orEmpty(),
                    onValueChange = { relayUrlInput = it },
                    label = { Text("URL du relais") },
                    placeholder = { Text("https://nas.exemple.fr:8787") },
                    singleLine = true,
                    isError = relayUrlError != null,
                    supportingText = { Text(relayUrlError ?: "https:// obligatoire hors du réseau local ; http:// toléré vers une adresse privée (192.168.x.x…)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                val hasToken = !relayTokenInput.isNullOrBlank()
                if (!hasToken) {
                    AccessRequestCard(
                        relayUrl = relayUrlInput.orEmpty(),
                        urlValid = relayUrlInput.orEmpty().isNotBlank() && relayUrlError == null,
                        onPaired = { token -> relayTokenInput = token; app.syncEngine.requestSync(300) },
                        onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    )
                } else {
                    PendingRequestsCard(onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
                }
                TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Masquer les options avancées" else "Options avancées (code d'appairage, jeton)") }
                if (showAdvanced) {
                    // Appairage par code court : « relayctl.sh pair » sur le NAS affiche 8 chiffres valables 10 min.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = pairCodeInput,
                            onValueChange = { v -> pairCodeInput = v.filter { it.isDigit() }.take(RelayPairing.CODE_LENGTH) },
                            label = { Text("Code d'appairage") },
                            placeholder = { Text("8 chiffres") },
                            singleLine = true,
                            supportingText = { Text("Sur le NAS : relayctl.sh pair (valable 10 min)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = !pairing && relayUrlInput.orEmpty().isNotBlank() && relayUrlError == null && pairCodeInput.length == RelayPairing.CODE_LENGTH,
                            onClick = {
                                pairing = true
                                scope.launch {
                                    val result = runCatching { settings.pairRelay(relayUrlInput.orEmpty(), pairCodeInput) }
                                    pairing = false
                                    result.onSuccess { token ->
                                        relayTokenInput = token; pairCodeInput = ""
                                        app.syncEngine.requestSync(300)
                                        snackbar.showSnackbar("Relais associé : jeton reçu et enregistré")
                                    }.onFailure { e -> snackbar.showSnackbar(e.message ?: "Appairage impossible") }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) { if (pairing) SmallSpinner() else Text("Associer") }
                    }
                    KeyField(
                        label = "Jeton du relais",
                        value = relayTokenInput.orEmpty(),
                        onValueChange = { relayTokenInput = it },
                        reveal = reveal,
                        onToggleReveal = { reveal = !reveal },
                        hint = if (hasToken) "Jeton en place (reçu par appairage ou saisi). Le vider puis Enregistrer dissocie cet appareil." else "Rempli automatiquement par l'appairage ; saisie manuelle possible (relayctl.sh showrelaytoken)",
                    )
                }

                SectionTitle("Clés API directes")
                Text(
                    "La clé saisie ici remplace celle du fichier .env embarquée à la compilation. " +
                        "Anthropic est utilisé en priorité si les deux clés sont présentes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KeyField(
                    label = "ANTHROPIC_API_KEY",
                    value = anthropicInput.orEmpty(),
                    onValueChange = { anthropicInput = it },
                    reveal = reveal,
                    onToggleReveal = { reveal = !reveal },
                    hint = config?.let { sourceHint(it.anthropicSource, AiProvider.ANTHROPIC) }.orEmpty(),
                )
                KeyField(
                    label = "OPENAI_API_KEY",
                    value = openAiInput.orEmpty(),
                    onValueChange = { openAiInput = it },
                    reveal = reveal,
                    onToggleReveal = { reveal = !reveal },
                    hint = config?.let { sourceHint(it.openAiSource, AiProvider.OPENAI) }.orEmpty(),
                )

                Button(
                    enabled = relayUrlError == null,
                    onClick = {
                        scope.launch {
                            runCatching {
                                settings.setRelay(relayUrlInput.orEmpty(), relayTokenInput.orEmpty())
                                settings.setAnthropicKey(anthropicInput.orEmpty())
                                settings.setOpenAiKey(openAiInput.orEmpty())
                            }.onSuccess { app.syncEngine.requestSync(0); snackbar.showSnackbar("Réglages enregistrés (secrets chiffrés dans le Keystore)") }
                                .onFailure { snackbar.showSnackbar(it.message ?: "Réglages invalides") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Enregistrer") }

                Spacer(Modifier.height(8.dp))
                SectionTitle("Mises à jour")
                UpdateSection()

                Spacer(Modifier.height(8.dp))
                SectionTitle("Modèles utilisés")
                Text(
                    "Anthropic : ${AnthropicReceiptAnalyzer.DEFAULT_MODEL}\nOpenAI : ${OpenAiReceiptAnalyzer.DEFAULT_MODEL}\n\n" +
                        "Les photos de tickets sont réduites à 1 600 px puis envoyées au fournisseur choisi. " +
                        "Les dépenses et les justificatifs sont stockés sur ce téléphone et, si le relais est configuré, sur votre NAS (récupérés automatiquement sur tout nouvel appareil connecté).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Réglages > Mises à jour : version installée, état de la dernière vérification, vérification
 * manuelle, vérification automatique au lancement, notes de la version installée.
 */
@Composable
private fun UpdateSection() {
    val app = appContext()
    val manager = app.updateManager
    val state by manager.state.collectAsStateWithLifecycle()
    val prefs by manager.prefs.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf<List<String>?>(null) }
    val pending = when (val s = state) {
        is UpdateState.Available -> s.manifest
        is UpdateState.Downloading -> s.manifest
        is UpdateState.ReadyToInstall -> s.manifest
        is UpdateState.Error -> s.manifest
        else -> null
    }

    Surface(
        color = if (pending != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (pending != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(28.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (pending != null) "Version ${pending.version} disponible" else "Version ${manager.currentVersion} installée",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when (val s = state) {
                            is UpdateState.Checking -> "Vérification en cours…"
                            is UpdateState.UpToDate -> "À jour · vérifié à ${DateFormat.getTimeInstance(DateFormat.SHORT).format(s.checkedAt)}"
                            is UpdateState.Error -> s.message.ifBlank { "Dernière vérification impossible (hors ligne ?)" }
                            is UpdateState.Available -> "Nouvelle version publiée sur GitHub"
                            is UpdateState.Downloading -> "Téléchargement… ${(s.progress * 100).toInt()} %"
                            is UpdateState.ReadyToInstall -> "Téléchargée et vérifiée, prête à installer"
                            UpdateState.Idle -> prefs?.lastCheck?.takeIf { it > 0 }?.let { "Dernière vérification : ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it)}" } ?: "Pas encore vérifié"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pending != null) {
                    Button(onClick = { showSheet = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("Installer la ${pending.version}") }
                } else {
                    OutlinedButton(
                        onClick = { scope.launch { manager.check(manual = true) } },
                        enabled = state !is UpdateState.Checking,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state is UpdateState.Checking) SmallSpinner() else Text("Vérifier maintenant")
                    }
                }
                OutlinedButton(onClick = { scope.launch { showNotes = manager.currentReleaseNotes() } }, shape = RoundedCornerShape(14.dp)) { Text("Quoi de neuf") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Vérifier automatiquement au lancement", style = MaterialTheme.typography.bodyMedium)
                    Text("Lecture de latest.json sur github.com/${UpdateManager.REPO}, au plus toutes les 6 h. Aucune donnée envoyée.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = prefs?.autoCheck ?: true, onCheckedChange = { on -> scope.launch { manager.setAutoCheck(on) } })
            }
        }
    }
    if (showSheet) UpdateSheet(manager = manager, onDismiss = { showSheet = false })
    showNotes?.let { notes -> WhatsNewDialog(version = manager.currentVersion, notes = notes, onDismiss = { showNotes = null }) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
}

private fun sourceHint(source: KeySource, provider: AiProvider): String = when (source) {
    KeySource.APP_SETTINGS -> "Clé saisie dans l'app"
    KeySource.DOT_ENV -> "Clé ${provider.label} présente dans le .env (laissez vide pour la conserver)"
    KeySource.NONE -> "Aucune clé"
}

@Composable
private fun StatusCard(config: AiConfig) {
    val active = config.provider != null
    Surface(
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(if (active) Icons.Rounded.AutoAwesome else Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(28.dp))
            Column {
                Text(
                    if (active) "Lecture automatique des tickets" else "Mode saisie manuelle",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (config.provider) {
                        AiProvider.RELAY -> "Via le relais NAS ${config.relayUrl} (aucune clé sur le téléphone)"
                        AiProvider.ANTHROPIC -> "Fournisseur : Anthropic (Claude) — " + sourceShort(config.anthropicSource)
                        AiProvider.OPENAI -> "Fournisseur : OpenAI — " + sourceShort(config.openAiSource)
                        null -> "Aucune clé API ni relais configuré. Les dépenses se saisissent à la main."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun sourceShort(source: KeySource): String = when (source) {
    KeySource.APP_SETTINGS -> "clé saisie dans l'app"
    KeySource.DOT_ENV -> "clé issue du .env"
    KeySource.NONE -> "aucune clé"
}

@Composable
private fun KeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    hint: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(hint) },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggleReveal) {
                Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (reveal) "Masquer" else "Afficher")
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}
