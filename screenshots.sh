#!/usr/bin/env bash
set -euo pipefail

ADB="${ANDROID_HOME}/platform-tools/adb"
PACKAGE="com.music.podcasto"
ACTIVITY="${PACKAGE}/.MainActivity"
SCREENSHOT_DIR="screenshots"
DEVICE_TMP="/sdcard/podcasto_screenshot.png"

# Pixel 7: 1080x2400, density 420
# Bottom nav: Library [0,2064][346,2274] | Playlist [367,2064][713,2274] | New [734,2064][1080,2274]
# MiniPlayer area: [0,1896][1080,2064]
# FAB Discover: [891,1707][1038,1854]

screenshot() {
    local name="$1"
    local desc="$2"
    echo "  Capturing: $name ($desc)"
    $ADB shell screencap -p "$DEVICE_TMP"
    $ADB pull "$DEVICE_TMP" "${SCREENSHOT_DIR}/${name}.png" > /dev/null 2>&1
    $ADB shell rm "$DEVICE_TMP"
    echo "  Saved: ${SCREENSHOT_DIR}/${name}.png"
}

tap() {
    $ADB shell input tap "$1" "$2"
}

back() {
    $ADB shell input keyevent KEYCODE_BACK
}

swipe_up() {
    $ADB shell input swipe 540 1500 540 800 300
}

echo "=== Podcasto Automatic Screenshot Tool ==="
echo ""

# --- Save current locale ---
ORIGINAL_LOCALE=$($ADB shell cmd locale get-device-locale)
echo "Current locale: $ORIGINAL_LOCALE"

# --- Switch to English ---
echo "Switching device to English (en-US)..."
$ADB shell cmd locale set-device-locale en-US
sleep 3

# --- Restart app fresh ---
echo "Restarting app..."
$ADB shell am force-stop "$PACKAGE"
sleep 1
$ADB shell am start -n "$ACTIVITY" > /dev/null 2>&1
sleep 4

# --- Create output directory ---
mkdir -p "$SCREENSHOT_DIR"

echo ""
echo "=== Taking screenshots ==="
echo ""

# 1. Library (subscriptions)
screenshot "01_library" "Library / Subscriptions"
sleep 1

# 2. Tap first podcast → Podcast Detail
echo "  Navigating to Podcast Detail..."
tap 540 620
sleep 3
screenshot "02_podcast_detail" "Podcast Detail"
sleep 1

# 3. Tap first episode → Episode Detail
# Episodes start below the header. Get position from UI dump after loading.
# Typically episodes are around y=800+ in podcast detail. Use a safe tap.
echo "  Navigating to Episode Detail..."
# Scroll down a bit first to make sure episodes are visible
# Episodes usually start around y=900 in podcast detail
tap 540 1000
sleep 3
screenshot "03_episode_detail" "Episode Detail"
sleep 1

# 4. Back to Podcast Detail, then back to Library
echo "  Going back to Library..."
back
sleep 1
back
sleep 2

# 5. Tap MiniPlayer → Player fullscreen
echo "  Opening Player..."
tap 540 1980
sleep 2
screenshot "04_player" "Player"
sleep 1

# 6. Back to Library
echo "  Going back to Library..."
back
sleep 1

# 7. Playlist tab
echo "  Navigating to Playlist..."
tap 540 2169
sleep 2
screenshot "05_playlist" "Playlist"
sleep 1

# 8. New Episodes tab
echo "  Navigating to New Episodes..."
tap 907 2169
sleep 2
screenshot "06_new_episodes" "New Episodes"
sleep 1

# 9. Go back to Library, tap FAB → Discover
echo "  Navigating to Discover..."
tap 173 2169
sleep 1
tap 965 1780
sleep 2
screenshot "07_discover" "Discover"
sleep 1

# --- Restore original locale ---
echo ""
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
