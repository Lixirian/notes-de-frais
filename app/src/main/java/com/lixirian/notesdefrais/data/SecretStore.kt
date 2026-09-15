package com.lixirian.notesdefrais.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement des secrets (jeton du relais, clés API) avec une clé AES-256 générée dans le
 * **Android Keystore** : la clé ne quitte jamais le matériel sécurisé, un fichier DataStore
 * copié hors du téléphone (sauvegarde, extraction) ne contient que du chiffré inutilisable.
 */
object SecretStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "ndf_secrets_v1"
    private const val PREFIX = "enc1:"
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    /** Renvoie "" si la valeur est vide ou illisible (clé Keystore perdue après restauration, etc.). */
    fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored // valeur héritée non chiffrée (migrée au prochain enregistrement)
        return runCatching {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val data = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun isEncrypted(stored: String?): Boolean = stored.isNullOrEmpty() || stored.startsWith(PREFIX)
}
