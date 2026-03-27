#!/usr/bin/env bash
set -uo pipefail

ADB="${ANDROID_HOME}/platform-tools/adb"
PACKAGE="com.ghostwan.podcasto"
ACTIVITY="${PACKAGE}/.MainActivity"
SCREENSHOT_DIR="screenshots"
DEVICE_TMP="/sdcard/podcasto_screenshot.png"
README="README.md"

echo "=== Podcasto — Capture de screenshots interactive ==="
echo ""

# Sauvegarder la locale actuelle
ORIGINAL_LOCALE=$($ADB shell cmd locale get-device-locale)
echo "Locale actuelle : $ORIGINAL_LOCALE"

# Passer en anglais
echo "Passage en anglais (en-US)..."
$ADB shell cmd locale set-device-locale en-US || true
sleep 3

# Redémarrer l'app pour appliquer la locale
echo "Redémarrage de l'app..."
$ADB shell am force-stop "$PACKAGE" || true
sleep 1
$ADB shell am start -n "$ACTIVITY" || true
sleep 4

# Supprimer les anciens screenshots
if [ -d "$SCREENSHOT_DIR" ]; then
    echo "Suppression des anciens screenshots..."
    rm -f "$SCREENSHOT_DIR"/*.png
else
    mkdir -p "$SCREENSHOT_DIR"
fi

count=0

while true; do
    echo ""
    read -rp "Prendre un screenshot ? (o/n) " answer
    case "$answer" in
        [oOyY]*)
            count=$((count + 1))
            filename="${count}.png"
            echo "  Capture en cours..."
            $ADB shell screencap -p "$DEVICE_TMP"
            $ADB pull "$DEVICE_TMP" "${SCREENSHOT_DIR}/${filename}" > /dev/null 2>&1
            $ADB shell rm "$DEVICE_TMP"
            echo "  Sauvegardé : ${SCREENSHOT_DIR}/${filename}"
            ;;
        [nN]*)
            echo ""
            break
            ;;
        *)
            echo "  Répondre o (oui) ou n (non)"
            ;;
    esac
done

if [ "$count" -eq 0 ]; then
    echo "Aucun screenshot pris."
    exit 0
fi

echo "=== $count screenshot(s) capturé(s) ==="
echo ""

# Mettre à jour le README
echo "Mise à jour du README..."

# Construire le bloc d'images
img_block='<p align="center">'
for i in $(seq 1 $count); do
    img_block+=$'\n'"  <img src=\"screenshots/${i}.png\" width=\"180\" />"
done
img_block+=$'\n''</p>'

# Remplacer le bloc entre <!-- SCREENSHOTS_START --> et <!-- SCREENSHOTS_END -->
# Si les marqueurs n'existent pas, remplacer le bloc <p align="center">...</p> après ## Screenshots
if grep -q '<!-- SCREENSHOTS_START -->' "$README"; then
    # Utiliser les marqueurs
    awk -v block="$img_block" '
        /<!-- SCREENSHOTS_START -->/ { print; print block; skip=1; next }
        /<!-- SCREENSHOTS_END -->/ { skip=0 }
        !skip { print }
    ' "$README" > "${README}.tmp"
    mv "${README}.tmp" "$README"
else
    # Remplacer le bloc <p align="center">...</p> après ## Screenshots
    # et ajouter les marqueurs pour la prochaine fois
    awk -v block="$img_block" '
        /^## Screenshots/ { print; getline; print; found=1; next }
        found && /^<p align="center">/ { skip=1; next }
        found && skip && /^<\/p>/ { skip=0; found=0; print "<!-- SCREENSHOTS_START -->"; print block; print "<!-- SCREENSHOTS_END -->"; next }
        found && skip { next }
        { print }
    ' "$README" > "${README}.tmp"
    mv "${README}.tmp" "$README"
fi

echo ""
echo "=== Terminé ! ==="
echo ""

# Restaurer la locale d'origine
echo "Restauration de la locale : $ORIGINAL_LOCALE"
$ADB shell cmd locale set-device-locale "$ORIGINAL_LOCALE" || true
sleep 2
$ADB shell am force-stop "$PACKAGE" || true
$ADB shell am start -n "$ACTIVITY" || true > /dev/null 2>&1

echo ""
echo "Fichiers :"
ls -1 "$SCREENSHOT_DIR"/*.png 2>/dev/null || echo "  (aucun)"
