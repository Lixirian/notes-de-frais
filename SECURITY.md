# Sécurité — revue et durcissement

Revue faite le 15 septembre 2026 sur l'app Android et le relais NAS, puis correctifs appliqués.
Ce document décrit ce qui est protégé, comment, et ce qui reste à la charge de l'utilisateur.

## Ce que l'app protège

- Les **dépenses** (montants, commerçants, dates, notes) et les **justificatifs** (photos de tickets).
- Le **jeton du relais NAS** et, le cas échéant, les **clés API** saisies dans l'app.
- L'**abonnement Claude** (token `setup-token` sur le NAS) : il ne doit servir qu'à lire des tickets.

## Mesures en place

### Sur le téléphone

| Risque | Mesure |
|---|---|
| Téléphone déverrouillé entre de mauvaises mains | Code PIN de l'app (4-8 chiffres) + empreinte, verrouillage à l'ouverture et après 30 s en arrière-plan |
| Force brute sur le code | PBKDF2-HMAC-SHA256 (100 000 itérations, sel aléatoire), blocage progressif après 5 échecs (30 s, 60 s, … jusqu'à 10 min), **persisté** : tuer l'app ne remet pas le compteur à zéro |
| Empreinte ajoutée par un tiers | Biométrie **forte** uniquement (classe 3), liée à une clé du Keystore `setInvalidatedByBiometricEnrollment` : toute nouvelle empreinte enrôlée invalide la clé, l'app retombe sur le code |
| Secrets lisibles dans les fichiers de l'app (sauvegarde, extraction) | Jeton et clés chiffrés **AES-256-GCM avec une clé du Keystore Android** (jamais exportable) ; les fichiers de secrets et de code sont **exclus des sauvegardes** Android (cloud et transfert) |
| Contenu visible dans les captures / le sélecteur d'applications | Option « Masquer dans les captures et les applications récentes » (FLAG_SECURE) ; de plus, tant que l'app est verrouillée, son contenu n'est pas composé du tout |
| Tapjacking (fenêtre superposée) | `filterTouchesWhenObscured` sur la fenêtre |
| Jeton envoyé en clair sur Internet | HTTP en clair refusé sauf vers une adresse **privée** (192.168.x.x, 10.x, 172.16-31.x, localhost) ; sinon HTTPS obligatoire. Les API Anthropic/OpenAI sont toujours en HTTPS |
| Export CSV piégé (formule injectée via un ticket) | Cellules texte commençant par `= + - @` ou tabulation préfixées d'une apostrophe (CSV seul et CSV inclus dans le ZIP) |
| Archive ZIP piégée (nom de dossier forgé via le commerçant) | Noms de dossiers réduits à `[a-z0-9-]` + date + montant : aucun `/`, `..` ni caractère spécial ne peut sortir du dossier d'extraction ; contenu limité à des fichiers texte/JSON/JPEG produits par l'app |
| Ticket contenant des instructions pour l'IA | Consigne explicite dans le prompt (le ticket est une donnée), schéma JSON strict : seuls des champs typés ressortent (montants, date, enum de paiement, lignes de TVA assainies par le relais) |
| Composants exposés | Une seule activité exportée (le lanceur) ; FileProvider non exporté, chemins limités (cache caméra, cache exports, justificatifs) ; aucune permission dangereuse (ni caméra, ni stockage) |
| Journaux | Aucun secret ni contenu de ticket dans les logs ; R8 activé (code minifié) |
| Mise à jour automatique détournée (faux APK) | Manifeste lu en HTTPS (64 Ko max), APK accepté **uniquement** depuis `github.com/Lixirian/notes-de-frais/releases/download/` (parseur OkHttp, redirection HTTPS→HTTP refusée), **SHA-256 et taille obligatoires** et vérifiés pendant le téléchargement puis **re-vérifiés juste avant l'installation** ; taille plafonnée à 50 Mo ; et surtout Android n'installe une mise à jour que si elle est **signée avec la même clé** que l'app en place : la clé de signature n'est pas dans le dépôt |
| Relais compromis ou MITM sur le LAN (données distantes) | Chaque fiche reçue est validée (identifiant hexadécimal, date et montants bornés, longueurs) avant stockage ; une date aberrante ne peut plus faire planter l'app ; corps de réponse bornés (10 Mo JSON, 20 Mo image) ; aucune redirection suivie par les clients relais/IA (le jeton ou la clé ne partent pas ailleurs) |
| Verrou contourné par une activité externe longue | Retour de l'appareil photo / du sélecteur toléré 5 min au plus (30 s sinon) ; rien n'est composé tant que l'état du verrou n'est pas lu ; `FLAG_SECURE` posé dès la création de la fenêtre ; blocage progressif appliqué aussi au dialogue « Changer / Désactiver le code » |
| Capture brute conservée (EXIF, GPS) | La photo écrite par l'app Caméra dans le cache est effacée après import ; captures, exports ZIP/CSV et APK téléchargés sont purgés à chaque démarrage à froid ; sauvegarde cloud des justificatifs seulement si l'appareil chiffre de bout en bout |
| Clé API compilée dans un APK public | `publish.sh` refuse de publier si `.env` contient une clé ou si le code compilé en contient une (le dépôt et les releases sont publics) |

### Sur GitHub (canal de distribution)

| Risque | Mesure |
|---|---|
| Branche `main` effacée ou réécrite (plus de `latest.json`, ou manifeste remplacé) | Règle de protection : suppression et force-push interdits sur `main` |
| Code exécuté dans le dépôt (workflow malveillant via fork ou PR) | GitHub Actions désactivées, aucun workflow ; wiki fermé |
| Secret poussé par erreur | Secret scanning + push protection activés ; `.env`, `keystore/`, jeton du relais git-ignorés ; historique vérifié (aucun secret) |
| Dépendance vulnérable | Alertes Dependabot et mises à jour de sécurité activées |
| Mot de passe du keystore | Aléatoire (24 octets), présent uniquement dans `keystore/keystore.properties` hors dépôt ; l'ancien mot de passe, qui figurait dans le script de build publié, a été invalidé |
| Compte GitHub | La seule vraie clé du canal : authentification à deux facteurs recommandée sur le compte `Lixirian` |

### Sur le NAS (relais)

| Risque | Mesure |
|---|---|
| Détournement de l'abonnement Claude par un porteur du jeton | Prompt et schéma **fixés côté serveur** : le relais ne sait faire qu'une chose, lire un ticket |
| Lecture de fichiers du NAS par le modèle (image piégée) | Claude Code lancé avec `--tools Read --allowedTools "Read(./*)"` : seul l'outil Read, seulement dans le dossier de travail ; `--no-session-persistence` ; vérifié : lecture hors dossier refusée |
| Force brute / déni de service | Limitation de débit par IP (120 requêtes / 10 min, 10 échecs d'authentification / 10 min), délai de 0,5 s sur jeton invalide, comparaison en temps constant, taille des corps bornée (12 Mo analyse, 10 Mo image, 4 Mo lot de dépenses) |
| Accès depuis Internet | Reverse proxy HTTPS du NAS (certificat valide, TLS 1.3, HSTS) ; le port du relais n'est joignable qu'en LAN et via le proxy ; aucune coordonnée (domaine, adresse, port publié, chemin) n'est publiée dans le dépôt |
| Limiteur de débit contourné ou retourné contre l'utilisateur (`X-Forwarded-For` forgé) | Adresse lue dans le **dernier** élément de `X-Forwarded-For` (celui posé par le proxy) ; le plafond d'échecs de jeton ne bloque que les requêtes **sans** jeton valide ; table d'adresses bornée (10 000) |
| Corps malformés, connexions lentes | `Content-Length` négatif / non numérique / chunked refusés, timeout socket 30 s, `mem_limit` 1 Go et `pids_limit` 256 sur le conteneur, `cap_drop ALL`, `no-new-privileges` |
| Épuisement disque par justificatifs orphelins | Justificatif accepté seulement pour une fiche existante non supprimée ; fichiers sans fiche purgés à chaque passage de la corbeille |
| File d'attente d'analyses illimitée | Verrou d'analyse borné (503 après 5 s d'attente) ; image écrite sur disque seulement une fois le verrou obtenu ; environnement minimal transmis à `claude` (ni jeton du relais ni clés API) |
| Empoisonnement de la synchro | `updatedAt` plafonné à maintenant + 5 min, montants et dates bornés, `NaN`/`Infinity` refusés, sauvegarde quotidienne de `expenses.json` (7 conservées) |
| Sortie libre du modèle relayée à l'app | Seule une sortie conforme au schéma JSON est renvoyée (sinon 502) ; longueurs maximales dans le schéma |
| Appairage par code court deviné | Code à 8 chiffres haché (SHA-256) dans `data/pairing.json`, valable 10 min, usage unique, 5 essais puis annulation, 1 s de délai par échec, limiteur de débit par adresse ; `POST /pair` est le seul endpoint sans jeton et ne renvoie que le jeton (jamais de données) |
| Secrets dans l'historique shell ou `ps` | `relayctl.sh settoken` lit le token sur stdin, `test` passe le jeton par fichier de configuration `curl`, `showrelaytoken` remplace le `grep` dans `.env` |
| Binaire Claude Code remplacé silencieusement | Version épinglée dans le Dockerfile (`2.1.270`) ; mode « bypass permissions » non pré-accepté |
| Conteneur | Utilisateur non-root (propriétaire du dossier), image dédiée, `restart: always`, aucun autre port |
| Données synchronisées | Fichiers dans `home/data` du relais (JSON + images), écritures atomiques, identifiants validés (32 hex), champs nettoyés et bornés |
| Premier appareil accepté d'office | Uniquement tant qu'aucun appareil n'a jamais reçu ni utilisé le jeton (marqueur `data/paired.marker` posé au premier usage authentifié) : la fenêtre se referme dès la première association ; l'URL du relais n'est publiée nulle part et le jeton reste hors de portée sans cette première demande |
| Demande d'accès (appairage sans commande) usurpée | Le demandeur ne reçoit qu'un identifiant secret (128 bits) et un code d'affichage ; le jeton n'est remis qu'après autorisation par un appareil déjà associé (jeton requis), par un lien e-mail portant une clé secrète à usage unique, ou par `relayctl.sh approve` sur le NAS ; demandes limitées à 20, expirées après 15 min, remise du jeton une seule fois, débit limité |
| Fuite de secrets dans les logs | Jamais de jeton ni de token dans les journaux ; erreurs internes renvoyées sous forme générique |

## Corbeille et données supprimées

Une dépense supprimée et son justificatif restent **30 jours** dans la corbeille, sur le téléphone
et sur le NAS, avant effacement définitif ; « Supprimer définitivement » ou « Vider la corbeille »
déclenchent l'effacement dès la synchronisation suivante. Ce délai est un choix de confort
(récupération d'une erreur) : pendant ces 30 jours, les données restent lisibles par qui détient
le jeton du relais ou l'accès au NAS.

## Ce qui reste à la charge de l'utilisateur

- **Le jeton du relais est le sésame** : qui l'a peut lire les tickets et **toutes les dépenses
  synchronisées**. Le garder hors des captures d'écran et des messageries ; le régénérer avec
  `relayctl.sh setrelaytoken` en cas de doute (puis le ressaisir sur chaque appareil).
- Les données sur le NAS sont **en clair** dans le volume Docker (comme les photos Immich ou les
  documents du NAS) : la protection est celle du NAS (comptes DSM, chiffrement du volume, sauvegardes).
- Le token d'abonnement Claude (`.env` du NAS, chmod 600) expire le 2027-06-24 : le relais
  l'annonce dans ses logs 30 jours avant.
- Le keystore de signature de l'APK (`keystore/release.jks`) est auto-signé et **hors dépôt**
  (le dépôt GitHub est public) : il ne vit que sur ce poste. Le perdre = plus aucune mise à jour
  installable par-dessus l'existant (réinstallation chez tous) ; le garder sauvegardé.
- Le dépôt GitHub étant public, la documentation mentionne l'adresse du reverse proxy du NAS :
  le relais y est protégé par le jeton et la limitation de débit, mais l'adresse est connue.
- Un téléphone **rooté** ou sans verrouillage système affaiblit toutes les protections locales
  (Keystore compris).

## Non fait, volontairement

- **Chiffrement de bout en bout des données sur le NAS** : impliquerait une clé partagée
  entre appareils (dérivée du jeton), donc perte des données à la rotation du jeton et sans
  intérêt réel sur un NAS personnel. À reconsidérer si le relais devait héberger plusieurs personnes.
- **Épinglage de certificat** (certificate pinning) : casserait à chaque renouvellement
  Let's Encrypt ; la validation système du certificat suffit.
- **Détection de root** : contournable et source de faux positifs.
- **Chiffrement de la base Room** (SQLCipher) : la base est dans le bac à sable de l'app, sur
  un stockage chiffré par Android ; le gain serait marginal face au poids de la dépendance.
