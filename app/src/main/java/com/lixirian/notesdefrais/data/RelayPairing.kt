package com.lixirian.notesdefrais.data

import com.lixirian.notesdefrais.net.BoundedBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Appairage avec le relais NAS, sans jamais recopier le jeton :
 *
 * 1. **Demande d'accès** (recommandé, aucune commande) : l'app envoie `POST /pair/request` avec un
 *    nom d'appareil et affiche le code reçu ; elle interroge `GET /pair/status/<id>` jusqu'à ce
 *    qu'un appareil déjà associé (Réglages > Demandes d'accès), le lien de l'e-mail envoyé au
 *    propriétaire, ou `relayctl.sh approve <code>` autorise la demande ; elle reçoit alors le jeton.
 * 2. **Code d'appairage** (avancé) : `relayctl.sh pair` sur le NAS affiche un code à usage unique,
 *    échangé contre le jeton via `POST /pair`.
 */
object RelayPairing {

    @Serializable private data class PairRequest(val code: String)
    @Serializable private data class PairResponse(val token: String)
    @Serializable private data class AccessRequest(val device: String)
    @Serializable private data class AccessRequestResponse(val request_id: String, val code: String, val expires_in: Int = 900, val mail_sent: Boolean = false)
    @Serializable private data class StatusResponse(val status: String, val token: String? = null)
    @Serializable private data class PendingResponse(val requests: List<PendingRequest> = emptyList())
    @Serializable private data class DecideRequest(val id: String)
    @Serializable private data class ErrorBody(val error: ErrorDetail? = null)
    @Serializable private data class ErrorDetail(val message: String? = null)

    /** Demande d'accès en attente, vue par un appareil déjà associé. */
    @Serializable
    data class PendingRequest(val id: String, val device: String, val code: String, val age_s: Int = 0) {
        val codeLabel: String get() = if (code.length == 8) "${code.take(4)} ${code.drop(4)}" else code
    }

    /** Demande émise par CET appareil, en attente d'autorisation. */
    data class OwnRequest(val requestId: String, val code: String, val mailSent: Boolean) {
        val codeLabel: String get() = if (code.length == 8) "${code.take(4)} ${code.drop(4)}" else code
    }

    sealed interface Status {
        data object Pending : Status
        data class Approved(val token: String) : Status
        /** Expirée, refusée ou déjà consommée. */
        data object Gone : Status
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .build()
    }
    private val jsonType = "application/json".toMediaType()

    class PairingException(message: String) : Exception(message)

    // ---------------------------------------------------------------- demande d'accès

    suspend fun requestAccess(baseUrl: String, deviceName: String): OwnRequest = withContext(Dispatchers.IO) {
        val base = checkedBase(baseUrl)
        val body = json.encodeToString(AccessRequest.serializer(), AccessRequest(deviceName.trim().take(60)))
        call(Request.Builder().url("$base/pair/request").post(body.toRequestBody(jsonType)).build()) { r, text ->
            if (!r.isSuccessful) throw PairingException(errorMessage(r.code, text, "Demande impossible"))
            val parsed = runCatching { json.decodeFromString(AccessRequestResponse.serializer(), text) }.getOrNull()
                ?: throw PairingException("Réponse du relais invalide")
            OwnRequest(parsed.request_id, parsed.code, parsed.mail_sent)
        }
    }

    suspend fun status(baseUrl: String, requestId: String): Status = withContext(Dispatchers.IO) {
        val base = checkedBase(baseUrl)
        call(Request.Builder().url("$base/pair/status/$requestId").get().build()) { r, text ->
            if (r.code == 404) return@call Status.Gone
            if (!r.isSuccessful) throw PairingException(errorMessage(r.code, text, "Relais indisponible"))
            val parsed = runCatching { json.decodeFromString(StatusResponse.serializer(), text) }.getOrNull()
                ?: throw PairingException("Réponse du relais invalide")
            when {
                parsed.status == "approved" && !parsed.token.isNullOrBlank() && parsed.token.length >= 16 -> Status.Approved(parsed.token)
                parsed.status == "pending" -> Status.Pending
                else -> Status.Gone
            }
        }
    }

    // ---------------------------------------------------------------- côté appareil associé

    suspend fun pending(baseUrl: String, token: String): List<PendingRequest> = withContext(Dispatchers.IO) {
        val base = checkedBase(baseUrl)
        call(Request.Builder().url("$base/pair/pending").header("Authorization", "Bearer $token").get().build()) { r, text ->
            if (!r.isSuccessful) throw PairingException(errorMessage(r.code, text, "Relais indisponible"))
            runCatching { json.decodeFromString(PendingResponse.serializer(), text).requests }.getOrDefault(emptyList())
        }
    }

    suspend fun decide(baseUrl: String, token: String, requestId: String, approve: Boolean): Boolean = withContext(Dispatchers.IO) {
        val base = checkedBase(baseUrl)
        val body = json.encodeToString(DecideRequest.serializer(), DecideRequest(requestId))
        val path = if (approve) "/pair/approve" else "/pair/deny"
        call(Request.Builder().url("$base$path").header("Authorization", "Bearer $token").post(body.toRequestBody(jsonType)).build()) { r, _ ->
            r.isSuccessful
        }
    }

    // ---------------------------------------------------------------- code d'appairage (avancé)

    suspend fun pair(baseUrl: String, code: String): String = withContext(Dispatchers.IO) {
        val clean = code.filter { it.isDigit() }
        if (clean.length != CODE_LENGTH) throw PairingException("Le code d'appairage comporte $CODE_LENGTH chiffres")
        val base = checkedBase(baseUrl)
        val body = json.encodeToString(PairRequest.serializer(), PairRequest(clean))
        call(Request.Builder().url("$base/pair").post(body.toRequestBody(jsonType)).build()) { r, text ->
            if (!r.isSuccessful) {
                throw PairingException(
                    when (r.code) {
                        404 -> "Aucun code en cours sur le NAS : lancez « relayctl.sh pair » puis réessayez dans les 10 minutes"
                        401 -> "Code incorrect"
                        410 -> "Code expiré ou déjà utilisé : relancez « relayctl.sh pair »"
                        429 -> "Trop d'essais : relancez « relayctl.sh pair » pour un nouveau code"
                        else -> errorMessage(r.code, text, "Appairage impossible")
                    },
                )
            }
            val token = runCatching { json.decodeFromString(PairResponse.serializer(), text).token }.getOrNull()
            if (token.isNullOrBlank() || token.length < 16) throw PairingException("Réponse d'appairage invalide")
            token
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun checkedBase(url: String): String {
        val base = url.trim().trimEnd('/')
        SettingsRepository.validateRelayUrl(base)?.let { throw PairingException(it) }
        return base
    }

    private inline fun <T> call(request: Request, handle: (Response, String) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PairingException("Relais injoignable : ${e.message ?: "erreur réseau"}")
        }
        return response.use { r ->
            val text = runCatching { BoundedBody.readText(r, 64 * 1024) }.getOrDefault("")
            handle(r, text)
        }
    }

    private fun errorMessage(code: Int, body: String, fallback: String): String {
        val detail = runCatching { json.decodeFromString(ErrorBody.serializer(), body).error?.message }.getOrNull()
        return when (code) {
            429 -> "Trop de demandes, réessayez dans quelques minutes"
            503 -> detail ?: "Relais occupé"
            else -> detail?.let { "$fallback : $it" } ?: "$fallback (HTTP $code)"
        }
    }

    const val CODE_LENGTH = 8
}
