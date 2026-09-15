# Mes notes de frais (Android)

Application Android native (Kotlin + Jetpack Compose, Material 3) pour un indépendant :
photographier un ticket, laisser un LLM pré-remplir montant TTC, HT, TVA (par taux), date,
commerçant, catégorie, mode de paiement et numéro de ticket, corriger, enregistrer, revoir et
filtrer ses justificatifs, exporter en **ZIP** (un dossier par ticket avec l'image) ou en CSV.

- **APK prêt à installer** : `dist/notes-de-frais.apk` (release signé, R8, ~3 Mo), ou la dernière
  release sur GitHub : https://github.com/Lixirian/notes-de-frais/releases/latest
- **Mises à jour automatiques** : une fois installée, l'app se met à jour toute seule depuis ce
  dépôt (voir plus bas).
- **Décisions techniques** : `DECISIONS.md`. **Sécurité** : `SECURITY.md`. Captures : `dist/screenshots/`.

## Installer l'APK sur un téléphone

1. Copier `dist/notes-de-frais.apk` sur le téléphone (câble, Drive, e-mail…).
2. Ouvrir le fichier, autoriser l'installation depuis cette source si demandé.
3. Android 8.0 (API 26) minimum, compilée pour **Android 17 (API 37)**, la version en vigueur. Compatible téléphones, tablettes, pliables et doubles écrans.

Ou, appareil branché en USB avec le débogage activé : `adb install -r dist/notes-de-frais.apk`.

## Mises à jour automatiques (GitHub)

Au lancement (au plus toutes les 6 h), l'app lit `latest.json` sur ce dépôt public via
raw.githubusercontent.com, sans jeton ni compte. Si une version plus récente est publiée, un
**bandeau** l'annonce sur la liste ; il ouvre une feuille avec les nouveautés et un bouton
**Télécharger et installer** : l'APK signé de la release GitHub est téléchargé, son empreinte
SHA-256 vérifiée, puis l'installateur Android prend le relais (la première fois, Android demande
d'autoriser l'app à installer des applications). Après redémarrage, un « Quoi de neuf » résume
la version. Réglages > **Mises à jour** : version installée, **Vérifier maintenant**, activation
de la vérification automatique, notes de la version. Aucune donnée n'est envoyée à GitHub.

Publier une version (mainteneur) : incrémenter `versionCode`/`versionName` dans
`app/build.gradle.kts`, ajouter les notes dans `app/src/main/assets/releases.json`, puis
`./publish.sh` (build, `latest.json`, commit + push, release GitHub avec l'APK, contrôle final
`PUBLICATION OK`). Détail dans `CLAUDE.md`.

## Lecture automatique des tickets (IA)

Trois façons d'activer l'IA, par ordre de priorité :

| Mode | Qui paie / où est le secret ? | Comment |
|---|---|---|
| **Relais NAS avec l'abonnement Claude** (recommandé) | Abonnement Max via un token `claude setup-token` (~1 an) stocké sur le NAS ; le téléphone n'a qu'un jeton obtenu par appairage | `tools/nas-relay/` puis Réglages > Relais NAS > Demander l'accès |
| Clé saisie dans l'app | Clé API pay-as-you-go, sur le téléphone (DataStore) | Réglages > Clés API directes |
| Fichier `.env` | Clé API embarquée dans l'APK à la compilation | Copier `.env.example` en `.env`, remplir `ANTHROPIC_API_KEY` **ou** `OPENAI_API_KEY`, recompiler |

Sans rien de tout cela, l'app fonctionne en **saisie manuelle** et l'indique clairement
(bandeau sur la liste, message dans l'écran d'ajout, écran Réglages). La photo du ticket est
alors simplement conservée comme justificatif.

Modèles en mode clé API : `claude-sonnet-5` (Anthropic) ou `gpt-5-mini` (OpenAI), environ un
centime par ticket (image réduite à 1 600 px). En mode relais : `sonnet` (défaut) ou `opus` de l'abonnement.

**Ce qui est extrait** quand le ticket le montre : montant TTC, montant HT, TVA totale, le
**détail par taux** (20 %, 10 %, 5,5 %, 2,1 %… avec base HT et TVA de chaque ligne), la date, le
commerçant, la catégorie, le mode de paiement (CB, espèces, chèque, virement…) et le numéro de
ticket ou de facture. Rien n'est inventé : un champ absent du ticket reste vide et se saisit à la
main (section « Détail TVA » de la fiche : un taux par ligne, « Ajouter un autre taux »).

### Relais NAS (abonnement Claude)

Le relais est un conteneur Docker sur votre NAS (`tools/nas-relay/`) qui embarque le **vrai Claude
Code CLI**. Il reçoit la photo du ticket, lance `claude -p … --json-schema …` avec le token
d'abonnement, et renvoie le JSON à l'app. Aucune clé API, aucune facturation à l'usage. Il sert
aussi de stockage synchronisé (voir plus bas). Rien de propre à votre installation n'est dans le
dépôt : les coordonnées du NAS vont dans `tools/nas-relay/site.env` (git-ignoré), les secrets dans
le `.env` du NAS.

```bash
cp tools/nas-relay/site.env.example tools/nas-relay/site.env   # NAS_SSH=utilisateur@nas, NAS_DIR=dossier du relais
./tools/nas-relay/deploy.sh                                     # copie les fichiers, build, démarrage
NAS="utilisateur@nas"; R="<dossier du relais>/relayctl.sh"
claude setup-token | ssh $NAS "$R settoken"                      # token d'abonnement, transmis par stdin (jamais en argument)
ssh $NAS "$R setrelaytoken"                                     # jeton des apps (elles l'obtiennent par appairage)
ssh $NAS "$R rebuild && $R test"                                # build de l'image, démarrage, vérification
ssh -t $NAS "$R setmail"                                        # facultatif : e-mail à chaque demande d'accès
ssh $NAS "$R setpublicurl https://<votre-domaine>"              # liens Autoriser / Refuser dans ces e-mails
```

**Associer un appareil (aucune commande)** : dans l'app, Réglages > Relais NAS > URL du relais,
puis **Demander l'accès**. **Le tout premier appareil est accepté d'office** (tant qu'aucun
appareil n'a jamais utilisé le jeton, il n'y a personne pour autoriser). Ensuite, l'appareil
affiche un code à 8 chiffres et attend. Le propriétaire
autorise depuis son téléphone déjà associé (bandeau « demande l'accès » sur l'accueil, puis
Réglages > Relais NAS > Autoriser, après avoir comparé le code), ou par le lien de l'e-mail reçu
si `setmail` est configuré, ou sur le NAS (`relayctl.sh approve <code>`). Le jeton arrive alors
tout seul et est stocké chiffré. Options avancées : code d'appairage (`relayctl.sh pair`) ou
saisie manuelle du jeton (`relayctl.sh showrelaytoken`).

**Depuis n'importe où (Wi-Fi, 4G/5G)** : exposez le relais en HTTPS par le proxy inversé de
votre NAS (`https://<votre-domaine>` → `http://localhost:<port>`, certificat valide) : c'est
l'URL à mettre dans l'app. L'URL locale `http://<ip-du-nas>:<port>` reste utilisable en Wi-Fi
maison (le HTTP en clair n'est accepté que vers une adresse privée). Le jeton est requis dans les
deux cas (401 sinon). `relayctl.sh` : `status`, `logs`, `auth`, `restart`, `rebuild`, `requests`…
Comptez 7 à 30 s par ticket avec `sonnet` (défaut) ; `ANTHROPIC_MODEL=opus` dans `.env` pour plus de précision.
Repli sans abonnement : mettre `ANTHROPIC_API_KEY` ou `OPENAI_API_KEY` dans le `.env` du NAS.

## Verrouillage de l'app (code + empreinte)

Réglages > Sécurité : définir un **code à 4-8 chiffres** (modifiable, désactivable après saisie
du code actuel), puis activer le **déverrouillage par empreinte** (ou visage, selon le téléphone).
L'app se verrouille à l'ouverture et après 30 s en arrière-plan ; pas de re-verrouillage au
retour de l'appareil photo ou de la photothèque. Cinq codes faux = 30 s d'attente. Le code est
stocké haché (PBKDF2-HMAC-SHA256, sel aléatoire), jamais en clair ; l'empreinte est vérifiée par
Android lui-même (BiometricPrompt).

## Stockage sur le NAS (multi-appareils)

Dès que le relais NAS est configuré, les dépenses **et** les justificatifs sont synchronisés
avec le NAS (dossier `home/data` du relais) : à chaque ouverture de l'app et après chaque
modification. Un **nouveau téléphone ou une tablette** n'a qu'à saisir l'URL et demander
l'accès dans Réglages > Relais NAS pour récupérer tout l'historique, images comprises. La base
locale reste utilisable hors ligne ; en cas de modification concurrente, la plus récente gagne ;
les suppressions se propagent. État et bouton « Synchroniser maintenant » dans Réglages > Stockage NAS.

## Corbeille (30 jours)

Supprimer une dépense la met à la **corbeille avec son justificatif** ; elle y reste 30 jours,
restaurable d'un geste (Justificatifs > icône corbeille, ou « Annuler » juste après la
suppression), puis elle est effacée définitivement, sur le téléphone **et** sur le NAS. La
corbeille est synchronisée entre appareils ; « Supprimer définitivement » et « Vider » n'attendent pas.

## Revoir ses tickets

Bouton « Justificatifs » (icône galerie, en haut de la liste) : tous les tickets chargés en grille,
filtres **année / mois / catégorie / avec justificatif**, total et TVA du filtre, export ZIP ou CSV
de la sélection. Toucher un ticket ouvre la dépense ; toucher l'aperçu du ticket l'affiche en plein
écran (pincer pour zoomer).

## Exporter (ZIP ou CSV)

Bouton **Exporter** (icône téléchargement en haut de la liste) : choisir la période (**cette
semaine, semaine dernière, ce mois, mois dernier, tout**) puis le format :

- **Archive ZIP** : `notes-de-frais-2026-09.zip` contenant `notes-de-frais.csv` (récapitulatif),
  `LISEZMOI.txt` et **un dossier par ticket** `AAAA-MM-JJ_commercant_montant/` avec `ticket.jpg`
  (le justificatif), `depense.json` (toutes les données, TVA par taux comprise) et `depense.txt`
  (lisible).
- **Tableau CSV seul** : une ligne par dépense, colonnes HT / TVA totale / une colonne par taux de
  TVA rencontré / mode de paiement / n° de ticket, séparateur `;`, ouvrable dans Excel.

Le fichier passe par la feuille de partage Android : Drive, Gmail, Files, Bluetooth, etc.
Le même bouton existe sur la fiche d'une dépense (**ZIP de ce seul ticket**) et dans
Justificatifs (export de la sélection filtrée).

## Développer

Prérequis : Android Studio (pour son JDK et le SDK) ou `ANDROID_HOME` + `JAVA_HOME` (JDK 17+).
Les scripts détectent automatiquement le SDK dans `%LOCALAPPDATA%\Android\Sdk` et le JDK
d'Android Studio.

```bash
./run.sh                 # compile, démarre l'émulateur téléphone si besoin, installe, lance
./run.sh --tablet        # idem sur l'AVD tablette
./run.sh --fold          # idem sur l'AVD pliable (déplié -> deux volets)
./run.sh --release       # installe l'APK release signé
./build-apk.sh           # produit dist/notes-de-frais.apk
./publish.sh             # build + latest.json + commit/push + release GitHub (mise à jour des apps)
./preview.sh             # aperçu dans le navigateur (voir ci-dessous)
./tools/create-avds.sh   # crée les AVD téléphone / tablette / pliable (API 36.1)
```

Sous Windows, lancez ces scripts depuis Git Bash (ou le terminal d'Android Studio).

## Aperçu dans le navigateur (téléphone, tablette, pliable)

`./preview.sh` ouvre `http://127.0.0.1:8765/` : l'écran de l'émulateur est diffusé dans un
cadre de téléphone/tablette, les clics, glissements, molette et frappes clavier sont renvoyés
à l'appareil. Depuis la page : démarrer un AVD, installer l'app, Retour/Accueil/Récents,
pivoter, **plier/déplier** (l'app bascule alors entre un et deux volets).

Fonctionne avec n'importe quel appareil visible par `adb` (émulateur ou téléphone USB).
C'est un miroir via `adb screencap` (quelques images par seconde), fidèle pour juger le rendu.

## Structure

```
app/src/main/java/com/lixirian/notesdefrais/
  data/        Room (Expense, DAO, base), dépôt, réglages DataStore
  ai/          analyse de ticket : Anthropic, OpenAI, relais NAS, redimensionnement image
  export/      ZIP (un dossier par ticket + CSV) et CSV, périodes semaine/mois/tout/sélection
  update/      mise à jour automatique : latest.json GitHub, téléchargement, SHA-256, installation
  ui/          thème, navigation, hôte liste/détail adaptatif
  ui/list      liste groupée par mois, ajout (photo / photothèque / manuel)
  ui/edit      formulaire, analyse IA, visualiseur de ticket
  ui/archive   Justificatifs : grille filtrable année / mois / catégorie
  ui/settings  sécurité (code, empreinte, captures), stockage NAS, relais NAS, clés API
  sync/        synchronisation NAS (push/pull dépenses et justificatifs)
  ui/lock      écran de verrouillage, règles de verrouillage, biométrie
tools/web-preview/   serveur d'aperçu navigateur (Python, sans dépendance)
tools/nas-relay/     relais NAS (Dockerfile + Claude Code CLI, relay.py, relayctl.sh, deploy.sh)
keystore/            clé de signature auto-signée (mot de passe dans keystore.properties)
```
