package com.lixirian.notesdefrais

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lixirian.notesdefrais.data.SecurityConfig
import com.lixirian.notesdefrais.ui.AppNavGraph
import com.lixirian.notesdefrais.ui.lock.LockScreen
import com.lixirian.notesdefrais.ui.theme.AppBackground
import com.lixirian.notesdefrais.ui.theme.NotesDeFraisTheme
import kotlinx.coroutines.launch

/** FragmentActivity (et non ComponentActivity) : requis par BiometricPrompt. */
class MainActivity : FragmentActivity() {

    private val app: NotesDeFraisApp get() = application as NotesDeFraisApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Anti-tapjacking : ignore les touchers reçus quand une autre fenêtre recouvre l'app.
        window.decorView.filterTouchesWhenObscured = true
        // FLAG_SECURE dès la création (avant lecture du réglage) : aucune image de l'app ne peut être
        // capturée pendant les premières frames ; le réglage utilisateur le lève ensuite si besoin.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            NotesDeFraisTheme {
                val locked by app.appLock.locked.collectAsStateWithLifecycle()
                val security by app.securityRepository.config.collectAsStateWithLifecycle(initialValue = SecurityConfig())

                // FLAG_SECURE : pas de capture d'écran ni d'aperçu dans les applications récentes (réglage utilisateur).
                LaunchedEffect(security.hideFromScreenshots) {
                    if (security.hideFromScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }

                Box(Modifier) {
                    // Le contenu n'est pas composé tant que l'app est verrouillée ni tant que l'état du
                    // verrou est inconnu (lecture de la configuration) : rien ne fuit à l'écran.
                    if (locked == false) AppBackground { AppNavGraph() }
                    AnimatedVisibility(visible = locked == true, enter = fadeIn(), exit = fadeOut()) {
                        LockScreen(appLock = app.appLock, security = app.securityRepository, config = security)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { app.appLock.onActivityStart() }
        // Récupère les données du NAS à chaque retour au premier plan (nouvel appareil compris).
        app.syncEngine.requestSync(delayMs = 300)
        // Nouvelle version publiée sur GitHub ? (au plus toutes les 6 h, désactivable dans Réglages)
        app.updateManager.checkIfDue()
        // Sans NAS, la corbeille se purge quand même au bout de 30 jours.
        lifecycleScope.launch { if (app.settingsRepository.current().relayUrl.isBlank()) app.expenseRepository.purgeExpiredOffline() }
    }

    override fun onStop() {
        app.appLock.onActivityStop()
        super.onStop()
    }
}
