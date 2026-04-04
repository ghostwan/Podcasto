#!/usr/bin/env bash
set -uo pipefail

ADB="${ANDROID_HOME}/platform-tools/adb"
PACKAGE="com.ghostwan.podcasto"
ACTIVITY="${PACKAGE}/.MainActivity"
SCREENSHOT_DIR="screenshots"
DEVICE_TMP="/sdcard/podcasto_screenshot.png"
README="README.md"

# --- Fonction : mettre à jour le README depuis le dossier screenshots ---
update_readme_from_dir() {
    local dir="$1"
    local readme="$2"

    # Lister les PNG triés par nom
    local files=()
    while IFS= read -r f; do
        files+=("$(basename "$f")")
    done < <(find "$dir" -maxdepth 1 -name '*.png' | sort)

    if [ ${#files[@]} -eq 0 ]; then
        echo "Aucun screenshot trouvé dans $dir"
        return 1
    fi

    echo "Screenshots trouvés : ${#files[@]}"
    for f in "${files[@]}"; do echo "  - $f"; done

    # Construire le bloc d'images
    local img_block='<p align="center">'
    for f in "${files[@]}"; do
        local alt_name
        alt_name=$(echo "$f" | sed 's/^[0-9]*_//; s/\.png$//' | sed 's/_/ /g')
        alt_name="$(echo "${alt_name:0:1}" | tr '[:lower:]' '[:upper:]')${alt_name:1}"
        img_block+=$'\n'"  <img src=\"screenshots/${f}\" width=\"180\" alt=\"${alt_name}\" />"
    done
    img_block+=$'\n''</p>'

    if grep -q '<!-- SCREENSHOTS_START -->' "$readme"; then
        # Écrire le bloc dans un fichier temporaire pour éviter les problèmes awk avec les newlines
        local block_file
        block_file=$(mktemp)
        echo "$img_block" > "$block_file"

        awk -v blockfile="$block_file" '
            /<!-- SCREENSHOTS_START -->/ { print; while ((getline line < blockfile) > 0) print line; close(blockfile); skip=1; next }
            /<!-- SCREENSHOTS_END -->/ { skip=0; print; next }
            !skip { print }
        ' "$readme" > "${readme}.tmp"
        rm -f "$block_file"
        mv "${readme}.tmp" "$readme"
        echo "README mis à jour avec ${#files[@]} screenshot(s)."
    else
        echo "ATTENTION: Marqueurs SCREENSHOTS non trouvés dans le README."
        echo "Ajoutez les marqueurs:"
        echo "  <!-- SCREENSHOTS_START -->"
        echo "  <!-- SCREENSHOTS_END -->"
        return 1
    fi
}

# --- Option --update-readme : mise à jour depuis le dossier existant ---
if [ "${1:-}" = "--update-readme" ]; then
    echo "=== Mise à jour du README depuis $SCREENSHOT_DIR ==="
    update_readme_from_dir "$SCREENSHOT_DIR" "$README"
    exit $?
fi

# --- Mode capture interactive ---
echo "=== Podcasto — Capture de screenshots interactive ==="
echo ""

# Sauvegarder la locale actuelle (trim whitespace/CR)
ORIGINAL_LOCALE=$($ADB shell getprop persist.sys.locale | tr -d '[:space:]')
if [ -z "$ORIGINAL_LOCALE" ]; then
    ORIGINAL_LOCALE=$($ADB shell getprop ro.product.locale | tr -d '[:space:]')
fi
if [ -z "$ORIGINAL_LOCALE" ]; then
    ORIGINAL_LOCALE="fr-FR"
fi
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

# Créer le dossier screenshots si nécessaire
mkdir -p "$SCREENSHOT_DIR"

# Liste des screenshots capturés (nom sans extension)
captured_files=()

while true; do
    echo ""
    read -rp "Prendre un screenshot ? (o/n) " answer
    case "$answer" in
        [oOyY]*)
            # Demander un nom descriptif
            read -rp "  Nom du screenshot (ex: library, player, playlist) : " sname
            if [ -z "$sname" ]; then
                echo "  Nom vide, screenshot ignoré."
                continue
            fi
            # Préfixer avec numéro pour garder l'ordre
            num=$(printf "%02d" $((${#captured_files[@]} + 1)))
            filename="${num}_${sname}.png"
            echo "  Capture en cours..."
            $ADB shell screencap -p "$DEVICE_TMP"
            $ADB pull "$DEVICE_TMP" "${SCREENSHOT_DIR}/${filename}" > /dev/null 2>&1
            $ADB shell rm "$DEVICE_TMP" || true
            echo "  Sauvegardé : ${SCREENSHOT_DIR}/${filename}"
            captured_files+=("$filename")
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

count=${#captured_files[@]}

if [ "$count" -eq 0 ]; then
    echo "Aucun screenshot pris."
else
    echo "=== $count screenshot(s) capturé(s) ==="
    echo ""

    # Demander si on veut mettre à jour le README
    read -rp "Mettre à jour le README avec ces screenshots ? (o/n) " update_readme
    case "$update_readme" in
        [oOyY]*)
            update_readme_from_dir "$SCREENSHOT_DIR" "$README"
            ;;
        *)
            echo "README non modifié."
            ;;
    esac
fi

echo ""

# Restaurer la locale d'origine
echo "Restauration de la locale : $ORIGINAL_LOCALE"
$ADB shell cmd locale set-device-locale "$ORIGINAL_LOCALE" || true
sleep 2
$ADB shell am force-stop "$PACKAGE" || true
sleep 1
$ADB shell am start -n "$ACTIVITY" > /dev/null 2>&1 || true

echo ""
echo "=== Terminé ! ==="
echo ""
echo "Fichiers :"
ls -1 "$SCREENSHOT_DIR"/*.png 2>/dev/null || echo "  (aucun)"
