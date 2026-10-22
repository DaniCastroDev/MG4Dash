#!/bin/sh
# Compile la version voiture, la signe avec la clé plateforme AOSP (keys/platform.pk8) et, avec
# --publish, la publie comme release GitHub de DaniCastroDev/MG4Dash : c'est là que le bouton
# « Buscar actualización » d'Ajustes va la chercher.
#
#   sh release.sh                         # dist/MG4Dash-<version>.apk + SHA-256
#   sh release.sh --publish notas.md      # idem, puis release v<version> avec ces notes
set -eu

REPO=DaniCastroDev/MG4Dash
cd "$(dirname "$0")"

PUBLISH=0; NOTES=""
if [ "${1:-}" = "--publish" ]; then
    PUBLISH=1; NOTES="${2:?notes de version (fichier markdown) manquantes}"
    [ -f "$NOTES" ] || { echo "notes introuvables : $NOTES" >&2; exit 1; }
fi

[ -f keys/platform.pk8 ] || { echo "keys/platform.pk8 absent (voir README › Signing key)" >&2; exit 1; }
: "${JAVA_HOME:=$(/usr/libexec/java_home -v 17)}"; export JAVA_HOME
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"; export ANDROID_HOME
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)

VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
TAG="v$VERSION"
OUT="dist/MG4Dash-$VERSION.apk"

if [ "$PUBLISH" = 1 ]; then
    # La release pointe sur le commit compilé : il doit déjà être sur GitHub
    [ -z "$(git status --porcelain)" ] || { echo "modifications non commitées" >&2; exit 1; }
    git fetch -q origin main
    [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "HEAD n'est pas origin/main : pousse main d'abord" >&2; exit 1; }
    if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
        echo "la release $TAG existe déjà : augmente versionCode et versionName" >&2; exit 1
    fi
fi

sh ./gradlew --no-daemon -q assembleCarRelease
mkdir -p dist
"$BT/zipalign" -f -p 4 app/build/outputs/apk/car/release/app-car-release-unsigned.apk dist/aligned.apk
"$BT/apksigner" sign --key keys/platform.pk8 --cert keys/platform.x509.pem \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
    --out "$OUT" dist/aligned.apk
rm -f dist/aligned.apk
"$BT/apksigner" verify --print-certs "$OUT" | grep -E "Signer #1 certificate SHA-256"
echo "APK : $OUT"
shasum -a 256 "$OUT"

[ "$PUBLISH" = 1 ] || exit 0
# Release normale (pas une pré-version) : le bouton ne regarde que la dernière release « latest ».
gh release create "$TAG" "$OUT" -R "$REPO" --target "$(git rev-parse HEAD)" --title "MG4Dash $VERSION" --notes-file "$NOTES"
echo "publiée : https://github.com/$REPO/releases/tag/$TAG"
