#!/usr/bin/env bash
# ============================================================================
#  Publie une version de « Mes notes de frais » (équivalent de build-zip.ps1 des
#  autres outils) :
#   1. lit versionName / versionCode dans app/build.gradle.kts ;
#   2. compile l'APK release signé (R8) -> dist/notes-de-frais.apk ;
#   3. génère latest.json (version, versionCode, URL de l'APK, SHA-256, taille,
#      notes de la version lues dans app/src/main/assets/releases.json) ;
#   4. COMMIT + PUSH sur origin (branche de travail + branche de publication
#      lue par les apps : main) ;
#   5. crée (ou remplace) la release GitHub v<version> avec l'APK en pièce jointe ;
#   6. CONTRÔLE la publication : origin/main porte ce build, latest.json en ligne
#      annonce cette version, l'APK de la release est téléchargeable.
#  Les apps installées voient la nouvelle version sous ~5 min (cache CDN raw).
#  Usage : ./publish.sh [--no-git] [--skip-build]
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"
source ./tools/env.sh
export PYTHONIOENCODING=utf-8   # accents et symboles dans les notes (console Windows en cp1252)

REPO="Lixirian/notes-de-frais"
PUB_BRANCH="main"
NO_GIT=0; SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --no-git) NO_GIT=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    *) echo "Option inconnue : $arg" >&2; exit 2 ;;
  esac
done

VER=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/')
CODE=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | head -1 | grep -oE '[0-9]+')
[ -n "$VER" ] && [ -n "$CODE" ] || { echo "versionName / versionCode introuvables dans app/build.gradle.kts" >&2; exit 1; }
ASSET="notes-de-frais-$VER.apk"
ASSET_URL="https://github.com/$REPO/releases/download/v$VER/$ASSET"
echo "▶ Version $VER (versionCode $CODE)"

# Les notes de cette version doivent exister : elles alimentent la pastille et le « Quoi de neuf ».
NOTES_JSON=$(python -c "import json,sys; d=json.load(open('app/src/main/assets/releases.json',encoding='utf-8')); n=d.get('$VER'); print(json.dumps(n, ensure_ascii=False) if n else '')")
[ -n "$NOTES_JSON" ] || { echo "Aucune note pour la version $VER dans app/src/main/assets/releases.json : ajoutez-la d'abord." >&2; exit 1; }

# --- Compilation ------------------------------------------------------------
if [ "$SKIP_BUILD" = 0 ]; then
  ./build-apk.sh
fi
[ -f dist/notes-de-frais.apk ] || { echo "dist/notes-de-frais.apk absent" >&2; exit 1; }

# Le dépôt et la release sont PUBLICS : refus catégorique si une clé API pourrait être embarquée
# (fichier .env avec une clé renseignée) ou se trouve dans le code compilé.
if [ -f .env ] && grep -qE '^(ANTHROPIC|OPENAI)_API_KEY=.+' .env; then
  echo "REFUS : .env contient une clé API ; elle serait compilée dans l'APK public. Videz-la (ou supprimez .env) avant de publier." >&2; exit 1
fi
if python - <<'EOF'
import re, sys, zipfile
z = zipfile.ZipFile('dist/notes-de-frais.apk')
pat = re.compile(rb'sk-ant-[A-Za-z0-9_-]{8,}|sk-proj-[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9]{32,}')
hits = [m.decode() for n in z.namelist() if n.endswith('.dex') for m in pat.findall(z.read(n))]
sys.exit(1 if hits else 0)
EOF
then :; else echo "REFUS : une clé API semble présente dans l'APK compilé." >&2; exit 1; fi

# Contrôle : l'APK produit porte bien la version annoncée.
AAPT=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2* 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$AAPT" ]; then
  BADGING=$("$AAPT" dump badging dist/notes-de-frais.apk 2>/dev/null | head -1)
  echo "$BADGING" | grep -q "versionCode='$CODE'" || { echo "L'APK n'a pas versionCode=$CODE : $BADGING" >&2; exit 1; }
  echo "$BADGING" | grep -q "versionName='$VER'" || { echo "L'APK n'a pas versionName=$VER : $BADGING" >&2; exit 1; }
fi

# --- latest.json --------------------------------------------------------------
SHA=$(sha256sum dist/notes-de-frais.apk | cut -d' ' -f1)
SIZE=$(stat -c %s dist/notes-de-frais.apk)
python - "$VER" "$CODE" "$ASSET_URL" "$SHA" "$SIZE" <<'EOF'
import json, sys
ver, code, url, sha, size = sys.argv[1:]
notes = json.load(open('app/src/main/assets/releases.json', encoding='utf-8'))[ver]
manifest = {"version": ver, "versionCode": int(code), "apk": url, "sha256": sha, "size": int(size), "notes": notes}
with open('latest.json', 'w', encoding='utf-8', newline='\n') as f:
    json.dump(manifest, f, ensure_ascii=False, indent=4); f.write('\n')
print("✔ latest.json :", ver, code, f"{int(size)/1e6:.2f} Mo", sha[:12] + "…")
EOF
cp dist/notes-de-frais.apk "build/$ASSET"

if [ "$NO_GIT" = 1 ]; then echo "Git : --no-git -> ni commit, ni push, ni release."; exit 0; fi

# --- Git : commit + push -------------------------------------------------------
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "Pas un dépôt git : lancez d'abord git init + remote origin." >&2; exit 1; }
git add -A
if ! git diff --cached --quiet; then
  MSG_FILE=$(mktemp)
  { echo "Version $VER"; echo; python -c "import json,sys; [print('- '+n) for n in json.loads(sys.argv[1])]" "$NOTES_JSON"; } > "$MSG_FILE"
  git commit -q -F "$MSG_FILE"; rm -f "$MSG_FILE"
  echo "Git : commit 'Version $VER' créé."
else
  echo "Git : rien à committer."
fi
CUR=$(git rev-parse --abbrev-ref HEAD)
git push -q origin HEAD
[ "$CUR" = "$PUB_BRANCH" ] || git push -q origin "HEAD:$PUB_BRANCH" || {
  echo "Git : push vers '$PUB_BRANCH' REFUSÉ. Rattrapage : git fetch origin ; git merge -s ours origin/$PUB_BRANCH ; git push origin HEAD:$PUB_BRANCH" >&2; exit 1; }

# --- Release GitHub avec l'APK ------------------------------------------------
NOTES_MD=$(python -c "import json,sys; print('\n'.join('- '+n for n in json.loads(sys.argv[1])))" "$NOTES_JSON")
if gh release view "v$VER" --repo "$REPO" >/dev/null 2>&1; then
  gh release upload "v$VER" "build/$ASSET" --repo "$REPO" --clobber >/dev/null
  echo "Release v$VER : APK remplacé."
else
  gh release create "v$VER" "build/$ASSET" --repo "$REPO" --title "Version $VER" --notes "$NOTES_MD" --target "$PUB_BRANCH" >/dev/null
  echo "Release v$VER créée avec $ASSET."
fi

# --- Contrôle final ----------------------------------------------------------
LOCAL_SHA=$(git rev-parse HEAD)
REMOTE_SHA=$(git ls-remote origin "refs/heads/$PUB_BRANCH" | cut -f1)
ONLINE_VER=$(curl -fsSL "https://raw.githubusercontent.com/$REPO/$PUB_BRANCH/latest.json?nocache=$(date +%s)" | python -c "import json,sys; print(json.load(sys.stdin)['version'])" 2>/dev/null || echo "?")
ASSET_HTTP=$(curl -sIL -o /dev/null -w '%{http_code}' "$ASSET_URL" || echo 000)
if [ "$REMOTE_SHA" = "$LOCAL_SHA" ] && [ "$ONLINE_VER" = "$VER" ] && [ "$ASSET_HTTP" = "200" ]; then
  echo "PUBLICATION OK : origin/$PUB_BRANCH porte la version $VER, latest.json en ligne l'annonce, l'APK est téléchargeable (bandeau visible dans l'app sous ~5 min)."
else
  echo "PUBLICATION KO :"
  echo "   origin/$PUB_BRANCH = $REMOTE_SHA (local $LOCAL_SHA)"
  echo "   latest.json en ligne = $ONLINE_VER (attendu $VER ; le CDN raw peut mettre ~5 min)"
  echo "   APK de la release : HTTP $ASSET_HTTP ($ASSET_URL)"
  echo "   -> tant que ce n'est pas corrigé, aucun utilisateur ne recevra la $VER."
  exit 1
fi
