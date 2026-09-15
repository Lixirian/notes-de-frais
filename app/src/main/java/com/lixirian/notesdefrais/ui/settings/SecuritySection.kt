package com.lixirian.notesdefrais.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lixirian.notesdefrais.data.SecurityConfig
import com.lixirian.notesdefrais.data.SecurityRepository
import com.lixirian.notesdefrais.ui.lock.Biometric
import com.lixirian.notesdefrais.ui.lock.BiometricKey
import androidx.compose.material.icons.rounded.Screenshot
import kotlinx.coroutines.launch

/** Section Sécurité des réglages : définir / changer / désactiver le code, activer l'empreinte. */
@Composable
fun SecuritySection(security: SecurityRepository, config: SecurityConfig, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<PinDialogMode?>(null) }
    val biometricReason = remember(config) { Biometric.unavailableReason(context) }

    Surface(
        color = if (config.hasPin) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (config.hasPin) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(if (config.hasPin) Icons.Rounded.Lock else Icons.Rounded.LockOpen, contentDescription = null)
                Column {
                    Text(if (config.hasPin) "App verrouillée par code" else "App non verrouillée", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (config.hasPin) "Code à ${config.pinLength} chiffres demandé à l'ouverture et après 30 s en arrière-plan."
                        else "Définissez un code à 4-8 chiffres pour protéger vos dépenses et vos justificatifs.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Screenshot, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Masquer dans les captures et les applications récentes", style = MaterialTheme.typography.titleSmall)
                    Text("Bloque les captures d'écran et l'aperçu de l'app dans le sélecteur d'applications.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = config.hideFromScreenshots,
                    onCheckedChange = { wanted -> scope.launch { security.setHideFromScreenshots(wanted) } },
                )
            }

            if (!config.hasPin) {
                OutlinedButton(onClick = { dialog = PinDialogMode.Define }, modifier = Modifier.fillMaxWidth()) { Text("Définir un code") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { dialog = PinDialogMode.Change }, modifier = Modifier.weight(1f)) { Text("Changer le code") }
                    OutlinedButton(onClick = { dialog = PinDialogMode.Disable }, modifier = Modifier.weight(1f)) { Text("Désactiver") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Déverrouillage par empreinte", style = MaterialTheme.typography.titleSmall)
                        Text(
                            biometricReason ?: "Empreinte ou visage enregistrés sur ce téléphone ; le code reste disponible.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = config.biometricEnabled,
                        enabled = biometricReason == null,
                        onCheckedChange = { wanted ->
                            if (!wanted) {
                                BiometricKey.delete()
                                scope.launch { security.setBiometricEnabled(false); onMessage("Empreinte désactivée") }
                            } else {
                                // Nouvelle clé Keystore liée aux empreintes actuelles : toute empreinte ajoutée plus tard l'invalide.
                                when (val prepared = BiometricKey.recreate()) {
                                    is BiometricKey.Prepared.Ready -> Biometric.prompt(
                                        context = context,
                                        cipher = prepared.cipher,
                                        title = "Activer l'empreinte",
                                        subtitle = "Confirmez avec votre empreinte",
                                        negativeText = "Annuler",
                                        onSuccess = { scope.launch { security.setBiometricEnabled(true); onMessage("Empreinte activée") } },
                                        onError = { msg -> if (msg != null) onMessage(msg) },
                                    )
                                    BiometricKey.Prepared.Invalidated -> onMessage("Impossible de créer la clé biométrique sur cet appareil.")
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    dialog?.let { mode ->
        PinDialog(
            mode = mode,
            security = security,
            onDismiss = { dialog = null },
            onDone = { message -> dialog = null; onMessage(message) },
        )
    }
}

enum class PinDialogMode { Define, Change, Disable }

/**
 * Dialogue en étapes : code actuel (si un code existe), nouveau code, confirmation.
 * Saisie via un champ numérique masqué (la vérification en temps constant est dans le dépôt).
 */
@Composable
private fun PinDialog(mode: PinDialogMode, security: SecurityRepository, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (mode == PinDialogMode.Define) Step.NEW else Step.CURRENT) }
    var input by remember { mutableStateOf("") }
    var firstNew by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val title = when (mode) {
        PinDialogMode.Define -> "Définir un code"
        PinDialogMode.Change -> "Changer le code"
        PinDialogMode.Disable -> "Désactiver le code"
    }
    val prompt = when (step) {
        Step.CURRENT -> "Code actuel"
        Step.NEW -> "Nouveau code (4 à 8 chiffres)"
        Step.CONFIRM -> "Confirmez le nouveau code"
    }

    fun validate() {
        if (busy) return
        val value = input
        when (step) {
            Step.CURRENT -> {
                busy = true
                scope.launch {
                    // Même blocage progressif que l'écran de verrouillage : pas d'oracle de code sans limite.
                    val waitMs = security.lockoutRemainingMs()
                    if (waitMs > 0) { busy = false; error = "Trop d'essais : attendez ${(waitMs + 999) / 1000} s"; input = ""; return@launch }
                    val ok = security.verifyPin(value)
                    busy = false
                    if (!ok) { security.registerFailedAttempt(); error = "Code incorrect"; input = ""; return@launch }
                    security.clearFailures()
                    if (mode == PinDialogMode.Disable) {
                        security.clearPin(); onDone("Code désactivé")
                    } else {
                        step = Step.NEW; input = ""; error = null
                    }
                }
            }
            Step.NEW -> {
                if (value.length !in SecurityRepository.MIN_LENGTH..SecurityRepository.MAX_LENGTH) { error = "Entre 4 et 8 chiffres"; return }
                firstNew = value; input = ""; error = null; step = Step.CONFIRM
            }
            Step.CONFIRM -> {
                if (value != firstNew) { error = "Les deux codes ne correspondent pas"; input = ""; step = Step.NEW; return }
                busy = true
                scope.launch {
                    security.setPin(value)
                    busy = false
                    onDone(if (mode == PinDialogMode.Define) "Code défini : l'app sera verrouillée à la prochaine ouverture" else "Code modifié")
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = input,
                    onValueChange = { v -> if (v.length <= SecurityRepository.MAX_LENGTH && v.all { it.isDigit() }) { input = v; error = null } },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::validate, enabled = input.isNotEmpty() && !busy) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

private enum class Step { CURRENT, NEW, CONFIRM }
