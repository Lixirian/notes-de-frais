# Mes notes de frais — repères pour Claude Code

Application Android native (Kotlin + Jetpack Compose, Material 3, Android 17 / API 37) pour un
indépendant : photo de ticket → lecture par IA (relais NAS sous abonnement Claude, ou clé API)
→ dépense pré-remplie (TTC, HT, TVA par taux, paiement, n° de ticket) → stockage local + NAS,
corbeille 30 jours, export ZIP/CSV, verrouillage code + empreinte. Détails : `README.md`
(usage), `DECISIONS.md` (choix techniques), `SECURITY.md` (revue sécurité).

> **Repères** : code → `app/src/main/java/com/lixirian/notesdefrais/` · relais NAS →
> `tools/nas-relay/` · aperçu navigateur → `./preview.sh` · **publier une version → section
> ci-dessous (démarche obligatoire)**.

## Lancer / compiler

```bash
./run.sh              # compile, démarre l'émulateur (Phone_API_37) si besoin, installe, lance
./run.sh --release    # idem avec l'APK release signé
./build-apk.sh        # APK release -> dist/notes-de-frais.apk
./preview.sh          # miroir de l'émulateur dans le navigateur (http://127.0.0.1:8765/)
```

Scripts à lancer depuis **Git Bash** (`tools/env.sh` localise le SDK dans `%LOCALAPPDATA%\Android\Sdk`
et le JDK d'Android Studio). Pile : AGP 9.4, Gradle 9.6, compileSdk 37, Kotlin intégré à AGP,
KSP, Room, DataStore, OkHttp, kotlinx-serialization, Coil, androidx.biometric.

## Publier une mise à jour (démarche OBLIGATOIRE pour toute évolution livrée)

Le canal de distribution est le dépôt GitHub **public `Lixirian/notes-de-frais`** (branche
`main`), comme pour Arrivée Collab : les apps installées lisent `latest.json` via
raw.githubusercontent.com (aucun jeton), comparent le `versionCode` au leur, et téléchargent
l'APK signé attaché à la **release GitHub `v<version>`** (empreinte SHA-256 contrôlée, puis
installateur Android). Toute modification livrée suit ces étapes, dans l'ordre :

1. **Incrémenter la version** dans `app/build.gradle.kts` : `versionCode` (+1, entier
   strictement croissant : c'est LUI que l'app compare) **et** `versionName` (`X.Y.Z` : mineur
   pour une fonctionnalité, patch pour un correctif).
2. **Ajouter l'entrée changelog** en tête de `app/src/main/assets/releases.json` (clé = le
   `versionName` exact). Ces notes alimentent le bandeau, la feuille de mise à jour et le
   « Quoi de neuf » affiché après installation. Rédiger pour l'utilisateur final, en français.
   Le script refuse de publier une version sans notes.
3. Si le **relais NAS** a changé (`tools/nas-relay/`) : `./tools/nas-relay/deploy.sh` (le NAS ne
   se met pas à jour depuis GitHub).
4. **Builder et publier** : `./publish.sh` — compile l'APK release, vérifie que l'APK porte bien
   la version annoncée, génère `latest.json` (version, versionCode, URL de l'APK, SHA-256,
   taille, notes), **commit + push sur `origin`** (branche de travail et `main`), crée la
   **release GitHub `v<version>`** avec `notes-de-frais-<version>.apk`, puis **contrôle** :
   `origin/main` porte ce build, `latest.json` en ligne annonce la version, l'APK répond 200.
   Il affiche **`PUBLICATION OK`** ; en cas de **`PUBLICATION KO`**, rien n'est distribué :
   appliquer le rattrapage affiché (le CDN raw peut mettre ~5 min ; relancer
   `./publish.sh --skip-build` pour re-contrôler). Les apps voient la nouvelle version au
   prochain lancement (vérification au plus toutes les 6 h ; « Vérifier maintenant » dans
   Réglages > Mises à jour force la lecture).
5. Copier le nouvel APK sur ses propres appareils n'est plus nécessaire : la pastille le fait.

`./publish.sh --no-git` produit l'APK et `latest.json` sans rien publier (test local).

**Ne jamais committer** : `.env`, `tools/nas-relay/.env`, `keystore/` (clé de signature : sans
elle, aucune mise à jour n'est installable par-dessus l'existant — elle ne vit que sur ce poste,
synchronisé par SynologyDrive), `build/relay-token.txt`. Le dépôt est PUBLIC.

## Architecture (résumé)

```
app/src/main/java/com/lixirian/notesdefrais/
  NotesDeFraisApp.kt   singletons (base Room, dépôts, SyncEngine, AppLock, UpdateManager)
  MainActivity.kt      FragmentActivity : fond aurora, verrouillage, FLAG_SECURE, sync + MAJ au retour
  data/                Room (Expense v4 : TVA par taux en JSON, HT, paiement, n° ticket), DAO, dépôt,
                       SettingsRepository (clés chiffrées Keystore), SecurityRepository (PIN, biométrie)
  ai/                  ReceiptAnalyzer (schéma JSON commun), Anthropic, OpenAI, relais NAS, images
  sync/                SyncEngine : push/pull dépenses + justificatifs vers le relais, corbeille
  export/              ZipExporter (un dossier par ticket), CsvExporter, ExportScope (périodes)
  update/              UpdateManager : latest.json GitHub, téléchargement, SHA-256, installation
  ui/                  thème, AppBackground, navigation, hôte liste/détail adaptatif (pliables)
  ui/list, ui/edit, ui/archive, ui/trash, ui/settings, ui/lock, ui/components
app/src/main/assets/releases.json   notes de version embarquées (« Quoi de neuf »)
tools/nas-relay/       relais Python (Claude Code headless + stockage), Dockerfile, deploy.sh
tools/web-preview/     miroir adb → navigateur
latest.json            manifeste de la dernière version publiée (généré par publish.sh)
dist/notes-de-frais.apk  APK courant (généré) · dist/screenshots/
```

## Points sensibles

- **Heredocs bash dans l'outillage Claude** : ils mangent backslashes et quotes ; écrire les
  fichiers avec l'outil Write ou des scripts Python.
- **Émulateur** : `adb shell input text` échoue si le tutoriel stylet Gboard s'affiche ; un
  `input swipe` clavier ouvert est interprété comme une frappe gestuelle (fermer le clavier
  d'abord) ; Git Bash réécrit `http://` sans `MSYS_NO_PATHCONV=1` ; pliable : `screencap -d <id>`.
- **Inspecter un fichier produit par l'app** (export, APK téléchargé) : installer l'APK debug
  (suffixe `.debug`, coexiste avec la release) puis `adb exec-out run-as
  com.lixirian.notesdefrais.debug cat <chemin> > fichier` (`exec-out`, pas `shell`).
- **Relais NAS** : `sudo -n` n'y autorise que docker ; transfert par `tar | ssh` (pas de scp).
  Appairage d'un appareil : `relayctl.sh pair` (code 8 chiffres, 10 min) puis Réglages > Associer
  dans l'app ; le jeton n'est plus recopié à la main.
  Dans `entrypoint.sh`, le script Node est dans une chaîne bash entre apostrophes : **aucune
  apostrophe dans ses commentaires** (le relais est tombé en 502 pour un « n'utilise »). Le noyau
  DSM ignore `pids_limit` (avertissement bénin au démarrage). Version de Claude Code épinglée
  dans le Dockerfile (`CLAUDE_CODE_VERSION`) : la monter volontairement, puis `deploy.sh`.
- **Sécurité** : après toute évolution touchant réseau, stockage, verrou ou mise à jour, relire
  `SECURITY.md` (revues du 2026-09-15) et garder les invariants : données distantes validées,
  corps HTTP bornés, aucune redirection suivie avec un secret, manifeste de MAJ strict, jamais de
  clé dans l'APK publié (`publish.sh` le vérifie).
- **Version-gating de la MAJ** : le code qui installe une mise à jour est celui de la version
  **qui fait** la mise à jour ; un changement de `update/` ne prend effet qu'à la MAJ suivante.
