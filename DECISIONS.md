# DECISIONS.md — choix faits seul pendant la construction

Contexte : la demande initiale visait iOS/SwiftUI, puis a été recentrée sur **Android uniquement,
avec un APK prêt à installer**, puis complétée en cours de route par : un look « au goût du
jour », l'adaptation aux **doubles écrans / pliables**, un **aperçu dans le navigateur**, l'usage
de l'**abonnement Claude plutôt qu'une clé API** (« cale-toi sur ce qui est fait pour claude
bot ») et un écran pour **revoir et filtrer tous les tickets** par mois/année. Tout ce qui suit a
été décidé sans poser de question.

## Plateforme et outillage

- **Kotlin + Jetpack Compose + Material 3** (pas de XML, pas de Flutter/React Native) : c'est
  le « natif » Android d'aujourd'hui, l'équivalent de SwiftUI côté Android.
- **AGP 9.4 + Gradle 9.6 + compileSdk 37 (Android 17)**. J'avais commencé en AGP 8.13 /
  compileSdk 36, mais toutes les bibliothèques récentes (Compose 1.12, OkHttp 5.5, Coil 3.6,
  Navigation 2.10…) exigent désormais compileSdk 37 et AGP ≥ 9.1. Plutôt que de figer des
  versions vieillissantes, j'ai migré vers la pile courante : Kotlin intégré à AGP (plus de
  plugin `kotlin-android`), plateforme `android-37.0` installée via sdkmanager.
- **Kotlin 2.3.21 / KSP 2.3.12 / Room 2.8.5 / Compose BOM 2026.09.00 / Material 3 Adaptive 1.3**.
  Versions vérifiées sur Google Maven et Maven Central au moment du build.
- **minSdk 26** (Android 8) : couvre quasiment tout le parc, et `java.time` est disponible
  sans désucrage.
- **Gradle wrapper versionné** : rien à installer hormis un JDK 17+ ; les scripts trouvent
  d'eux-mêmes le JDK d'Android Studio et le SDK dans `%LOCALAPPDATA%\Android\Sdk`.
- **Scripts bash** (`run.sh`, `build-apk.sh`, `preview.sh`) comme demandé à l'origine ; sous
  Windows ils tournent dans Git Bash. `run.sh` démarre un émulateur si aucun appareil n'est
  connecté, installe et lance l'app (options `--tablet`, `--fold`, `--release`, `--preview`).

## APK « prêt à l'installation »

- **Build release signé, R8 activé** (minification + réduction des ressources) : 2,3 Mo. Le
  build release a été installé et testé sur l'émulateur, pas seulement le debug, pour être sûr
  que les règles ProGuard (kotlinx.serialization, OkHttp) tiennent.
- **Keystore auto-signé versionné** (`keystore/release.jks`, mot de passe dans
  `keystore.properties`). C'est volontaire : un APK à sideloader doit pouvoir être mis à jour
  avec la même signature depuis n'importe quel poste. Ce keystore n'a aucune valeur pour le
  Play Store ; en cas de publication, en générer un autre.
- Le build **debug** porte le suffixe `.debug` pour cohabiter avec la release sur un même appareil.

## Données

- **Room** (équivalent Android de SwiftData) avec une seule entité `Expense`.
- **Montants en centimes (Long)**, jamais en Double : pas d'erreur d'arrondi, totaux exacts.
- **Dates en jours epoch (Long)** : regroupement par mois et tri triviaux en SQL.
- **TVA nullable** : un ticket sans TVA lisible n'est pas une TVA à 0 €. Le total HT est dérivé
  (TTC − TVA) tant que le ticket ne donne pas de HT explicite (`amountHtCents`).
- **Détail TVA par taux** (base v4) : lignes `{taux, base HT, TVA}` stockées en JSON dans une
  colonne (`vatLinesJson`) plutôt qu'une table liée : 1 à 3 lignes par ticket, toujours lues avec
  la dépense, jamais requêtées seules ; l'export CSV fabrique une colonne par taux rencontré. La
  TVA totale reste une colonne propre (somme des lignes quand elles existent) pour les totaux SQL.
  S'y ajoutent `paymentMethod` (enum : CB, espèces, chèque, virement, prélèvement, autre) et
  `invoiceNumber` ; migration 3→4 sans perte, le relais NAS synchronise ces champs.
- **Justificatifs dans le stockage interne** (`files/receipts/*.jpg`), réduits à 1 600 px,
  JPEG 85 %, orientation EXIF corrigée. Supprimés avec la dépense ; effacés si on abandonne
  une nouvelle dépense.
- **Catégories fixes** (10) adaptées à un indépendant français, stockées par nom d'enum.
  Pas de catégories personnalisées : cela complexifie l'export et le prompt sans gain réel.
- **Pas de sauvegarde cloud** : tout reste sur le téléphone (`allowBackup` reste actif pour la
  sauvegarde Android standard).

## Photo et appareil photo

- **Appareil photo système** via `ActivityResultContracts.TakePicture` + FileProvider, et
  **sélecteur photo** via `PickVisualMedia`. Aucune permission à demander (ni CAMERA, ni
  stockage) : parcours plus simple et plus rassurant. CameraX aurait ajouté du poids pour rien.
- Bug trouvé et corrigé en testant l'import depuis la photothèque (« Impossible d'ouvrir
  l'image ») : `BitmapFactory.decodeStream` renvoie null en mode `inJustDecodeBounds` et je
  prenais ce null pour un échec d'ouverture.

## LLM

- **Deux fournisseurs, une interface** (`ReceiptAnalyzer`) et **un seul schéma JSON**
  (montant TTC, TVA, date ISO, commerçant, catégorie) : sorties structurées Anthropic
  (`output_config.format`) et `response_format` JSON Schema strict côté OpenAI. Un repli tolère
  un JSON entouré de texte.
- **HTTP brut (OkHttp) plutôt que les SDK officiels** : le SDK Java Anthropic embarque Jackson
  (lourd, fragile sous R8) pour une seule requête ; un client OkHttp + kotlinx.serialization
  couvre les deux fournisseurs de façon symétrique, et le relais NAS parle le même format.
- **Modèles en mode clé API** : `claude-sonnet-5` (Anthropic, avec `effort: low` ; Opus 5
  serait surdimensionné et cher pour lire un ticket, remarque de l'utilisateur) et `gpt-5-mini`
  (OpenAI). Priorité à Anthropic si les deux clés existent,
  comme dans l'ordre de l'énoncé. Changement de modèle = une constante.
- **Pas de paramètre `fallbacks`** ni de bêta : une photo de ticket ne déclenche pas de refus,
  et je n'ai pas de clé pour valider des options supplémentaires. L'appel API direct n'a donc
  **pas été testé en conditions réelles** ; en cas d'erreur, le message HTTP du fournisseur est
  affiché tel quel dans l'écran d'édition, avec un bouton Réessayer, et la saisie manuelle reste
  possible. Le chemin relais NAS, lui, a été validé de bout en bout.
- **Prompt en français**, avec des règles explicites : ne jamais inventer la TVA, commerçant
  court sans adresse, date ISO.
- **Extraction détaillée** (demande utilisateur) : le schéma renvoie aussi `amount_ht`,
  `vat_lines[{rate, base_ht, vat}]`, `payment_method` (enum ou null) et `invoice_number`. Tout est
  nullable : un ticket sans « Détail TVA » donne `vat_lines: []`, l'app reconstruit alors une seule
  ligne au taux implicite si TVA et HT sont cohérents, sinon rien. Le relais NAS applique le même
  schéma et **assainit** les lignes (taux plausible, montants en centimes, cohérence avec le TTC).
  Validé de bout en bout sur un ticket restaurant à deux taux (10 % + 20 %) : les deux lignes,
  le HT, le paiement CB et le numéro de ticket sont pré-remplis.

## `.env`, clés et abonnement

- Une app Android **ne peut pas lire le `.env` du projet à l'exécution**. Le `.env` est donc
  lu par Gradle au build et injecté dans `BuildConfig` (la clé est alors embarquée dans l'APK :
  acceptable pour un usage personnel, à ne pas distribuer).
- Comme un APK « prêt à installer » sera souvent compilé sans `.env`, j'ai ajouté un **écran
  Réglages** : relais NAS > clé saisie dans l'app (DataStore) > clé du `.env`.
- **Relais NAS aligné sur un conteneur Claude Code déjà en place** (demande : « regarde ce qui est
  fait pour le bot et cale-toi dessus »). Ce bot fait tourner le vrai Claude Code CLI dans Docker
  sur le NAS, authentifié par un token d'abonnement longue durée (`claude setup-token`, ~1 an,
  `CLAUDE_CODE_OAUTH_TOKEN`). Le relais reprend exactement ce montage : même image de base
  (`node:22-bookworm` + `@anthropic-ai/claude-code`), utilisateur non-root aligné sur le
  propriétaire du dossier, même `.env` (`CLAUDE_CODE_OAUTH_TOKEN` + `CLAUDE_TOKEN_EXPIRES`), même
  preflight d'auth dans l'entrypoint, même pré-acquittement de l'onboarding dans `home/.claude.json`,
  même `restart: always`, un `relayctl.sh` sur le modèle du bot (`sudo -n docker-compose`) et un
  `deploy.sh` depuis le PC. Différence assumée : le relais expose un port car le téléphone doit
  le joindre ; le bot, lui, n'a que du trafic sortant.
- **Comment le relais lit un ticket** : il reçoit la requête au format Anthropic Messages (image
  base64 + prompt + schéma), écrit l'image dans `~/work`, lance `claude -p … --output-format json
  --json-schema … --allowedTools Read --no-session-persistence --model sonnet`, et renvoie le
  `structured_output` dans une réponse au format Anthropic. L'app ne voit aucune différence
  avec l'API. Validé de bout en bout sur le PC (Claude Code local, 33 à 51 s par ticket avec
  Sonnet, JSON strictement conforme). `sonnet` par défaut plutôt que `opus` : plus rapide et
  moins gourmand sur les limites Max ; réglable via `ANTHROPIC_MODEL`.
- **Pourquoi c'est acceptable** : `claude setup-token` est le mécanisme officiel d'Anthropic
  pour utiliser Claude Code sans session interactive, et c'est bien Claude Code lui-même qui
  tourne sur le NAS (pas une app tierce qui rejoue le token contre l'API). C'est le même usage
  que le bot Discord déjà en place. Ce que je n'ai pas fait : rejouer une session claude.ai ou
  chatgpt.com depuis l'app — contraire aux conditions d'utilisation et cassé à la première
  rotation de session.
- **Repli clé API conservé** dans le même relais (`ANTHROPIC_API_KEY` ou `OPENAI_API_KEY` →
  passthrough HTTP), et clés directes possibles dans l'app.
- **Déployé sur le NAS** (conteneur `notes-de-frais-relay`) via SSH par clé, comme le bot. Le
  token d'abonnement a été **copié depuis le `.env` du bot** (même compte Max) sans jamais
  transiter par le PC ; le jeton d'app a été généré sur le NAS. Validé de bout en bout depuis
  l'app sur l'émulateur : ticket → relais → Claude Code (Sonnet) → champs pré-remplis en 12 à 16 s.
- Deux bugs trouvés lors de ce test réel et corrigés : Android interdisait le HTTP en clair vers
  l'adresse LAN du NAS (ajout d'un `network_security_config` autorisant le trafic local en clair ;
  les API restent en HTTPS), et une erreur de connexion au relais remontait hors du `try/catch`
  et faisait planter l'app au lieu d'afficher « Analyse impossible ».
- **Accès hors du Wi-Fi (4G/5G)** : règle de proxy inversé HTTPS sur le NAS vers le port du
  relais. Vérifié depuis le PC : certificat valide, 401 sans jeton, analyse complète en 14 s.
  C'est l'URL de référence pour l'app ; l'URL locale reste valable en Wi-Fi. Un VPN reste une
  alternative sans exposition, non utilisée.
- **Rien de propre au site dans le dépôt public** (demande : les ports, adresses et chemins
  publiés « permettent aux gens de savoir comment y accéder ») : coordonnées SSH dans
  `tools/nas-relay/site.env` (git-ignoré), UID/GID, port publié, e-mail et URL publique dans le
  `.env` du NAS, volume Docker relatif, documentation avec des `<placeholders>`, captures d'écran
  montrant l'URL retirées, et **historique git réécrit** (un seul commit propre, anciennes
  releases supprimées) : les commits antérieurs contenaient ces informations.
- `scp` ne fonctionne pas vers DSM (pas de sous-système SFTP) : `deploy.sh` transfère par
  `tar` via SSH.

## Interface

- **Look Material 3 « expressif »** sans dépendre des API expérimentales : palette maison
  (indigo vif / menthe / ambre) plutôt que les couleurs dynamiques (rendu imprévisible d'un
  téléphone à l'autre), coins très arrondis, carte héro en dégradé avec le total du mois, chips
  de catégories colorées, liste groupée à coins « connectés », FAB étendu, feuille modale pour
  le choix photo / photothèque / saisie manuelle, bordure de l'app bord à bord.
- Le dégradé de la carte héro utilise des **couleurs fixes** (pas celles du thème) : en sombre,
  le primaire clair du thème rendait le texte blanc illisible (constaté sur capture, corrigé).
- **Mode sombre** : suit le système, palette dédiée, testé sur capture.
- **Fond « aurora »** (demande : rendre l'app plus attrayante, surtout le fond) : un composable
  `AppBackground` derrière toute la navigation, base unie + quatre halos radiaux (indigo, rose,
  menthe, ambre) dessinés au `Canvas`, atténués et assombris en mode sombre. Les écrans ont un
  `Scaffold` transparent et des cartes légèrement translucides pour laisser passer le fond. Pas
  d'animation du fond : coût GPU permanent pour un gain faible, et sensible sur pliable.
- **Suppression** : glissement vers la gauche avec annulation dans un snackbar, plus un bouton
  dans l'écran de détail avec confirmation (le justificatif part avec).
- **Validation** : montant et commerçant obligatoires, montants acceptés avec virgule ou point,
  erreurs affichées seulement après une tentative d'enregistrement.
- **Export CSV** : séparateur `;`, virgule décimale, BOM UTF-8 (sinon Excel massacre les
  accents), ligne TOTAL, colonnes HT, TVA totale, une colonne par taux de TVA présent dans
  l'export, mode de paiement, n° de ticket, Justificatif (oui/non) et Saisie (IA/manuelle).
  Fichier écrit dans le cache et partagé via FileProvider + feuille de partage Android.
- **Export ZIP** (demande : « dossiers par ticket et images, à l'unité / semaine / mois ») :
  une feuille `ExportSheet` commune (liste, fiche d'une dépense, Justificatifs) avec des
  périodes prêtes à l'emploi (`ExportScope` : cette semaine, semaine dernière, ce mois, mois
  dernier, tout ; ou une sélection / un ticket imposé) et deux formats. L'archive contient le
  CSV récapitulatif, un `LISEZMOI.txt` et un dossier par ticket `AAAA-MM-JJ_commercant_montant/`
  (`ticket.jpg`, `depense.json`, `depense.txt`). Le nom de dossier est un slug ASCII (accents
  retirés, tout autre caractère remplacé par `-`), avec suffixe `-2`, `-3` en cas de doublon.
  Le ZIP est construit en flux (`ZipOutputStream`, images copiées sans recompression) dans le
  cache de l'app et partagé comme le CSV : « téléchargement » sur Android = feuille de partage
  (Files, Drive, Gmail…), il n'y a pas d'écriture directe dans Téléchargements sans permission.

## Verrouillage par code et empreinte

- Demande : « par souci de cybersécurité », un code PIN défini et modifiable par l'utilisateur,
  plus l'empreinte. Implémenté en **opt-in** (un bandeau sur la liste invite à l'activer) :
  imposer un code à la première ouverture gênerait une diffusion de l'APK à d'autres personnes.
- **Code** : 4 à 8 chiffres, haché **PBKDF2-HMAC-SHA256** (100 000 itérations, sel aléatoire
  de 16 octets) dans un DataStore dédié, comparaison en temps constant. Changer le code ou le
  désactiver exige le code actuel. Cinq échecs → 30 s d'attente (anti-force brute local).
- **Empreinte** : `androidx.biometric` (BiometricPrompt, BIOMETRIC_STRONG ou WEAK, donc aussi
  le visage). Activable seulement si un code existe et si Android a une empreinte enregistrée ;
  l'activation demande une authentification biométrique. Le code reste toujours disponible en
  secours (bouton « Utiliser le code »). `MainActivity` devient une `FragmentActivity`, exigée
  par BiometricPrompt.
- **Règles** : verrouillé au démarrage à froid ; re-verrouillé après **30 s** en arrière-plan ;
  **pas** de verrouillage au retour de l'appareil photo ou de la photothèque (l'app les a
  lancés elle-même). Tant que l'app est verrouillée, le contenu n'est pas composé du tout :
  rien ne fuit dans l'aperçu des applications récentes.
- Testé sur l'émulateur : définition du code, refus du mauvais code, ouverture avec le bon,
  re-verrouillage après passage en arrière-plan, enrôlement d'une empreinte simulée
  (`adb emu finger touch`), activation dans les réglages, prompt biométrique automatique au
  lancement et déverrouillage par le capteur.

## Revue de sécurité (« blinder l'app »)

Détail complet dans `SECURITY.md`. Correctifs appliqués après revue du code et du relais :
- secrets (jeton, clés API) **chiffrés AES-GCM par une clé du Keystore Android**, avec
  migration transparente des valeurs stockées en clair par la version précédente ;
- secrets et code PIN **exclus des sauvegardes** Android (cloud et transfert) ;
- **HTTP en clair refusé** hors adresses privées (l'URL est validée dans les réglages) ;
- **blocage progressif persistant** après 5 codes faux (30 s → 10 min) ;
- biométrie **forte uniquement**, liée à une clé Keystore invalidée si une empreinte est
  ajoutée sur le téléphone (retour au code) ;
- option **FLAG_SECURE** (captures et aperçu des applications récentes bloqués) ;
- anti-**tapjacking** (`filterTouchesWhenObscured`) ;
- **anti-injection CSV** (apostrophe devant `= + - @`) ;
- prompt : le ticket est une **donnée**, pas une instruction ;
- relais : prompt et schéma **fixés côté serveur**, Claude Code limité à `Read` dans son
  dossier de travail (vérifié : lecture hors dossier refusée), **limitation de débit** par IP,
  tailles bornées, comparaison de jeton en temps constant, erreurs génériques.
- Non retenu, et pourquoi : chiffrement de bout en bout côté NAS, épinglage de certificat,
  détection de root, SQLCipher (voir `SECURITY.md`).

## Stockage NAS multi-appareils

- Demande : que les données soient sur le NAS et qu'un nouveau téléphone/tablette récupère
  tout en se connectant. Le **relais sert aussi de serveur de synchronisation** (mêmes
  conteneur, jeton et URL) : `GET/PUT /sync/expenses`, `GET/PUT /sync/receipts/<uid>`.
  Stockage en fichiers (`expenses.json` + images) dans le volume persistant du conteneur :
  simple, lisible, sauvegardable avec le reste du NAS ; pas de base serveur à administrer.
- **Hors ligne d'abord** : Room reste la source locale ; chaque dépense porte un `uid`, un
  `updatedAt`, un drapeau `dirty` et une pierre tombale `deleted`. Cycle : pousser les
  modifications locales, envoyer les justificatifs, tirer les changements distants depuis la
  dernière synchro, télécharger les justificatifs manquants, purger les tombales envoyées.
  Conflit : **la modification la plus récente gagne** ; le serveur ne perd jamais un
  justificatif déjà déposé.
- Déclencheurs : ouverture / retour au premier plan de l'app, chaque enregistrement ou
  suppression (regroupés à 1,5 s), enregistrement des réglages, bouton manuel.
- Migration Room v1 → v2 : les dépenses existantes reçoivent un `uid` et sont envoyées au
  premier lancement (vérifié sur l'émulateur : dépense existante retrouvée sur le NAS).
- Vérifié de bout en bout : appareil A (Android 16) pousse une dépense avec ticket ;
  appareil B (**Android 17**, neuf) saisit URL + jeton et récupère les deux dépenses et
  l'image ; suppression sur B propagée à A.

## Version d'Android

- Demande : « prendre la dernière version d'Android en vigueur ». L'app est compilée et
  ciblée pour **Android 17 (API 37)**, dernière version stable publiée par le SDK
  (`platforms;android-37.0`), et reste installable depuis Android 8. Un émulateur
  **Android 17** (`Phone_API_37`, image Google Play) a été ajouté et sert de second appareil
  de test ; les AVD Android 16 (téléphone, tablette, pliable) sont conservés.

## Corbeille

- Demande : supprimer une note supprime son justificatif mais le conserve 30 jours dans une
  corbeille récupérable. Implémenté par **suppression douce datée** (`deleted`, `deletedAt`) :
  la ligne et le fichier restent sur le téléphone et sur le NAS pendant 30 jours, puis une purge
  (locale après chaque synchronisation, serveur à chaque lecture et au démarrage) efface tout.
  Écran Corbeille (badge sur l'icône dans Justificatifs) : restaurer, supprimer définitivement,
  vider. La suppression définitive antidate la tombale au-delà de la rétention, ce qui fait
  purger le NAS et les autres appareils à leur prochaine synchronisation.
- Sans NAS configuré, la purge à 30 jours se fait quand même localement à l'ouverture de l'app.
- Vérifié : suppression sur A → corbeille sur A et sur B (image conservée sur le NAS) →
  restauration depuis B → dépense de retour sur A avec son justificatif.

## Justificatifs (revoir et filtrer les tickets)

- Demande : revenir sur tous les tickets chargés, classés, filtrables par mois/année. Écran
  **Justificatifs** (icône galerie en haut de la liste) : grille adaptative de cartes avec la
  vignette du ticket (ou l'icône de catégorie si saisie sans photo), filtres par **année**
  (années présentes dans les données), **mois**, **catégorie** et **avec justificatif**, carte
  de synthèse du filtre (nombre, tickets, TVA, total) et **export CSV de la sélection**
  (`notes-de-frais-2026-03-repas.csv`…). Le filtrage est fait en mémoire : quelques centaines
  de dépenses par an ne justifient pas des requêtes SQL dédiées.
- Toucher une carte ouvre la dépense dans le volet détail (deux volets sur tablette / pliable,
  comme la liste). Toucher l'aperçu du ticket ouvre un **visualiseur plein écran** avec zoom
  au pincement.

## Doubles écrans, pliables, tablettes

- **`ListDetailPaneScaffold` (Material 3 Adaptive 1.3)** : un volet sur téléphone, **liste +
  détail côte à côte** sur tablette, pliable déplié ou double écran. Le scaffold lit la posture
  de la fenêtre (charnière, pli) et place la séparation sur la charnière : c'est la brique
  prévue par Google pour Surface Duo / Galaxy Fold / Pixel Fold. Un hôte commun
  (`ListDetailHost`) sert la liste et l'écran Justificatifs ; les réglages restent une route
  plein écran (usage rare).
- Vérifié sur un **AVD Pixel 9 Pro Fold déplié** (2076×2152) : liste à gauche, formulaire à
  droite, placeholder « Sélectionnez une dépense » sinon. Sur téléphone, comportement inchangé
  (le détail prend tout l'écran, retour = fermer).
- Le volet détail reçoit ses paramètres sous forme de chaîne (`EditArgs`) car le navigateur
  list-detail ne sauvegarde que des types simples ; un nonce garantit un ViewModel neuf à
  chaque ouverture.
- Trois AVD créés : `Medium_Phone_API_36.1`, `Tablet_API_36.1` (Pixel Tablet),
  `Pliable_API_36.1` (Pixel 9 Pro Fold), reproductibles avec `tools/create-avds.sh`.

## Aperçu dans le navigateur

- Demande : voir le résultat dans un navigateur comme sur un téléphone/une tablette Android.
  Les solutions « officielles » (conteneur web de l'émulateur, WebRTC) ne tournent que sous
  Linux/Docker avec un proxy gRPC : disproportionné. J'ai écrit un **miroir adb** en Python
  standard (`tools/web-preview`) : capture `screencap` en boucle (3 à 6 images/s), cadre
  téléphone / tablette / pliable, clics → `input tap`, glissement/molette → `input swipe`,
  clavier → `input text` / `keyevent`, boutons Retour/Accueil/Récents, rotation, **pli /
  dépli** (`adb emu fold|unfold`), démarrage d'un AVD et installation de l'APK depuis la page.
  Vérifié dans Chrome : bascule téléphone → pliable, installation et lancement depuis la page.
- Les pliables exposent plusieurs écrans : `screencap` exige alors l'identifiant d'affichage,
  récupéré via `dumpsys SurfaceFlinger --display-id` et mis en cache.
- Ce n'est pas une vidéo fluide, mais un aperçu fidèle du rendu, sans dépendance à installer.

## Distribution GitHub et mise à jour automatique

- Demande : « comme Arrivée Collab et SNOW Widget, tout sur GitHub avec mise à jour auto ».
  Même schéma : dépôt **public** `Lixirian/notes-de-frais`, `latest.json` à la racine lu via
  raw.githubusercontent.com (aucun jeton, aucun compte), notes de version embarquées dans
  l'app (`assets/releases.json`) pour le « Quoi de neuf », script de publication unique
  (`publish.sh` ≈ `build-zip.ps1`) qui commit, pousse, publie et **contrôle** (`PUBLICATION OK`).
- **Comparaison sur `versionCode`** (entier Android), pas sur la chaîne de version : c'est ce
  qu'Android lui-même utilise, et cela évite tout parsing de `1.10.0` vs `1.9.0`.
- **APK sur une release GitHub** (`v<version>`, pièce jointe `notes-de-frais-<version>.apk`)
  plutôt que versionné dans le dépôt : un binaire de 3 Mo par version gonflerait l'historique
  git ; les releases sont faites pour ça et téléchargeables sans jeton sur un dépôt public.
  `dist/notes-de-frais.apk` (version courante) reste suivi pour l'installation initiale.
- **Installation par l'installateur système** (`ACTION_VIEW` + FileProvider, permission
  `REQUEST_INSTALL_PACKAGES`) : la seule voie hors Play Store. Android **refuse** un APK signé
  avec une autre clé que l'app installée : la clé `keystore/release.jks` est donc vitale et
  **exclue du dépôt** (elle ne vit que sur ce poste, synchronisé par SynologyDrive). En plus,
  l'app contrôle taille et **SHA-256** annoncés dans `latest.json`, n'accepte que des URL
  HTTPS sur les hôtes GitHub, et refuse une redirection non chiffrée.
- **Vérification au lancement, au plus toutes les 6 h**, désactivable ; « Plus tard » ignore une
  version jusqu'à la suivante, « Vérifier maintenant » la re-propose. Pas de vérification en
  arrière-plan (WorkManager) : l'app est ouverte régulièrement, inutile de réveiller le
  téléphone pour ça.
- Validé de bout en bout sur l'émulateur Android 17 : 1.1.0 installée → publication de 1.1.1
  → bandeau → téléchargement (SHA-256 vérifié) → autorisation « applications inconnues » →
  installateur → « Quoi de neuf » 1.1.1, données conservées. Ce test a révélé un bug corrigé en
  1.1.2 : le nettoyage du cache d'APK, lancé à chaque retour au premier plan, effaçait le fichier
  téléchargé pendant l'aller-retour vers le réglage système (l'installateur répondait ENOENT).
  Le nettoyage n'a lieu qu'au démarrage à froid, et jamais quand un APK est en cours ou prêt.
- Sur un appareil avec les services Google, **Play Protect** propose d'analyser l'app à la
  première installation hors Play Store (« Installer sans analyser » sous « Plus de détails »).
  C'est le comportement normal d'Android pour toute app hors Store, pas un défaut de l'APK.

## Seconde revue de sécurité (app + relais + GitHub)

- Demande : « vérifie les failles cyber de l'app et du GitHub ». Deux audits de code indépendants
  (app Android, relais NAS) plus un contrôle du dépôt (historique, réglages, releases, APK publié)
  et une sonde du relais public (TLS 1.3, HSTS, 401 partout sans jeton, 429 après échecs).
  Aucune faille critique. Constats corrigés dans la 1.2.0 et le relais redéployé :
  - **Élevée (latente)** : les clés `.env` sont compilées dans `BuildConfig`, release comprise, et
    l'APK est public. Aucun `.env` n'a jamais existé ici, mais `publish.sh` refuse désormais de
    publier si une clé est présente dans `.env` ou dans le code compilé. Le mécanisme `.env` est
    conservé pour qui compile un APK privé avec sa propre clé (demande initiale).
  - **Élevée (relais)** : limiteur de débit contournable en forgeant `X-Forwarded-For` (premier
    élément lu au lieu du dernier posé par le proxy), et retournable pour bloquer l'utilisateur
    légitime pendant 10 min. Corrigé : dernier élément, blocage des échecs sans effet sur un jeton valide.
  - **Moyennes** : fiche distante non validée (une date aberrante faisait planter l'app à chaque
    lancement, indéfiniment puisque re-tirée du NAS) ; empreinte/taille facultatives dans le
    manifeste de mise à jour ; tolérance de verrouillage illimitée pendant une activité externe ;
    côté relais `Content-Length` négatif, file d'attente d'analyses illimitée, justificatifs
    orphelins jamais purgés, secrets passés en argument de commande.
  - Le mot de passe du keystore (`notesdefrais2026`) figurait dans `build-apk.sh`, donc dans le
    dépôt public. Le fichier `.jks` n'y est pas, mais le mot de passe a été remplacé par un aléa de
    24 octets (ancien invalidé, sauvegarde `keystore/*.bak-*` locale) et le script en génère un
    aléatoire à la création. Réécrire l'historique git n'apporterait rien : le secret est révoqué.
  - GitHub : règle anti-suppression / anti-force-push sur `main`, Actions et wiki désactivés,
    Dependabot activé (secret scanning et push protection l'étaient déjà).
- Non retenu : lier le port 8787 à `127.0.0.1` (l'URL LAN en clair reste documentée comme option
  Wi-Fi maison ; l'app impose HTTPS hors adresses privées) ; chiffrement de bout en bout du NAS
  (voir SECURITY.md) ; `PackageInstaller` à la place de `ACTION_VIEW` (l'APK est public, sans gain).

## Appairage par code court

- Demande : « un mot de passe à la place du jeton », trop long à recopier. Un mot de passe
  permanent choisi par l'utilisateur affaiblirait le secret exposé sur Internet ; retenu à la
  place un **code d'appairage** : `relayctl.sh pair` dépose sur le NAS le SHA-256 d'un code à
  8 chiffres (10 min, usage unique, 5 essais) ; l'app l'envoie à `POST /pair` et reçoit le jeton
  long, stocké chiffré. Le secret fort reste le jeton, jamais vu ni tapé. Un code deviné est
  irréaliste (10⁸ combinaisons, 5 essais, fenêtre de 10 min ouverte seulement quand on le demande).
- **Premier appareil accepté d'office** (demande explicite) : tant que le relais n'a jamais remis
  ni vu utiliser son jeton, la première demande d'accès est approuvée automatiquement ; il n'y a
  personne pour l'autoriser et la fenêtre se referme au premier usage authentifié. Le marqueur
  est aussi posé par tout appareil déjà configuré (jeton saisi à la main).
- Pas de QR code : il aurait fallu une bibliothèque de scan et la permission caméra, pour gagner
  huit chiffres. La saisie manuelle du jeton reste possible (champ « avancé »).

## Ce qui reste non vérifié

- L'appel API direct Anthropic / OpenAI (aucune clé disponible pendant la construction) :
  format de requête conforme à la documentation courante, erreurs remontées à l'écran.
- Les exports CSV et ZIP ouvrent bien la feuille de partage ; les archives (mois et ticket
  unique) ont été extraites depuis une compilation debug et vérifiées (CSV, JSON avec les deux
  lignes de TVA, JPEG valides), mais le CSV n'a pas été ouvert dans Excel.
- L'appareil photo : l'émulateur n'a pas de caméra exploitable, le parcours a été validé via
  la photothèque (même code d'import ensuite).
