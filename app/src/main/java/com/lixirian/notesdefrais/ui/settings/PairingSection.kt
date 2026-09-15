package com.lixirian.notesdefrais.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lixirian.notesdefrais.data.RelayPairing
import com.lixirian.notesdefrais.ui.appContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Appairage sans commande, côté appareil DEMANDEUR : bouton « Demander l'accès », puis carte
 * d'attente avec le code à comparer, interrogation du relais toutes les 3 s jusqu'à
 * l'autorisation (par un appareil associé, le lien de l'e-mail ou relayctl).
 */
@Composable
fun AccessRequestCard(relayUrl: String, urlValid: Boolean, onPaired: (String) -> Unit, onMessage: (String) -> Unit) {
    val app = appContext()
    val settings = app.settingsRepository
    val scope = rememberCoroutineScope()
    val own by settings.ownAccessRequest.collectAsStateWithLifecycle(initialValue = null)
    var askName by rememberSaveable { mutableStateOf(false) }
    var deviceName by rememberSaveable { mutableStateOf(defaultDeviceName()) }
    var busy by remember { mutableStateOf(false) }

    // Interrogation du relais tant qu'une demande est en attente (écran ouvert).
    LaunchedEffect(own?.requestId) {
        val req = own ?: return@LaunchedEffect
        while (true) {
            delay(3_000)
            when (val s = runCatching { settings.pollOwnAccessRequest() }.getOrNull()) {
                is RelayPairing.Status.Approved -> { onPaired(s.token); onMessage("Accès autorisé : relais associé"); return@LaunchedEffect }
                RelayPairing.Status.Gone -> {
                    // La synchro a pu consommer l'autorisation avant cet écran : le jeton est alors déjà en place.
                    val token = settings.current().relayToken
                    if (token.isNotBlank()) { onPaired(token); onMessage("Accès autorisé : relais associé") }
                    else onMessage("Demande expirée ou refusée (code ${req.codeLabel})")
                    return@LaunchedEffect
                }
                else -> Unit
            }
        }
    }

    val pending = own
    if (pending != null) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.HourglassTop, contentDescription = null, modifier = Modifier.size(26.dp))
                    Text("En attente d'autorisation", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(pending.codeLabel, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 4.sp))
                Text(
                    "Faites autoriser cet appareil par le propriétaire : sur son téléphone déjà associé (Réglages > Relais NAS > Demandes d'accès), " +
                        (if (pending.mailSent) "par le lien reçu par e-mail, " else "") +
                        "ou sur le NAS (relayctl.sh approve). Il doit voir ce même code. Valable 15 minutes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { scope.launch { settings.cancelOwnAccessRequest(); onMessage("Demande annulée") } }) { Text("Annuler la demande") }
            }
        }
        return
    }

    Button(
        enabled = !busy && urlValid,
        onClick = { askName = true },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Icon(Icons.Rounded.PersonAdd, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text("Demander l'accès à ce relais")
    }
    Text(
        "Aucune commande à taper : le propriétaire autorise l'appareil depuis son téléphone (ou par l'e-mail reçu), et le jeton arrive tout seul.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (askName) {
        AlertDialog(
            onDismissRequest = { askName = false },
            icon = { Icon(Icons.Rounded.Devices, contentDescription = null) },
            title = { Text("Nom de cet appareil") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Il sera affiché au propriétaire pour qu'il reconnaisse la demande.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = deviceName, onValueChange = { deviceName = it.take(60) }, singleLine = true, label = { Text("Appareil") }, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = deviceName.isNotBlank(),
                    onClick = {
                        askName = false; busy = true
                        scope.launch {
                            runCatching { settings.startAccessRequest(relayUrl, deviceName) }
                                .onSuccess { onMessage("Demande envoyée : code ${it.codeLabel}") }
                                .onFailure { onMessage(it.message ?: "Demande impossible") }
                            busy = false
                        }
                    },
                ) { Text("Envoyer la demande") }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Annuler") } },
        )
    }
}

/**
 * Côté appareil ASSOCIÉ : liste des demandes d'accès en attente sur le relais, avec Autoriser /
 * Refuser. Rafraîchie toutes les 5 s tant que l'écran est ouvert.
 */
@Composable
fun PendingRequestsCard(onMessage: (String) -> Unit) {
    val app = appContext()
    val sync = app.syncEngine
    val pending by sync.pendingPairings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) { runCatching { sync.refreshPendingPairings() }; delay(5_000) }
    }
    if (pending.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(26.dp))
                Text(if (pending.size > 1) "Demandes d'accès (${pending.size})" else "Demande d'accès", style = MaterialTheme.typography.titleMedium)
            }
            Text("N'autorisez que si le code affiché sur l'appareil demandeur est identique.", style = MaterialTheme.typography.bodySmall)
            pending.forEach { req ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(req.device, style = MaterialTheme.typography.titleSmall)
                    Text(req.codeLabel, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 3.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { scope.launch { onMessage(if (sync.decidePairing(req.id, true)) "« ${req.device} » autorisé" else "Demande introuvable (expirée ?)") } }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("Autoriser") }
                        OutlinedButton(onClick = { scope.launch { sync.decidePairing(req.id, false); onMessage("Demande refusée") } }, shape = RoundedCornerShape(14.dp)) { Text("Refuser") }
                    }
                }
            }
        }
    }
}

private fun defaultDeviceName(): String {
    val model = Build.MODEL.orEmpty()
    val brand = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    return if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model".trim()
}
