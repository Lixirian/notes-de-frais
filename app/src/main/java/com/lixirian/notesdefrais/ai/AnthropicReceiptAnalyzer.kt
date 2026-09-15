package com.lixirian.notesdefrais.ai

import android.util.Base64
import com.lixirian.notesdefrais.net.BoundedBody
import com.lixirian.notesdefrais.data.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Appel direct à l'API Messages d'Anthropic (HTTP brut via OkHttp : le SDK Java officiel
 * embarque Jackson, trop lourd pour une app mobile qui ne fait qu'un seul type de requête).
 * Sorties structurées via `output_config.format` pour garantir un JSON conforme au schéma.
 *
 * Avec [bearerToken] et [baseUrl] pointant sur le relais NAS, la requête est identique mais
 * authentifiée par jeton : le relais ajoute la clé API côté serveur.
 */
class AnthropicReceiptAnalyzer(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.anthropic.com",
    private val bearerToken: String? = null,
    private val displayProvider: AiProvider = AiProvider.ANTHROPIC,
) : ReceiptAnalyzer {

    override val provider: AiProvider = displayProvider

    override suspend fun analyze(jpeg: ByteArray): ReceiptExtraction = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("system", ReceiptPrompt.systemPrompt)
            putJsonObject("output_config") {
                // Lecture d'un ticket : tâche simple, effort faible = réponse rapide et peu coûteuse.
                put("effort", "low")
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", ReceiptPrompt.schema)
                }
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                            }
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", ReceiptPrompt.userPrompt)
                        })
                    }
                })
            }
        }

        val builder = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (bearerToken != null) builder.header("Authorization", "Bearer $bearerToken") else builder.header("x-api-key", apiKey)
        val request = builder.build()

        val responseText = try {
            http.newCall(request).execute().use { response ->
                val text = BoundedBody.readText(response)
                if (!response.isSuccessful) {
                    throw ReceiptAnalysisException("${provider.label} a répondu ${response.code} : ${extractError(text)}")
                }
                text
            }
        } catch (e: IOException) {
            throw ReceiptAnalysisException("Connexion impossible (${provider.label}) : ${e.message ?: "erreur réseau"}", e)
        }

        val root: JsonObject = ReceiptPrompt.json.parseToJsonElement(responseText).jsonObject
        val stopReason = root["stop_reason"]?.jsonPrimitive?.content
        if (stopReason == "refusal") throw ReceiptAnalysisException("Le modèle a refusé d'analyser cette image.")
        val text = root["content"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "text" }
            ?.get("text")?.jsonPrimitive?.content
            ?: throw ReceiptAnalysisException("Réponse vide du modèle.")
        ReceiptPrompt.parseExtraction(text)
    }

    private fun extractError(body: String): String = runCatching {
        ReceiptPrompt.json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: body.take(200)

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5"
    }
}
