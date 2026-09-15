package com.lixirian.notesdefrais.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.securityStore: DataStore<Preferences> by preferencesDataStore(name = "security")

data class SecurityConfig(
    val hasPin: Boolean = false,
    val pinLength: Int = 0,
    val biometricEnabled: Boolean = false,
    /** FLAG_SECURE : pas de capture d'écran, app masquée dans les applications récentes. */
    val hideFromScreenshots: Boolean = false,
)

/**
 * Code PIN de l'app : jamais stocké en clair. On conserve un sel aléatoire et un hachage
 * PBKDF2-HMAC-SHA256 (100 000 itérations) ; la vérification se fait en temps constant.
 * Les échecs et le délai d'attente sont persistés : tuer l'app ne remet pas le compteur à zéro.
 * L'empreinte est un simple indicateur : c'est Android (BiometricPrompt + clé Keystore) qui authentifie.
 */
class SecurityRepository(private val context: Context) {

    private val saltKey = stringPreferencesKey("pin_salt")
    private val hashKey = stringPreferencesKey("pin_hash")
    private val lengthKey = intPreferencesKey("pin_length")
    private val biometricKey = booleanPreferencesKey("biometric_enabled")
    private val hideKey = booleanPreferencesKey("hide_from_screenshots")
    private val failedKey = intPreferencesKey("failed_attempts")
    private val lockoutUntilKey = longPreferencesKey("lockout_until")

    val config: Flow<SecurityConfig> = context.securityStore.data.map { prefs ->
        val hash = prefs[hashKey]
        SecurityConfig(
            hasPin = !hash.isNullOrEmpty(),
            pinLength = prefs[lengthKey] ?: 0,
            biometricEnabled = !hash.isNullOrEmpty() && (prefs[biometricKey] ?: false),
            hideFromScreenshots = prefs[hideKey] ?: false,
        )
    }

    suspend fun current(): SecurityConfig = config.first()

    suspend fun setPin(pin: String) {
        require(pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }) { "Code invalide" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = withContext(Dispatchers.Default) { derive(pin, salt) }
        context.securityStore.edit {
            it[saltKey] = salt.toHex()
            it[hashKey] = hash.toHex()
            it[lengthKey] = pin.length
            it[failedKey] = 0
            it[lockoutUntilKey] = 0L
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val prefs = context.securityStore.data.first()
        val salt = prefs[saltKey]?.fromHex() ?: return false
        val expected = prefs[hashKey]?.fromHex() ?: return false
        val actual = withContext(Dispatchers.Default) { derive(pin, salt) }
        return MessageDigest.isEqual(expected, actual)
    }

    suspend fun clearPin() {
        context.securityStore.edit {
            it.remove(saltKey); it.remove(hashKey); it.remove(lengthKey)
            it[biometricKey] = false; it[failedKey] = 0; it[lockoutUntilKey] = 0L
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.securityStore.edit { it[biometricKey] = enabled }
    }

    suspend fun setHideFromScreenshots(enabled: Boolean) {
        context.securityStore.edit { it[hideKey] = enabled }
    }

    /** Millisecondes restantes de blocage après trop d'échecs (0 si aucun). */
    suspend fun lockoutRemainingMs(): Long {
        val until = context.securityStore.data.first()[lockoutUntilKey] ?: 0L
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Enregistre un échec ; déclenche un blocage progressif à partir de [MAX_ATTEMPTS]. */
    suspend fun registerFailedAttempt() {
        context.securityStore.edit { prefs ->
            val failed = (prefs[failedKey] ?: 0) + 1
            prefs[failedKey] = failed
            if (failed >= MAX_ATTEMPTS) {
                // 30 s, puis 60 s, 120 s… (doublé à chaque nouvelle série d'échecs, plafonné à 10 min)
                val series = failed - MAX_ATTEMPTS + 1
                val delayMs = (BASE_LOCKOUT_MS shl (series - 1).coerceAtMost(4)).coerceAtMost(MAX_LOCKOUT_MS)
                prefs[lockoutUntilKey] = System.currentTimeMillis() + delayMs
            }
        }
    }

    suspend fun clearFailures() {
        context.securityStore.edit { it[failedKey] = 0; it[lockoutUntilKey] = 0L }
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 8
        const val MAX_ATTEMPTS = 5
        private const val ITERATIONS = 100_000
        private const val BASE_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 600_000L
    }
}
