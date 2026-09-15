package com.lixirian.notesdefrais.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lixirian.notesdefrais.update.UpdateManager
import com.lixirian.notesdefrais.update.UpdateState
import kotlinx.coroutines.launch

/** Bandeau « Mise à jour disponible » (liste principale) ; masqué quand rien n'est à faire. */
@Composable
fun UpdateBanner(manager: UpdateManager, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsStateWithLifecycle()
    val (label, action) = when (val s = state) {
        is UpdateState.Available -> "Version ${s.manifest.version} disponible" to "Installer"
        is UpdateState.Downloading -> "Téléchargement de la version ${s.manifest.version}…" to "${(s.progress * 100).toInt()} %"
        is UpdateState.ReadyToInstall -> "Version ${s.manifest.version} prête à installer" to "Installer"
        is UpdateState.Error -> if (s.manifest != null) "Mise à jour ${s.manifest.version} : échec" to "Réessayer" else return
        else -> return
    }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(action, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Feuille de mise à jour : notes de la nouvelle version, téléchargement avec progression,
 * puis installation par l'installateur Android (autorisation « applications inconnues » demandée
 * une seule fois).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(manager: UpdateManager, onDismiss: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Ré-évalué à chaque retour au premier plan : l'utilisateur revient du réglage
    // « applications inconnues » sans que l'état de la feuille ait changé.
    var canInstall by remember { mutableStateOf(manager.canInstall()) }
    LifecycleResumeEffect(Unit) {
        canInstall = manager.canInstall()
        manager.ensureReadyFileExists()
        onPauseOrDispose { }
    }
    val manifest = when (val s = state) {
        is UpdateState.Available -> s.manifest
        is UpdateState.Downloading -> s.manifest
        is UpdateState.ReadyToInstall -> s.manifest
        is UpdateState.Error -> s.manifest
        else -> null
    }
    if (manifest == null) { onDismiss(); return }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Column {
                    Text("Version ${manifest.version}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Installée : ${manager.currentVersion}" + (manifest.size?.let { " · ${Formatters.fileSize(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (manifest.notes.isNotEmpty()) {
                Text("Quoi de neuf", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ReleaseNotes(manifest.notes)
            }
            Spacer(Modifier.height(4.dp))
            when (val s = state) {
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Téléchargement… ${(s.progress * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
                }
                is UpdateState.ReadyToInstall -> {
                    Text(
                        "APK vérifié (signature GitHub et empreinte SHA-256). Android va demander confirmation ; l'app redémarre ensuite dans la nouvelle version.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!canInstall) {
                        Text(
                            "Android demande une autorisation unique pour installer une application depuis cette app : activez « Autoriser depuis cette source », puis revenez ici.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            when {
                                !manager.canInstall() -> context.startActivity(manager.unknownSourcesIntent())
                                manager.verifyReadyFile() -> context.startActivity(manager.installIntent(s.file))
                                // sinon : l'état est passé en erreur (fichier altéré), la feuille propose de retélécharger
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(if (canInstall) "Installer maintenant" else "Autoriser l'installation puis revenir") }
                }
                is UpdateState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { manager.download(manifest) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Réessayer") }
                }
                else -> {
                    Button(onClick = { manager.download(manifest) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Télécharger et installer")
                    }
                }
            }
            if (state is UpdateState.Available) {
                TextButton(onClick = { scope.launch { manager.dismiss(manifest); onDismiss() } }, modifier = Modifier.fillMaxWidth()) { Text("Plus tard") }
            }
        }
    }
}

@Composable
fun ReleaseNotes(notes: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.forEach { note ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(note, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Dialogue « Quoi de neuf » affiché une fois après une mise à jour. */
@Composable
fun WhatsNewDialog(version: String, notes: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.NewReleases, contentDescription = null) },
        title = { Text("Nouveautés de la version $version") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (notes.isEmpty()) Text("Mise à jour installée.") else ReleaseNotes(notes)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Compris") } },
    )
}

/** Petit indicateur d'attente réutilisable (bouton « Vérifier maintenant »). */
@Composable
fun SmallSpinner() {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
}
