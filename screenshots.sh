#!/usr/bin/env bash
set -euo pipefail

ADB="${ANDROID_HOME}/platform-tools/adb"
PACKAGE="com.music.podcasto"
ACTIVITY="${PACKAGE}/.MainActivity"
SCREENSHOT_DIR="screenshots"
DEVICE_TMP="/sdcard/podcasto_screenshot.png"

# Screenshots to capture — name and instructions
SCREENS=(
    "01_library:Library screen (subscriptions list)"
    "02_discover:Discover screen (search with country filter)"
    "03_podcast_detail:Podcast detail (episode list)"
    "04_episode_detail:Episode detail (actions + description)"
    "05_player:Player (full screen playback)"
    "06_playlist:Playlist (queued episodes)"
)

echo "=== Podcasto Screenshot Tool ==="
echo ""

# --- Save current locale ---
ORIGINAL_LOCALE=$($ADB shell cmd locale get-device-locale)
echo "Current locale: $ORIGINAL_LOCALE"

# --- Switch to English ---
echo "Switching device to English (en-US)..."
$ADB shell cmd locale set-device-locale en-US
sleep 2

# --- Restart app ---
echo "Restarting app..."
$ADB shell am force-stop "$PACKAGE"
sleep 0.5
$ADB shell am start -n "$ACTIVITY" > /dev/null 2>&1
sleep 2

# --- Create output directory ---
mkdir -p "$SCREENSHOT_DIR"

echo ""
echo "The device is now in English and the app is open."
echo "For each screenshot, navigate to the correct screen on the device,"
echo "then press ENTER here to capture."
echo ""

for entry in "${SCREENS[@]}"; do
    NAME="${entry%%:*}"
    DESC="${entry##*:}"
    
    echo "--- [$NAME] $DESC ---"
    read -r -p "  Navigate to this screen, then press ENTER to capture... "
    
    # Capture
    $ADB shell screencap -p "$DEVICE_TMP"
    $ADB pull "$DEVICE_TMP" "${SCREENSHOT_DIR}/${NAME}.png" > /dev/null 2>&1
    $ADB shell rm "$DEVICE_TMP"
    
    echo "  Saved: ${SCREENSHOT_DIR}/${NAME}.png"
    echo ""
done

# --- Restore original locale ---
echo "Restoring original locale: $ORIGINAL_LOCALE"
$ADB shell cmd locale set-device-locale "$ORIGINAL_LOCALE"
sleep 2

# --- Restart app with original locale ---
$ADB shell am force-stop "$PACKAGE"
$ADB shell am start -n "$ACTIVITY" > /dev/null 2>&1

echo ""
echo "=== Done! Screenshots saved in ${SCREENSHOT_DIR}/ ==="
echo ""
echo "Files:"
ls -1 "$SCREENSHOT_DIR"/*.png 2>/dev/null || echo "  (none)"
