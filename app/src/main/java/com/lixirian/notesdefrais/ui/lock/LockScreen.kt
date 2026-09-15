package com.lixirian.notesdefrais.ui.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lixirian.notesdefrais.data.SecurityConfig
import com.lixirian.notesdefrais.data.SecurityRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Écran de verrouillage plein écran : pavé numérique, indicateurs de saisie, empreinte.
 * Le code est vérifié dès que le nombre de chiffres attendu est atteint.
 */
@Composable
fun LockScreen(appLock: AppLock, security: SecurityRepository, config: SecurityConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var lockoutLeft by remember { mutableIntStateOf(0) }
    var biometricUsable by remember { mutableStateOf(config.biometricEnabled && Biometric.isAvailable(context)) }
    val shake = remember { Animatable(0f) }

    fun promptBiometric() {
        when (val prepared = BiometricKey.prepare()) {
            is BiometricKey.Prepared.Ready -> Biometric.prompt(
                context = context,
                cipher = prepared.cipher,
                title = "Déverrouiller Mes notes de frais",
                subtitle = "Empreinte ou code",
                onSuccess = { scope.launch { appLock.unlockWithBiometric() } },
                onError = { message -> if (message != null) error = message },
            )
            BiometricKey.Prepared.Invalidated -> {
                // Empreintes modifiées sur le téléphone : on retombe sur le code et on désactive l'empreinte.
                biometricUsable = false
                error = "Empreintes modifiées : entrez votre code, puis réactivez l'empreinte dans les réglages."
                scope.launch { security.setBiometricEnabled(false) }
            }
        }
    }

    LaunchedEffect(Unit) {
        lockoutLeft = appLock.lockoutSecondsLeft()
        if (biometricUsable && lockoutLeft == 0) promptBiometric()
    }

    // Compte à rebours après trop d'échecs.
    LaunchedEffect(lockoutLeft) {
        if (lockoutLeft > 0) { delay(1000); lockoutLeft = appLock.lockoutSecondsLeft() }
    }

    fun submit(pin: String) {
        checking = true
        scope.launch {
            val ok = appLock.tryUnlockWithPin(pin)
            checking = false
            if (!ok) {
                entered = ""
                lockoutLeft = appLock.lockoutSecondsLeft()
                error = if (lockoutLeft > 0) "Trop d'essais. Patientez $lockoutLeft s." else "Code incorrect"
                shake.snapTo(0f)
                repeat(3) { shake.animateTo(12f, tween(40)); shake.animateTo(-12f, tween(40)) }
                shake.animateTo(0f, tween(40))
            }
        }
    }

    fun onDigit(d: Char) {
        if (checking || lockoutLeft > 0) return
        error = null
        if (entered.length < config.pinLength) entered += d
        if (entered.length == config.pinLength) submit(entered)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF5B4CF5), Color(0xFF3B2FB8), Color(0xFF1B1640))))
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp).padding(24.dp),
        ) {
            Box(
                modifier = Modifier.size(72.dp).background(Color.White.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.height(18.dp))
            Text("Mes notes de frais", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    lockoutLeft > 0 -> "Trop d'essais. Patientez $lockoutLeft s."
                    error != null -> error!!
                    else -> "Entrez votre code"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (error != null || lockoutLeft > 0) Color(0xFFFFB4AB) else Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
            ) {
                repeat(config.pinLength) { i ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(if (i < entered.length) Color.White else Color.White.copy(alpha = 0.25f), CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(36.dp))

            val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { row.forEach { d -> KeyButton(d.toString()) { onDigit(d) } } }
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (biometricUsable) {
                    KeyButton(icon = { Icon(Icons.Rounded.Fingerprint, contentDescription = "Empreinte", tint = Color.White, modifier = Modifier.size(30.dp)) }) { promptBiometric() }
                } else {
                    Spacer(Modifier.size(76.dp))
                }
                KeyButton("0") { onDigit('0') }
                KeyButton(icon = { Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Effacer", tint = Color.White, modifier = Modifier.size(26.dp)) }) {
                    if (entered.isNotEmpty()) entered = entered.dropLast(1)
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String? = null, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = if (label != null) 0.16f else 0.08f),
        modifier = Modifier.size(76.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (label != null) Text(label, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium) else icon?.invoke()
        }
    }
}
