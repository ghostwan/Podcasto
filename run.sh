#!/bin/bash
set -e

BUILD_TYPE="${1:-release}"

if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    echo "Usage: ./run.sh [debug|release]"
    echo "  Default: release"
    exit 1
fi

if [ "$BUILD_TYPE" = "release" ]; then
    TASK="assembleRelease"
else
    TASK="assembleDebug"
fi
APK_PATH="app/build/outputs/apk/$BUILD_TYPE/app-${BUILD_TYPE}.apk"

echo "=== Building Podcasto ($BUILD_TYPE) ==="
./gradlew "$TASK"

echo ""
echo "=== Installing on device ==="
adb install -r "$APK_PATH"

echo ""
echo "=== Launching app ==="
adb shell am start -n com.music.podcasto/.MainActivity

echo ""
echo "=== Done! ==="
