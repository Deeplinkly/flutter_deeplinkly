#!/bin/bash

# Script to run deferred deep link tests on Firebase Test Lab
# Tests deferred deep linking: TEST_LINK clicked -> app installed -> deferred params delivered
# Device: Samsung S24 Ultra only

set -e

PROJECT_ID=${FIREBASE_PROJECT_ID:-"your-project-id"}
API_KEY=${DEEPLINKLY_API_KEY:-""}
TEST_LINK=${TEST_LINK:-""}

if [ -z "$API_KEY" ]; then
    echo "Error: DEEPLINKLY_API_KEY environment variable is not set"
    exit 1
fi

if [ -z "$TEST_LINK" ]; then
    echo "Warning: TEST_LINK environment variable is not set"
    echo "Deferred deep link test requires a link to be clicked before app installation"
fi

echo "Running deferred deep link tests on Samsung S24 Ultra"
echo "Project ID: $PROJECT_ID"
echo "Test Link: $TEST_LINK"

# Build the example app APK
echo "Building example app APK..."
cd example/android
./gradlew assembleDebug

# Build test APK (if instrumentation tests exist)
if [ -d "app/src/androidTest" ]; then
    echo "Building test APK..."
    ./gradlew assembleDebugAndroidTest
    TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
else
    echo "No instrumentation tests found, using app APK only"
    TEST_APK=""
fi

APP_APK="app/build/outputs/apk/debug/app-debug.apk"

# Test on Samsung S24 Ultra
DEVICE="model=SM-S928B,version=34"  # Samsung Galaxy S24 Ultra, Android 14

echo "Testing deferred deep link on: $DEVICE"
echo ""
echo "Test Scenario:"
echo "1. TEST_LINK ($TEST_LINK) should be clicked before app installation"
echo "2. App will be installed on device"
echo "3. On first launch, InstallReferrerHandler will retrieve click_id"
echo "4. SDK will resolve click_id and deliver deferred deep link params"
echo "5. App will display data on screen and log to console"
echo ""

if [ -n "$TEST_APK" ]; then
    # Run instrumentation test
    gcloud firebase test android run \
        --type instrumentation \
        --app "$APP_APK" \
        --test "$TEST_APK" \
        --device "$DEVICE" \
        --timeout 30m \
        --project "$PROJECT_ID" \
        --environment-variables API_KEY="$API_KEY",TEST_LINK="$TEST_LINK" \
        --results-bucket gs://"$PROJECT_ID"-test-results \
        --results-dir "deeplinkly-deferred-tests/$(date +%Y%m%d-%H%M%S)/s24-ultra"
else
    # Run app only (manual testing)
    echo "Running app-only test (no instrumentation tests)"
    gcloud firebase test android run \
        --type app \
        --app "$APP_APK" \
        --device "$DEVICE" \
        --timeout 30m \
        --project "$PROJECT_ID" \
        --environment-variables API_KEY="$API_KEY",TEST_LINK="$TEST_LINK" \
        --results-bucket gs://"$PROJECT_ID"-test-results \
        --results-dir "deeplinkly-deferred-tests/$(date +%Y%m%d-%H%M%S)/s24-ultra"
fi

if [ $? -eq 0 ]; then
    echo ""
    echo "Test completed successfully!"
    echo "Check Firebase Test Lab console for results and screenshots"
    echo "Look for console logs containing 'DEEPLINKLY_TEST:' to verify deferred deep link data"
else
    echo "Test failed on device: $DEVICE"
    exit 1
fi

