package com.lixirian.notesdefrais.ui.lock

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Clé AES du Keystore liée aux empreintes : utilisable uniquement après une authentification
 * biométrique **forte**, et **invalidée définitivement** si une nouvelle empreinte est enrôlée
 * sur le téléphone. Ainsi, quelqu'un qui ajouterait son empreinte (il lui faudrait déjà le code
 * de l'appareil) ne pourrait pas ouvrir l'app : elle retomberait sur le code PIN.
 */
object BiometricKey {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "ndf_biometric_v1"

    /** Résultat de la préparation du chiffre pour BiometricPrompt. */
    sealed interface Prepared {
        data class Ready(val cipher: Cipher) : Prepared
        /** Clé invalidée (empreintes modifiées) ou absente : l'empreinte doit être réactivée. */
        data object Invalidated : Prepared
    }

    /** (Re)crée la clé : à appeler quand l'utilisateur active l'empreinte dans les réglages. */
    fun recreate(): Prepared {
        delete()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        generator.init(spec)
        generator.generateKey()
        return prepare()
    }

    /** Prépare un chiffre à faire authentifier par BiometricPrompt (CryptoObject). */
    fun prepare(): Prepared {
        val key = existingKey() ?: return Prepared.Invalidated
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            Prepared.Ready(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            delete()
            Prepared.Invalidated
        } catch (e: Exception) {
            Prepared.Invalidated
        }
    }

    fun delete() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun existingKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }.getOrNull()
}
