#!/bin/bash
set -e

# ============================================================
# Podcasto — Play Store Deployment Script
# Builds a release AAB (Android App Bundle) for Play Store upload
# ============================================================

KEYSTORE_FILE="podcasto-release.jks"
KEYSTORE_PROPS="keystore.properties"
AAB_OUTPUT="app/build/outputs/bundle/release/app-release.aab"
APK_OUTPUT="app/build/outputs/apk/release/app-release.apk"

echo "========================================="
echo " Podcasto — Play Store Build"
echo "========================================="
echo ""

# Step 1: Check/Create release keystore
if [ ! -f "$KEYSTORE_FILE" ]; then
    echo ">>> No release keystore found. Creating one..."
    echo ""
    echo "You'll be asked to set a keystore password and provide certificate info."
    echo ""

    keytool -genkeypair \
        -v \
        -keystore "$KEYSTORE_FILE" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -alias podcasto

    echo ""
    echo ">>> Keystore created: $KEYSTORE_FILE"
    echo ""
    echo "IMPORTANT: Save this keystore and its password securely!"
    echo "You cannot update your app on Play Store without it."
    echo ""

    # Create keystore.properties template
    if [ ! -f "$KEYSTORE_PROPS" ]; then
        echo ">>> Creating $KEYSTORE_PROPS (fill in your password)..."
        cat > "$KEYSTORE_PROPS" <<EOF
storePassword=YOUR_STORE_PASSWORD
keyPassword=YOUR_KEY_PASSWORD
keyAlias=podcasto
storeFile=../podcasto-release.jks
EOF
        echo ">>> Edit $KEYSTORE_PROPS with your actual passwords before building."
        echo ""
    fi
else
    echo ">>> Release keystore found: $KEYSTORE_FILE"
fi

# Step 2: Check keystore.properties
if [ ! -f "$KEYSTORE_PROPS" ]; then
    echo "ERROR: $KEYSTORE_PROPS not found."
    echo "Create it with: storePassword, keyPassword, keyAlias, storeFile"
    exit 1
fi

echo ""
echo ">>> Building release AAB (without Gemini key)..."
echo ""

# Step 3: Build the AAB (no GEMINI_API_KEY — users configure it in-app)
./gradlew bundleRelease \
    -PGEMINI_API_KEY=""

echo ""
echo "========================================="
echo " Build complete!"
echo "========================================="
echo ""

if [ -f "$AAB_OUTPUT" ]; then
    AAB_SIZE=$(du -h "$AAB_OUTPUT" | cut -f1)
    echo "  AAB: $AAB_OUTPUT ($AAB_SIZE)"
    echo ""
    echo "Upload this file to Google Play Console:"
    echo "  https://play.google.com/console"
else
    echo "WARNING: AAB not found at $AAB_OUTPUT"
    echo ""
    echo "Building APK as fallback..."
    ./gradlew assembleRelease -PGEMINI_API_KEY=""
    if [ -f "$APK_OUTPUT" ]; then
        APK_SIZE=$(du -h "$APK_OUTPUT" | cut -f1)
        echo "  APK: $APK_OUTPUT ($APK_SIZE)"
    fi
fi
