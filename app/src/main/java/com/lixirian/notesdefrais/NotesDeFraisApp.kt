package com.lixirian.notesdefrais

import android.app.Application
import androidx.room.Room
import com.lixirian.notesdefrais.ai.ReceiptAnalyzerFactory
import com.lixirian.notesdefrais.data.AppDatabase
import com.lixirian.notesdefrais.data.ExpenseRepository
import com.lixirian.notesdefrais.data.SecurityRepository
import com.lixirian.notesdefrais.data.SettingsRepository
import com.lixirian.notesdefrais.sync.SyncEngine
import com.lixirian.notesdefrais.ui.lock.AppLock
import com.lixirian.notesdefrais.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Point d'entrée : instancie les singletons (base Room, dépôts) sans framework d'injection.
 * L'app est petite ; un graphe manuel reste plus lisible qu'un Hilt/Koin.
 */
class NotesDeFraisApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "notes-de-frais.db")
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
    }

    val expenseRepository: ExpenseRepository by lazy {
        ExpenseRepository(database.expenseDao()).also { repo -> repo.onChanged = { syncEngine.requestSync() } }
    }

    /** Synchronisation des dépenses et justificatifs avec le NAS (via le relais). */
    val syncEngine: SyncEngine by lazy { SyncEngine(this, database.expenseDao(), settingsRepository) { expenseRepository.purgeExpired() } }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val analyzerFactory: ReceiptAnalyzerFactory by lazy { ReceiptAnalyzerFactory() }

    val securityRepository: SecurityRepository by lazy { SecurityRepository(this) }

    /** État de verrouillage de l'app (code PIN / empreinte), partagé entre l'activité et les écrans. */
    val appLock: AppLock by lazy { AppLock(securityRepository) }

    /** Mise à jour automatique depuis le dépôt GitHub public (latest.json + APK signé). */
    val updateManager: UpdateManager by lazy { UpdateManager(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Les secrets enregistrés en clair par une version antérieure sont re-chiffrés (Keystore).
        appScope.launch { runCatching { settingsRepository.migrateLegacySecrets() } }
        // Captures caméra, exports et APK téléchargés ne survivent pas à un redémarrage de l'app.
        appScope.launch { com.lixirian.notesdefrais.ai.ReceiptImages.purgeCaches(this@NotesDeFraisApp) }
    }
}
