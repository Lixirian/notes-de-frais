package com.lixirian.notesdefrais.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Aide autour de BiometricPrompt. Uniquement la biométrie **forte** (classe 3 : empreinte,
 * visage sécurisé), la seule autorisée à débloquer une clé du Keystore.
 */
object Biometric {

    private const val AUTHENTICATORS = BIOMETRIC_STRONG

    /** Vrai si l'appareil a un capteur fort ET au moins une empreinte/un visage enregistré. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Message explicatif quand la biométrie n'est pas utilisable, null sinon. */
    fun unavailableReason(context: Context): String? = when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Aucune empreinte enregistrée dans les réglages Android."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Cet appareil n'a pas de capteur biométrique sécurisé."
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Capteur biométrique indisponible pour le moment."
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Mise à jour de sécurité Android requise."
        else -> "Biométrie forte indisponible sur cet appareil."
    }

    fun findActivity(context: Context): FragmentActivity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is FragmentActivity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * Affiche la fenêtre biométrique système, liée au [cipher] du Keystore : le succès n'est
     * reconnu que si le système a réellement déverrouillé la clé. [onError] reçoit null si
     * l'utilisateur a simplement annulé, un message sinon.
     */
    fun prompt(
        context: Context,
        cipher: Cipher,
        title: String,
        subtitle: String? = null,
        negativeText: String = "Utiliser le code",
        onSuccess: () -> Unit,
        onError: (String?) -> Unit,
    ) {
        val activity = findActivity(context) ?: run { onError("Écran incompatible avec la biométrie."); return }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (result.cryptoObject?.cipher != null) onSuccess() else onError("Authentification biométrique non liée à la clé.")
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onError(if (cancelled) null else errString.toString())
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
