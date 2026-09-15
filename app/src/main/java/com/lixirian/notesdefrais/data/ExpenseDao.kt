package com.lixirian.notesdefrais.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {

    @Query("SELECT * FROM expenses WHERE deleted = 0 ORDER BY dateEpochDay DESC, createdAt DESC")
    fun observeAll(): Flow<List<Expense>>

    /** Corbeille : dépenses supprimées, les plus récentes d'abord. */
    @Query("SELECT * FROM expenses WHERE deleted = 1 ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<Expense>>

    @Query("SELECT COUNT(*) FROM expenses WHERE deleted = 1")
    fun observeDeletedCount(): Flow<Int>

    @Query("SELECT * FROM expenses WHERE deleted = 0 AND dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY dateEpochDay ASC, createdAt ASC")
    suspend fun betweenDays(fromEpochDay: Long, toEpochDay: Long): List<Expense>

    @Query("SELECT * FROM expenses WHERE id = :id AND deleted = 0")
    suspend fun byId(id: Long): Expense?

    @Query("SELECT * FROM expenses WHERE uid = :uid")
    suspend fun byUid(uid: String): Expense?

    @Query("SELECT * FROM expenses WHERE dirty = 1 OR receiptDirty = 1")
    suspend fun pending(): List<Expense>

    @Query("SELECT * FROM expenses WHERE deleted = 0 AND remoteHasReceipt = 1 AND receiptPath IS NULL")
    suspend fun missingReceipts(): List<Expense>

    /** Dépenses de la corbeille dont la rétention est écoulée (à purger avec leur justificatif). */
    @Query("SELECT * FROM expenses WHERE deleted = 1 AND deletedAt > 0 AND deletedAt < :cutoff")
    suspend fun expiredDeleted(cutoff: Long): List<Expense>

    @Upsert
    suspend fun upsert(expense: Expense): Long

    @Query("UPDATE expenses SET dirty = 0 WHERE uid IN (:uids) AND updatedAt <= :upTo")
    suspend fun markPushed(uids: List<String>, upTo: Long)

    @Query("UPDATE expenses SET receiptDirty = 0, remoteHasReceipt = 1 WHERE uid = :uid")
    suspend fun markReceiptUploaded(uid: String)

    @Query("DELETE FROM expenses WHERE uid = :uid")
    suspend fun hardDelete(uid: String)
}
