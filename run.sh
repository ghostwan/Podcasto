#!/bin/bash
set -e

echo "=== Building Podcasto ==="
./gradlew assembleDebug

echo ""
echo "=== Installing on device ==="
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo ""
echo "=== Launching app ==="
adb shell am start -n com.music.podcasto/.MainActivity

echo ""
echo "=== Done! ==="
