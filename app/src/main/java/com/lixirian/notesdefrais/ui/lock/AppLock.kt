package com.lixirian.notesdefrais.ui.lock

import android.os.SystemClock
import com.lixirian.notesdefrais.data.SecurityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Règles de verrouillage :
 * - verrouillé au démarrage à froid si un code est défini ;
 * - re-verrouillé si l'app est restée en arrière-plan plus de [GRACE_MS] ;
 * - pas de verrouillage au retour de l'appareil photo / du sélecteur de photos (lancés par l'app) ;
 * - après trop de codes faux, attente progressive persistée (survit à un arrêt forcé de l'app).
 */
class AppLock(private val security: SecurityRepository) {

    /** null tant que la configuration n'a pas été lue : l'activité n'affiche rien pendant ce temps. */
    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked

    private var stoppedAt: Long? = null
    private var externalActivityInProgress = false
    private var coldStartChecked = false

    /** À appeler à chaque onStart de l'activité. */
    suspend fun onActivityStart() {
        val config = security.current()
        if (!config.hasPin) {
            _locked.value = false
            return
        }
        if (!coldStartChecked) {
            coldStartChecked = true
            _locked.value = true
            return
        }
        val since = stoppedAt
        // Retour de l'appareil photo / du sélecteur : tolérance plus longue, mais bornée (quelqu'un
        // qui laisse la caméra ouverte des heures ne doit pas retrouver l'app déverrouillée).
        val grace = if (externalActivityInProgress) EXTERNAL_GRACE_MS else GRACE_MS
        if (since != null && SystemClock.elapsedRealtime() - since > grace) {
            _locked.value = true
        } else if (_locked.value == null) {
            _locked.value = false
        }
        stoppedAt = null
    }

    /** À appeler à chaque onStop de l'activité. */
    fun onActivityStop() {
        stoppedAt = SystemClock.elapsedRealtime()
    }

    /** L'app lance un autre écran système (caméra, photothèque) : tolérance [EXTERNAL_GRACE_MS] au retour. */
    fun beginExternalActivity() { externalActivityInProgress = true }
    fun endExternalActivity() { externalActivityInProgress = false }

    fun lockNow() { _locked.value = true }

    /** Secondes d'attente restantes après trop d'échecs, 0 si aucune. */
    suspend fun lockoutSecondsLeft(): Int {
        val left = security.lockoutRemainingMs()
        return if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    suspend fun tryUnlockWithPin(pin: String): Boolean {
        if (security.lockoutRemainingMs() > 0) return false
        val ok = security.verifyPin(pin)
        if (ok) {
            security.clearFailures()
            _locked.value = false
        } else {
            security.registerFailedAttempt()
        }
        return ok
    }

    suspend fun unlockWithBiometric() {
        security.clearFailures()
        _locked.value = false
    }

    companion object {
        const val GRACE_MS = 30_000L
        const val EXTERNAL_GRACE_MS = 5 * 60_000L
    }
}
