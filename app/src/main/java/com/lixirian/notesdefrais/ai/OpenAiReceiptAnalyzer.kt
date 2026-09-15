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
 * Appel à l'API Chat Completions d'OpenAI avec une image en data-URL et un `response_format`
 * JSON Schema strict, pour obtenir exactement la même structure qu'avec Anthropic.
 * Via le relais NAS, [apiKey] est le jeton du relais et [baseUrl] l'adresse du NAS.
 */
class OpenAiReceiptAnalyzer(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.openai.com",
    private val displayProvider: AiProvider = AiProvider.OPENAI,
) : ReceiptAnalyzer {

    override val provider: AiProvider = displayProvider

    override suspend fun analyze(jpeg: ByteArray): ReceiptExtraction = withContext(Dispatchers.IO) {
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = buildJsonObject {
            put("model", model)
            put("max_completion_tokens", 1024)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", ReceiptPrompt.systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", dataUrl)
                                put("detail", "high")
                            }
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", ReceiptPrompt.userPrompt)
                        })
                    }
                })
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "receipt_extraction")
                    put("strict", true)
                    put("schema", ReceiptPrompt.schema)
                }
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

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
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw ReceiptAnalysisException("Réponse vide du modèle.")
        val refusal = message["refusal"]?.jsonPrimitive?.takeIf { it.isString }?.content
        if (!refusal.isNullOrBlank()) throw ReceiptAnalysisException("Le modèle a refusé d'analyser cette image : $refusal")
        val text = message["content"]?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: throw ReceiptAnalysisException("Réponse vide du modèle.")
        ReceiptPrompt.parseExtraction(text)
    }

    private fun extractError(body: String): String = runCatching {
        ReceiptPrompt.json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: body.take(200)

    companion object {
        const val DEFAULT_MODEL = "gpt-5-mini"
    }
}
