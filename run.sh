#!/bin/bash
set -e

FLAVOR="${1:-full}"
BUILD_TYPE="${2:-release}"

if [[ "$FLAVOR" != "full" && "$FLAVOR" != "store" ]]; then
    echo "Usage: ./run.sh [full|store] [debug|release]"
    echo "  Default: full release"
    exit 1
fi

if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    echo "Usage: ./run.sh [full|store] [debug|release]"
    echo "  Default: full release"
    exit 1
fi

# Capitalize first letter for Gradle task
FLAVOR_CAP="$(tr '[:lower:]' '[:upper:]' <<< ${FLAVOR:0:1})${FLAVOR:1}"
BUILD_TYPE_CAP="$(tr '[:lower:]' '[:upper:]' <<< ${BUILD_TYPE:0:1})${BUILD_TYPE:1}"

TASK="assemble${FLAVOR_CAP}${BUILD_TYPE_CAP}"
APK_PATH="app/build/outputs/apk/$FLAVOR/$BUILD_TYPE/app-${FLAVOR}-${BUILD_TYPE}.apk"

echo "=== Building Podcasto ($FLAVOR $BUILD_TYPE) ==="
./gradlew "$TASK"

echo ""
echo "=== Installing on device ==="
adb install -r "$APK_PATH"

echo ""
echo "=== Launching app ==="
adb shell am start -n com.ghostwan.podcasto/.MainActivity

echo ""
echo "=== Done! ==="
