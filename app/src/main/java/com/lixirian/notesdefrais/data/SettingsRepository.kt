package com.lixirian.notesdefrais.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lixirian.notesdefrais.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class AiProvider(val label: String) {
    ANTHROPIC("Anthropic (Claude)"),
    OPENAI("OpenAI"),
    RELAY("Relais NAS"),
}

/** D'où provient la clé effectivement utilisée. */
enum class KeySource { NONE, DOT_ENV, APP_SETTINGS }

data class AiConfig(
    val anthropicKey: String,
    val openAiKey: String,
    val anthropicSource: KeySource,
    val openAiSource: KeySource,
    /** URL de base du relais NAS (ex. https://nas.example.com:8787), vide si non utilisé. */
    val relayUrl: String = "",
    val relayToken: String = "",
) {
    /** Priorité : relais NAS, puis Anthropic, puis OpenAI. */
    val provider: AiProvider? = when {
        relayUrl.isNotBlank() && relayToken.isNotBlank() -> AiProvider.RELAY
        anthropicKey.isNotBlank() -> AiProvider.ANTHROPIC
        openAiKey.isNotBlank() -> AiProvider.OPENAI
        else -> null
    }
    val isAiAvailable: Boolean get() = provider != null
}

/**
 * Résout la configuration IA : une clé saisie dans l'app (DataStore, **chiffrée via le Keystore**)
 * l'emporte sur la clé embarquée depuis `.env` à la compilation (BuildConfig). Un relais NAS
 * configuré l'emporte sur les deux (le téléphone ne porte alors aucune clé API).
 */
class SettingsRepository(private val context: Context) {

    private val anthropicKeyPref = stringPreferencesKey("anthropic_api_key")
    private val openAiKeyPref = stringPreferencesKey("openai_api_key")
    private val relayUrlPref = stringPreferencesKey("relay_url")
    private val relayTokenPref = stringPreferencesKey("relay_token")
    private val pairReqIdPref = stringPreferencesKey("pair_request_id")
    private val pairReqCodePref = stringPreferencesKey("pair_request_code")
    private val pairReqUrlPref = stringPreferencesKey("pair_request_url")
    private val pairReqMailPref = stringPreferencesKey("pair_request_mail")

    /** Demande d'accès émise par cet appareil et pas encore autorisée (persistée : survit à une fermeture). */
    val ownAccessRequest: Flow<RelayPairing.OwnRequest?> = context.settingsStore.data.map { p ->
        val id = p[pairReqIdPref]; val code = p[pairReqCodePref]
        if (id.isNullOrBlank() || code.isNullOrBlank()) null else RelayPairing.OwnRequest(id, code, p[pairReqMailPref] == "1")
    }

    val config: Flow<AiConfig> = context.settingsStore.data.map { prefs ->
        resolve(
            anthropicOverride = SecretStore.decrypt(prefs[anthropicKeyPref]),
            openAiOverride = SecretStore.decrypt(prefs[openAiKeyPref]),
            relayUrl = prefs[relayUrlPref].orEmpty(),
            relayToken = SecretStore.decrypt(prefs[relayTokenPref]),
        )
    }

    suspend fun current(): AiConfig = config.first()

    suspend fun setAnthropicKey(key: String) = storeSecret(anthropicKeyPref, key)

    suspend fun setOpenAiKey(key: String) = storeSecret(openAiKeyPref, key)

    /** Demande d'accès (appairage sans commande) : envoie la demande et la mémorise pour l'interroger ensuite. */
    suspend fun startAccessRequest(url: String, deviceName: String): RelayPairing.OwnRequest {
        val cleanUrl = url.trim().trimEnd('/')
        validateRelayUrl(cleanUrl)?.let { throw RelayPairing.PairingException(it) }
        val req = RelayPairing.requestAccess(cleanUrl, deviceName)
        context.settingsStore.edit {
            it[pairReqIdPref] = req.requestId; it[pairReqCodePref] = req.code; it[pairReqUrlPref] = cleanUrl; it[pairReqMailPref] = if (req.mailSent) "1" else "0"
        }
        return req
    }

    /**
     * Interroge le relais sur la demande en cours. Si elle est autorisée, le jeton est enregistré et
     * la demande effacée ; si elle a disparu (expirée, refusée), elle est effacée. null = aucune demande.
     */
    suspend fun pollOwnAccessRequest(): RelayPairing.Status? {
        val p = context.settingsStore.data.first()
        val id = p[pairReqIdPref]; val url = p[pairReqUrlPref]
        if (id.isNullOrBlank() || url.isNullOrBlank()) return null
        val status = RelayPairing.status(url, id)
        when (status) {
            is RelayPairing.Status.Approved -> {
                try {
                    setRelay(url, status.token)
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) android.util.Log.e("Pairing", "enregistrement du jeton impossible", e)
                    throw e
                }
                cancelOwnAccessRequest()
            }
            RelayPairing.Status.Gone -> cancelOwnAccessRequest()
            RelayPairing.Status.Pending -> Unit
        }
        return status
    }

    suspend fun cancelOwnAccessRequest() {
        context.settingsStore.edit { it.remove(pairReqIdPref); it.remove(pairReqCodePref); it.remove(pairReqUrlPref); it.remove(pairReqMailPref) }
    }

    /**
     * Appairage par code court : obtient le jeton auprès du relais puis l'enregistre chiffré.
     * Renvoie le jeton obtenu. Lève [RelayPairing.PairingException] avec un message lisible.
     */
    suspend fun pairRelay(url: String, code: String): String {
        val cleanUrl = url.trim().trimEnd('/')
        val token = RelayPairing.pair(cleanUrl, code)
        setRelay(cleanUrl, token)
        return token
    }

    /** Refuse une URL de relais non sûre ; voir [validateRelayUrl]. */
    suspend fun setRelay(url: String, token: String) {
        val cleanUrl = url.trim().trimEnd('/')
        validateRelayUrl(cleanUrl)?.let { throw IllegalArgumentException(it) }
        context.settingsStore.edit { it[relayUrlPref] = cleanUrl }
        storeSecret(relayTokenPref, token)
    }

    /** Re-chiffre les secrets stockés en clair par une version antérieure de l'app. */
    suspend fun migrateLegacySecrets() {
        val prefs = context.settingsStore.data.first()
        val toMigrate = listOf(anthropicKeyPref, openAiKeyPref, relayTokenPref).filter { !SecretStore.isEncrypted(prefs[it]) }
        if (toMigrate.isEmpty()) return
        val encrypted = withContext(Dispatchers.Default) { toMigrate.associateWith { SecretStore.encrypt(prefs[it].orEmpty()) } }
        context.settingsStore.edit { store -> encrypted.forEach { (k, v) -> store[k] = v } }
    }

    private suspend fun storeSecret(key: Preferences.Key<String>, value: String) {
        val encrypted = withContext(Dispatchers.Default) { SecretStore.encrypt(value.trim()) }
        context.settingsStore.edit { it[key] = encrypted }
    }

    private fun resolve(anthropicOverride: String, openAiOverride: String, relayUrl: String, relayToken: String): AiConfig {
        val anthropicEnv = BuildConfig.ANTHROPIC_API_KEY
        val openAiEnv = BuildConfig.OPENAI_API_KEY
        return AiConfig(
            anthropicKey = anthropicOverride.ifBlank { anthropicEnv },
            openAiKey = openAiOverride.ifBlank { openAiEnv },
            anthropicSource = when {
                anthropicOverride.isNotBlank() -> KeySource.APP_SETTINGS
                anthropicEnv.isNotBlank() -> KeySource.DOT_ENV
                else -> KeySource.NONE
            },
            openAiSource = when {
                openAiOverride.isNotBlank() -> KeySource.APP_SETTINGS
                openAiEnv.isNotBlank() -> KeySource.DOT_ENV
                else -> KeySource.NONE
            },
            relayUrl = relayUrl,
            relayToken = relayToken,
        )
    }

    companion object {
        /**
         * Le HTTP en clair n'est toléré que vers le réseau local (adresses privées, localhost) :
         * ailleurs, le jeton et les tickets circuleraient en clair sur Internet. Renvoie un
         * message d'erreur, ou null si l'URL est acceptable. Une URL vide désactive le relais.
         */
        fun validateRelayUrl(url: String): String? {
            if (url.isBlank()) return null
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return "URL invalide"
            val host = uri.host?.lowercase() ?: return "URL invalide (hôte manquant)"
            return when (uri.scheme?.lowercase()) {
                "https" -> null
                "http" -> if (isPrivateHost(host)) null else "En dehors du réseau local, l'URL doit commencer par https://"
                else -> "L'URL doit commencer par https:// (ou http:// sur le réseau local)"
            }
        }

        private fun isPrivateHost(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home")) return true
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            return parts[0] == 10 ||
                (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 192 && parts[1] == 168) ||
                parts[0] == 127
        }
    }
}
