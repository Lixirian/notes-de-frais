package com.lixirian.notesdefrais.data

import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.YearMonth

/**
 * Accès aux dépenses. Chaque écriture marque la ligne « à synchroniser ». La suppression met
 * la dépense **et son justificatif** à la corbeille pendant 30 jours (restaurable), puis
 * [purgeExpired] efface tout. [onChanged] déclenche une synchronisation.
 */
class ExpenseRepository(private val dao: ExpenseDao) {

    /** Branché par l'app : appelé après chaque modification locale. */
    var onChanged: (() -> Unit)? = null

    fun observeAll(): Flow<List<Expense>> = dao.observeAll()

    fun observeTrash(): Flow<List<Expense>> = dao.observeDeleted()

    fun observeTrashCount(): Flow<Int> = dao.observeDeletedCount()

    suspend fun byId(id: Long): Expense? = dao.byId(id)

    suspend fun forMonth(month: YearMonth): List<Expense> =
        dao.betweenDays(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())

    suspend fun save(expense: Expense): Long {
        val previous = if (expense.id != 0L) dao.byId(expense.id) else null
        val receiptChanged = expense.receiptPath != null && expense.receiptPath != previous?.receiptPath
        val id = dao.upsert(
            expense.copy(
                updatedAt = System.currentTimeMillis(),
                dirty = true,
                receiptDirty = expense.receiptDirty || receiptChanged,
                remoteHasReceipt = previous?.remoteHasReceipt ?: expense.remoteHasReceipt,
            ),
        )
        onChanged?.invoke()
        return id
    }

    /** Met la dépense à la corbeille : ligne et justificatif conservés 30 jours. */
    suspend fun moveToTrash(expense: Expense) {
        val now = System.currentTimeMillis()
        dao.upsert(expense.copy(deleted = true, deletedAt = now, updatedAt = now, dirty = true))
        onChanged?.invoke()
    }

    /** Sort la dépense de la corbeille. */
    suspend fun restore(uid: String) {
        val current = dao.byUid(uid) ?: return
        val now = System.currentTimeMillis()
        dao.upsert(
            current.copy(
                deleted = false,
                deletedAt = 0,
                updatedAt = now,
                dirty = true,
                receiptDirty = current.receiptPath != null && !current.remoteHasReceipt,
            ),
        )
        onChanged?.invoke()
    }

    /**
     * Suppression définitive : la pierre tombale est antidatée au-delà de la rétention pour que
     * le NAS et les autres appareils purgent eux aussi, puis la ligne et le fichier local disparaissent
     * dès que la tombale a été envoyée (ou immédiatement si aucun NAS n'est configuré).
     */
    suspend fun deletePermanently(uid: String) {
        val current = dao.byUid(uid) ?: return
        val now = System.currentTimeMillis()
        dao.upsert(current.copy(deleted = true, deletedAt = now - Expense.RETENTION_MS - 1, updatedAt = now, dirty = true))
        onChanged?.invoke()
    }

    /** Purge locale de la corbeille : lignes expirées et leurs justificatifs. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int {
        val expired = dao.expiredDeleted(now - Expense.RETENTION_MS)
        expired.forEach { e ->
            if (e.dirty) return@forEach // tombale pas encore envoyée au NAS : on attend
            e.receiptPath?.let { runCatching { File(it).delete() } }
            dao.hardDelete(e.uid)
        }
        return expired.count { !it.dirty }
    }

    /** Sans NAS, rien ne retient les tombales : purge immédiate des expirées, même non envoyées. */
    suspend fun purgeExpiredOffline(now: Long = System.currentTimeMillis()) {
        dao.expiredDeleted(now - Expense.RETENTION_MS).forEach { e ->
            e.receiptPath?.let { runCatching { File(it).delete() } }
            dao.hardDelete(e.uid)
        }
    }
}
