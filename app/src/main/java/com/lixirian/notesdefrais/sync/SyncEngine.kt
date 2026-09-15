package com.lixirian.notesdefrais.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lixirian.notesdefrais.ai.ReceiptImages
import com.lixirian.notesdefrais.data.AiProvider
import com.lixirian.notesdefrais.data.Category
import com.lixirian.notesdefrais.data.Expense
import com.lixirian.notesdefrais.data.ExpenseDao
import com.lixirian.notesdefrais.data.PaymentMethod
import com.lixirian.notesdefrais.data.VatLine
import com.lixirian.notesdefrais.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.lixirian.notesdefrais.net.BoundedBody
import com.lixirian.notesdefrais.data.RelayPairing
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val Context.syncStore: DataStore<Preferences> by preferencesDataStore(name = "sync")

private val UID_RE = Regex("[0-9a-f]{32}")
private const val MAX_EPOCH_DAY = 1_000_000L      // ~ an 4700
private const val MAX_CENTS = 1_000_000_000_000L  // 10 milliards d'euros

@Serializable
data class RemoteExpense(
    val uid: String,
    val amountCents: Long,
    val vatCents: Long? = null,
    val dateEpochDay: Long,
    val merchant: String = "",
    val category: String = "AUTRE",
    val note: String = "",
    val createdAt: Long = 0,
    val aiExtracted: Boolean = false,
    val hasReceipt: Boolean = false,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
    val deletedAt: Long = 0,
    val vatLines: List<VatLine> = emptyList(),
    val amountHtCents: Long? = null,
    val paymentMethod: String? = null,
    val invoiceNumber: String? = null,
)

@Serializable private data class PullResponse(val serverTime: Long, val expenses: List<RemoteExpense> = emptyList())
@Serializable private data class PushRequest(val expenses: List<RemoteExpense>)
@Serializable private data class PushResponse(val serverTime: Long, val accepted: List<String> = emptyList(), val current: List<RemoteExpense> = emptyList())

sealed interface SyncState {
    data object Off : SyncState
    data object Idle : SyncState
    data object Running : SyncState
    data class Error(val message: String) : SyncState
}

data class SyncStatus(val state: SyncState = SyncState.Off, val lastSuccessAt: Long? = null)

/**
 * Synchronisation avec le NAS (relais) : la base Room reste la source locale (hors ligne),
 * le NAS la référence partagée. Cycle : pousser les modifications locales (dépenses puis
 * justificatifs), tirer les changements distants depuis la dernière synchro, télécharger les
 * justificatifs manquants. Conflit : la modification la plus récente gagne.
 */
class SyncEngine(
    private val context: Context,
    private val dao: ExpenseDao,
    private val settings: SettingsRepository,
    /** Purge locale de la corbeille (rétention écoulée), exécutée après chaque synchronisation. */
    private val purge: suspend () -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }
    private val http: OkHttpClient by lazy {
        // Aucune redirection légitime attendue du relais : ne jamais suivre (le jeton ne part pas ailleurs).
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val lastSyncKey = longPreferencesKey("last_server_time")
    private val lastSuccessKey = longPreferencesKey("last_success_at")
    private var debounceJob: Job? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    /** Demandes d'accès d'autres appareils, en attente d'autorisation (cet appareil est associé). */
    private val _pendingPairings = MutableStateFlow<List<RelayPairing.PendingRequest>>(emptyList())
    val pendingPairings: StateFlow<List<RelayPairing.PendingRequest>> = _pendingPairings

    suspend fun refreshPendingPairings() {
        val config = settings.current()
        if (config.provider != AiProvider.RELAY) { _pendingPairings.value = emptyList(); return }
        _pendingPairings.value = runCatching { RelayPairing.pending(config.relayUrl.trimEnd('/'), config.relayToken) }.getOrDefault(_pendingPairings.value)
    }

    suspend fun decidePairing(requestId: String, approve: Boolean): Boolean {
        val config = settings.current()
        if (config.provider != AiProvider.RELAY) return false
        val ok = runCatching { RelayPairing.decide(config.relayUrl.trimEnd('/'), config.relayToken, requestId, approve) }.getOrDefault(false)
        _pendingPairings.value = _pendingPairings.value.filterNot { it.id == requestId }
        return ok
    }

    /** Synchronisation différée (regroupe les modifications rapprochées). */
    fun requestSync(delayMs: Long = 1_500) {
        debounceJob?.cancel()
        debounceJob = scope.launch { delay(delayMs); syncNow() }
    }

    /** Synchronisation immédiate ; renvoie null si OK, sinon le message d'erreur. */
    suspend fun syncNow(): String? = withContext(Dispatchers.IO) {
        var config = settings.current()
        if (config.provider != AiProvider.RELAY) {
            // Une demande d'accès est peut-être en attente : si elle vient d'être autorisée, le jeton
            // est enregistré et la synchronisation démarre dans la foulée.
            val approved = runCatching { settings.pollOwnAccessRequest() }.getOrNull() is RelayPairing.Status.Approved
            if (approved) config = settings.current()
            if (config.provider != AiProvider.RELAY) {
                _status.value = SyncStatus(SyncState.Off, lastSuccess())
                return@withContext "Aucun relais NAS configuré"
            }
        }
        if (mutex.isLocked) return@withContext null
        mutex.withLock {
            _status.value = SyncStatus(SyncState.Running, lastSuccess())
            try {
                val base = config.relayUrl.trimEnd('/')
                val token = config.relayToken
                pushExpenses(base, token)
                pushReceipts(base, token)
                pullExpenses(base, token)
                pullMissingReceipts(base, token)
                purge.invoke()
                runCatching { refreshPendingPairings() }
                val now = System.currentTimeMillis()
                context.syncStore.edit { it[lastSuccessKey] = now }
                _status.value = SyncStatus(SyncState.Idle, now)
                null
            } catch (e: Exception) {
                val message = when (e) {
                    is IOException -> "NAS injoignable : ${e.message ?: "erreur réseau"}"
                    else -> e.message ?: "Erreur de synchronisation"
                }
                _status.value = SyncStatus(SyncState.Error(message), lastSuccess())
                message
            }
        }
    }

    private suspend fun lastSuccess(): Long? = context.syncStore.data.first()[lastSuccessKey]

    // ---------------------------------------------------------------- push

    private suspend fun pushExpenses(base: String, token: String) {
        val pending = dao.pending().filter { it.dirty }
        if (pending.isEmpty()) return
        val payload = PushRequest(pending.map { it.toRemote() })
        val request = Request.Builder()
            .url("$base/sync/expenses")
            .header("Authorization", "Bearer $token")
            .put(json.encodeToString(PushRequest.serializer(), payload).toRequestBody("application/json".toMediaType()))
            .build()
        val response = execute(request)
        val parsed = json.decodeFromString(PushResponse.serializer(), response)
        val maxUpdated = pending.maxOf { it.updatedAt }
        dao.markPushed(parsed.accepted, maxUpdated)
        // Les enregistrements refusés (plus récents côté NAS) sont réalignés tout de suite.
        parsed.current.filter { it.uid !in parsed.accepted }.forEach { applyRemote(it, base, token) }
    }

    private suspend fun pushReceipts(base: String, token: String) {
        dao.pending().filter { it.receiptDirty && !it.deleted }.forEach { expense ->
            val file = expense.receiptPath?.let(::File)
            if (file == null || !file.exists()) {
                dao.markReceiptUploaded(expense.uid); return@forEach
            }
            val request = Request.Builder()
                .url("$base/sync/receipts/${expense.uid}")
                .header("Authorization", "Bearer $token")
                .put(file.readBytes().toRequestBody("image/jpeg".toMediaType()))
                .build()
            execute(request)
            dao.markReceiptUploaded(expense.uid)
        }
    }

    // ---------------------------------------------------------------- pull

    private suspend fun pullExpenses(base: String, token: String) {
        val since = context.syncStore.data.first()[lastSyncKey] ?: 0L
        val request = Request.Builder()
            .url("$base/sync/expenses?since=$since")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val parsed = json.decodeFromString(PullResponse.serializer(), execute(request))
        parsed.expenses.forEach { applyRemote(it, base, token) }
        context.syncStore.edit { it[lastSyncKey] = parsed.serverTime }
    }

    private suspend fun applyRemote(remote: RemoteExpense, base: String, token: String) {
        // Données distantes = non fiables (relais compromis, MITM en clair sur le LAN) : une ligne
        // invalide est ignorée plutôt que stockée (elle ferait planter l'app à chaque lancement).
        if (!remote.isValid()) return
        val local = dao.byUid(remote.uid)
        when {
            // Mise à la corbeille distante : on garde la ligne et le justificatif (restaurable 30 jours).
            remote.deleted -> if (local != null && !local.dirty && remote.updatedAt > local.updatedAt) {
                dao.upsert(local.copy(deleted = true, deletedAt = remote.deletedAt, updatedAt = remote.updatedAt, dirty = false))
            } else if (local == null && remote.deletedAt > 0 && System.currentTimeMillis() - remote.deletedAt < Expense.RETENTION_MS) {
                dao.upsert(remote.toLocal(existing = null).copy(deleted = true, deletedAt = remote.deletedAt))
            }
            local == null -> {
                val inserted = remote.toLocal(existing = null)
                dao.upsert(inserted)
            }
            !local.dirty && remote.updatedAt > local.updatedAt -> dao.upsert(remote.toLocal(existing = local))
            // Modification locale non encore poussée : elle sera comparée au prochain push.
        }
    }

    private suspend fun pullMissingReceipts(base: String, token: String) {
        dao.missingReceipts().forEach { expense ->
            val request = Request.Builder()
                .url("$base/sync/receipts/${expense.uid}")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val bytes = runCatching { executeBytes(request) }.getOrNull() ?: return@forEach
            val target = File(ReceiptImages.receiptsDir(context), "${expense.uid}.jpg")
            target.writeBytes(bytes)
            dao.byUid(expense.uid)?.let { current ->
                if (current.receiptPath == null) dao.upsert(current.copy(receiptPath = target.absolutePath))
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun execute(request: Request): String = http.newCall(request).execute().use { response ->
        val text = BoundedBody.readText(response, BoundedBody.JSON_LIMIT)
        if (!response.isSuccessful) throw IllegalStateException("Le NAS a répondu ${response.code} : ${extractMessage(text)}")
        text
    }

    private fun executeBytes(request: Request): ByteArray? = http.newCall(request).execute().use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) throw IllegalStateException("Le NAS a répondu ${response.code}")
        BoundedBody.readBytes(response, BoundedBody.IMAGE_LIMIT)
    }

    private fun extractMessage(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: body.take(120)

    private fun RemoteExpense.isValid(): Boolean =
        UID_RE.matches(uid) &&
            dateEpochDay in 0..MAX_EPOCH_DAY &&
            kotlin.math.abs(amountCents) <= MAX_CENTS &&
            (vatCents == null || kotlin.math.abs(vatCents) <= MAX_CENTS) &&
            (amountHtCents == null || kotlin.math.abs(amountHtCents) <= MAX_CENTS) &&
            merchant.length <= 200 && note.length <= 2000 && (invoiceNumber?.length ?: 0) <= 100 &&
            vatLines.size <= 12

    private fun Expense.toRemote() = RemoteExpense(
        uid = uid, amountCents = amountCents, vatCents = vatCents, dateEpochDay = dateEpochDay, merchant = merchant,
        category = category.name, note = note, createdAt = createdAt, aiExtracted = aiExtracted,
        hasReceipt = receiptPath != null || remoteHasReceipt, updatedAt = updatedAt, deleted = deleted, deletedAt = deletedAt,
        vatLines = this.vatLines, amountHtCents = amountHtCents, paymentMethod = paymentMethod?.name, invoiceNumber = invoiceNumber,
    )

    private fun RemoteExpense.toLocal(existing: Expense?) = Expense(
        id = existing?.id ?: 0,
        uid = uid,
        amountCents = amountCents,
        vatCents = vatCents,
        dateEpochDay = dateEpochDay,
        merchant = merchant,
        category = Category.fromNameOrNull(category) ?: Category.AUTRE,
        note = note,
        vatLinesJson = VatLine.encode(vatLines),
        amountHtCents = amountHtCents,
        paymentMethod = PaymentMethod.fromNameOrNull(paymentMethod),
        invoiceNumber = invoiceNumber,
        receiptPath = existing?.receiptPath,
        createdAt = if (createdAt > 0) createdAt else (existing?.createdAt ?: System.currentTimeMillis()),
        aiExtracted = aiExtracted,
        updatedAt = updatedAt,
        deleted = false,
        dirty = false,
        receiptDirty = false,
        remoteHasReceipt = hasReceipt,
    )
}
