package com.lixirian.notesdefrais.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lixirian.notesdefrais.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.lixirian.notesdefrais.net.BoundedBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val Context.updateStore: DataStore<Preferences> by preferencesDataStore(name = "updates")

/**
 * Contenu de `latest.json` publié à la racine du dépôt GitHub de distribution (même schéma
 * que les autres outils : version + binaire + notes). `versionCode` est la clé de comparaison
 * (entier monotone), `version` n'est que l'étiquette lisible.
 */
@Serializable
data class UpdateManifest(
    val version: String,
    val versionCode: Int,
    val apk: String,
    val sha256: String? = null,
    val size: Long? = null,
    val notes: List<String> = emptyList(),
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Aucune version plus récente que celle installée. */
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateState
    data class ReadyToInstall(val manifest: UpdateManifest, val file: File) : UpdateState
    data class Error(val message: String, val manifest: UpdateManifest? = null) : UpdateState
}

data class UpdatePrefs(
    val autoCheck: Boolean = true,
    val lastCheck: Long = 0L,
    val dismissedVersionCode: Int = 0,
    /** Dernière version dont l'utilisateur a vu le « Quoi de neuf » (0 = première installation). */
    val lastSeenVersionCode: Int = 0,
)

/**
 * Mise à jour automatique, calquée sur Arrivée Collab / SNOW Widget : l'app lit `latest.json`
 * sur le **dépôt GitHub public** (raw.githubusercontent.com, aucun jeton), compare le
 * `versionCode` au sien, propose la nouvelle version avec ses notes, télécharge l'APK signé,
 * vérifie son empreinte SHA-256 puis ouvre l'installateur Android. Android n'accepte la mise à
 * jour que si l'APK est signé avec la même clé que l'app installée : un binaire substitué serait
 * refusé même si le dépôt était compromis.
 */
class UpdateManager(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)       // github.com/releases/download redirige vers le CDN GitHub (HTTPS)
        .followSslRedirects(false)   // jamais de passage HTTPS -> HTTP
        .build()

    private val autoCheckPref = booleanPreferencesKey("auto_check")
    private val lastCheckPref = longPreferencesKey("last_check")
    private val dismissedPref = intPreferencesKey("dismissed_version_code")
    private val lastSeenPref = intPreferencesKey("last_seen_version_code")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val prefs: Flow<UpdatePrefs> = app.updateStore.data.map { p ->
        UpdatePrefs(
            autoCheck = p[autoCheckPref] ?: true,
            lastCheck = p[lastCheckPref] ?: 0L,
            dismissedVersionCode = p[dismissedPref] ?: 0,
            lastSeenVersionCode = p[lastSeenPref] ?: 0,
        )
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    suspend fun setAutoCheck(enabled: Boolean) { app.updateStore.edit { it[autoCheckPref] = enabled } }

    /**
     * Vérification au retour au premier plan : au plus une fois par [CHECK_INTERVAL_MS], si
     * activée. Ne touche à rien si un téléchargement est en cours ou prêt : l'activité repasse
     * par ici en revenant du réglage « applications inconnues » ou de l'installateur, et le
     * fichier téléchargé doit survivre (bug corrigé en 1.1.2 : il était effacé, ENOENT à l'installation).
     */
    fun checkIfDue() {
        scope.launch {
            val current = _state.value
            if (current is UpdateState.Downloading || current is UpdateState.ReadyToInstall) return@launch
            val p = prefs.first()
            if (!p.autoCheck) return@launch
            if (System.currentTimeMillis() - p.lastCheck < CHECK_INTERVAL_MS && current !is UpdateState.Idle) return@launch
            check(manual = false)
        }
    }

    /**
     * Interroge `latest.json`. En mode manuel (bouton « Vérifier maintenant »), une version
     * ignorée est de nouveau proposée et l'état « à jour » est affiché explicitement.
     */
    suspend fun check(manual: Boolean) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        val result = runCatching { fetchManifest() }
        app.updateStore.edit { it[lastCheckPref] = System.currentTimeMillis() }
        result.onSuccess { manifest ->
            val dismissed = prefs.first().dismissedVersionCode
            _state.value = when {
                manifest.versionCode <= currentVersionCode -> UpdateState.UpToDate(System.currentTimeMillis())
                !manual && manifest.versionCode == dismissed -> UpdateState.UpToDate(System.currentTimeMillis())
                else -> UpdateState.Available(manifest)
            }
        }.onFailure { e ->
            _state.value = UpdateState.Error(if (manual) "Vérification impossible : ${e.message ?: e.javaClass.simpleName}" else "")
        }
    }

    /** « Plus tard » : la version n'est plus proposée automatiquement (mais reste accessible dans Réglages). */
    suspend fun dismiss(manifest: UpdateManifest) {
        app.updateStore.edit { it[dismissedPref] = manifest.versionCode }
        _state.value = UpdateState.UpToDate(System.currentTimeMillis())
    }

    /** Télécharge l'APK dans le cache, vérifie taille et SHA-256, puis passe en [UpdateState.ReadyToInstall]. */
    fun download(manifest: UpdateManifest) {
        if (_state.value is UpdateState.Downloading) return
        scope.launch {
            _state.value = UpdateState.Downloading(manifest, 0f)
            runCatching { downloadApk(manifest) }
                .onSuccess { file -> _state.value = UpdateState.ReadyToInstall(manifest, file) }
                .onFailure { e -> _state.value = UpdateState.Error("Téléchargement impossible : ${e.message ?: e.javaClass.simpleName}", manifest) }
        }
    }

    /** Vrai si Android autorise cette app à lancer l'installation d'un APK. */
    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /** Intent vers le réglage « Installer des applications inconnues » de cette app. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Le fichier prêt a-t-il disparu (cache vidé par le système) ? Alors on repart du téléchargement. */
    fun ensureReadyFileExists() {
        val s = _state.value
        if (s is UpdateState.ReadyToInstall && !s.file.exists()) _state.value = UpdateState.Available(s.manifest)
    }

    /** Intent d'installation (installateur système) pour l'APK téléchargé. */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Notes de version embarquées (`assets/releases.json`) pour la version installée. */
    suspend fun currentReleaseNotes(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = app.assets.open("releases.json").bufferedReader().readText()
            val all = json.decodeFromString<Map<String, List<String>>>(raw)
            all[currentVersion] ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * « Quoi de neuf » : vrai une seule fois après une mise à jour (jamais à la première
     * installation, où l'on se contente de mémoriser la version).
     */
    suspend fun consumeWhatsNew(): Boolean {
        val p = prefs.first()
        val show = p.lastSeenVersionCode in 1 until currentVersionCode
        if (p.lastSeenVersionCode != currentVersionCode) app.updateStore.edit { it[lastSeenPref] = currentVersionCode }
        return show
    }

    private suspend fun fetchManifest(): UpdateManifest = withContext(Dispatchers.IO) {
        val url = "$RAW_BASE/latest.json?nocache=${System.currentTimeMillis()}"
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            if (!response.request.url.isHttps) error("manifeste servi sans HTTPS")
            val manifest = json.decodeFromString<UpdateManifest>(BoundedBody.readText(response, BoundedBody.MANIFEST_LIMIT))
            validateManifest(manifest)
            manifest
        }
    }

    /**
     * Le manifeste est une donnée téléchargée : tout est vérifié avant usage. Empreinte et taille
     * sont OBLIGATOIRES (sinon la vérification annoncée à l'utilisateur n'aurait pas lieu), l'APK
     * ne peut venir que des releases de CE dépôt, la version ne contient que des caractères sûrs.
     */
    private fun validateManifest(m: UpdateManifest) {
        if (!VERSION_RE.matches(m.version)) error("version invalide dans le manifeste")
        if (m.versionCode <= 0) error("versionCode invalide")
        val sha = m.sha256 ?: error("empreinte SHA-256 absente du manifeste")
        if (!SHA256_RE.matches(sha)) error("empreinte SHA-256 invalide")
        val size = m.size ?: error("taille absente du manifeste")
        if (size <= 0 || size > MAX_APK_BYTES) error("taille d'APK invalide")
        if (m.notes.size > 50 || m.notes.any { it.length > 2000 }) error("notes de version invalides")
        validateApkUrl(m.apk)
    }

    private suspend fun downloadApk(manifest: UpdateManifest): File = withContext(Dispatchers.IO) {
        validateManifest(manifest)
        val expectedSize = manifest.size!!
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "notes-de-frais-${manifest.version}.apk")
        val tmp = File(dir, "${target.name}.part")
        val request = Request.Builder().url(manifest.apk).header("User-Agent", USER_AGENT).build()
        runCatching {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            if (!response.request.url.isHttps) error("téléchargement non chiffré refusé")
            val body = response.body
            if (body.contentLength() > expectedSize) error("taille annoncée par le serveur supérieure à celle publiée")
            val total = expectedSize
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastEmit = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (done > expectedSize) error("fichier plus gros que la taille publiée")
                        if (total > 0 && System.currentTimeMillis() - lastEmit > 100) {
                            _state.value = UpdateState.Downloading(manifest, (done.toFloat() / total).coerceIn(0f, 1f))
                            lastEmit = System.currentTimeMillis()
                        }
                    }
                }
            }
            if (done != expectedSize) error("taille inattendue ($done octets au lieu de $expectedSize)")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!manifest.sha256.equals(hash, ignoreCase = true)) error("empreinte SHA-256 différente de celle publiée")
        }
        }.onFailure { tmp.delete() }.getOrThrow()
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) error("impossible d'écrire le fichier")
        target
    }

    /**
     * Juste avant de lancer l'installateur : le fichier du cache est re-haché et comparé à
     * l'empreinte publiée (aucune fenêtre entre vérification et installation). Faux = on repart
     * du téléchargement.
     */
    fun verifyReadyFile(): Boolean {
        val s = _state.value as? UpdateState.ReadyToInstall ?: return false
        val ok = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            s.file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(s.manifest.sha256, ignoreCase = true) && s.file.length() == s.manifest.size
        }.getOrDefault(false)
        if (!ok) {
            s.file.delete()
            _state.value = UpdateState.Error("Le fichier téléchargé ne correspond plus à l'empreinte publiée : téléchargez-le à nouveau.", s.manifest)
        }
        return ok
    }

    /**
     * Seul un APK attaché à une release de CE dépôt est accepté. Analyse avec le même parseur
     * qu'OkHttp (HttpUrl) : ce qui est vérifié est exactement ce qui sera demandé.
     */
    private fun validateApkUrl(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: error("adresse d'APK invalide")
        if (!parsed.isHttps || parsed.host != "github.com" || parsed.port != 443 ||
            !parsed.encodedPath.startsWith("/$REPO/releases/download/") || parsed.encodedPath.contains("/../")
        ) error("adresse d'APK non autorisée")
    }

    companion object {
        const val REPO = "Lixirian/notes-de-frais"
        const val BRANCH = "main"
        const val RAW_BASE = "https://raw.githubusercontent.com/$REPO/$BRANCH"
        const val REPO_URL = "https://github.com/$REPO"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private val USER_AGENT = "NotesDeFrais-Update/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})"
        private val VERSION_RE = Regex("[0-9A-Za-z.-]{1,32}")
        private val SHA256_RE = Regex("[0-9a-fA-F]{64}")
        const val MAX_APK_BYTES = 50L * 1024 * 1024
    }
}
