package com.lixirian.notesdefrais.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Expense::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao

    companion object {
        /** v1 → v2 : colonnes de synchronisation NAS ; les dépenses existantes reçoivent un uid et seront envoyées. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE expenses ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE expenses ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE expenses ADD COLUMN receiptDirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE expenses ADD COLUMN remoteHasReceipt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE expenses SET uid = lower(hex(randomblob(16))), updatedAt = createdAt, receiptDirty = CASE WHEN receiptPath IS NULL THEN 0 ELSE 1 END")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_uid ON expenses(uid)")
            }
        }

        /** v2 → v3 : corbeille datée (rétention 30 jours). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE expenses SET deletedAt = updatedAt WHERE deleted = 1")
            }
        }

        /** v3 → v4 : détail du ticket (TVA par taux, HT, paiement, numéro). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN vatLinesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE expenses ADD COLUMN amountHtCents INTEGER")
                db.execSQL("ALTER TABLE expenses ADD COLUMN paymentMethod TEXT")
                db.execSQL("ALTER TABLE expenses ADD COLUMN invoiceNumber TEXT")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
