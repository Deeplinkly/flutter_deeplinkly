# How to Run Tests - Physical Devices & Firebase Test Lab

This guide covers testing deferred deep linking on both physical devices and Firebase Test Lab.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Testing on Physical Device](#testing-on-physical-device)
3. [Testing on Firebase Test Lab](#testing-on-firebase-test-lab)
4. [Verifying Test Results](#verifying-test-results)
5. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Required Setup

1. **API Key**: Get your Deeplinkly API key from your dashboard
2. **TEST_LINK**: Create a link with identifier "TEST_LINK" using your Deeplinkly dashboard
3. **Android Device**: Physical Android device with USB debugging enabled (for physical testing)
4. **Firebase Project**: Firebase project with Test Lab enabled (for cloud testing)

### Install Dependencies

```bash
# Navigate to example app
cd example

# Get Flutter dependencies
flutter pub get

# Set up API key
../scripts/setup_deferred_test.sh YOUR_API_KEY
```

---

## Testing on Physical Device

### Step 1: Connect Your Device

1. **Enable Developer Options** on your Android device:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   - Go back to Settings → Developer Options
   - Enable "USB Debugging"

2. **Connect device via USB**:
   ```bash
   # Verify device is connected
   adb devices
   ```
   You should see your device listed.

### Step 2: Set Up API Key

```bash
# From project root
./scripts/setup_deferred_test.sh YOUR_API_KEY
```

This sets the API key in `example/android/app/src/main/AndroidManifest.xml`.

### Step 3: Create TEST_LINK

1. Go to your Deeplinkly dashboard
2. Create a new link with:
   - **Identifier**: `TEST_LINK`
   - **Title**: "Test Deferred Link"
   - **Any custom parameters** you want to test
3. Copy the link URL (e.g., `https://your-domain.com/TEST_LINK`)

### Step 4: Test Deferred Deep Link Flow

#### Option A: Full Deferred Deep Link Test (Recommended)

1. **Uninstall the app** if it's already installed:
   ```bash
   adb uninstall com.deeplinkly.example
   ```

2. **Click the TEST_LINK** on your device:
   - Open the link in a browser on your device
   - Or use ADB to open it:
     ```bash
     adb shell am start -a android.intent.action.VIEW -d "YOUR_TEST_LINK_URL"
     ```
   - The link should open in browser (app not installed yet)

3. **Install the app** from the link or manually:
   ```bash
   # Build and install
   cd example
   flutter install
   ```
   
   Or if you have an APK:
   ```bash
   adb install -r path/to/app-debug.apk
   ```

4. **Launch the app**:
   ```bash
   adb shell am start -n com.deeplinkly.example/.MainActivity
   ```

5. **Check the app**:
   - App should display "Deep link received!" or "Install attribution found"
   - Deep Link Data card should show the deferred parameters
   - Check logcat for console logs:
     ```bash
     adb logcat | grep DEEPLINKLY_TEST
     ```

#### Option B: Simulate Install Referrer (Quick Test)

If you want to test without clicking the link first:

```bash
# Simulate install referrer with click_id
adb shell am broadcast -a com.android.vending.INSTALL_REFERRER \
  -n com.deeplinkly.example/com.google.android.gms.measurement.AppMeasurementInstallReferrerReceiver \
  --es "referrer" "click_id=YOUR_CLICK_ID&utm_source=test&utm_medium=deeplink"
```

Then launch the app and check for deferred data.

### Step 5: Verify Results

**In the App:**
- Status should show "Deep link received!" or "Install attribution found"
- Deep Link Data card should display:
  - `click_id`
  - `params` (if resolved)
  - UTM parameters
  - Any custom data

**In Logcat:**
```bash
# View all Deeplinkly test logs
adb logcat | grep DEEPLINKLY_TEST

# Expected output:
# DEEPLINKLY_TEST: Deep link data received
# DEEPLINKLY_TEST: Data: {click_id: xxx, params: {...}}
# DEEPLINKLY_TEST: Install attribution: {...}
```

**Check Install Attribution:**
The app also calls `getInstallAttribution()` which should return the deferred deep link data.

---

## Testing on Firebase Test Lab

### Step 1: Set Up Firebase

1. **Install Google Cloud SDK** (if not already installed):
   ```bash
   # On macOS
   brew install google-cloud-sdk
   
   # On Linux
   # Follow: https://cloud.google.com/sdk/docs/install
   ```

2. **Authenticate**:
   ```bash
   gcloud auth login
   gcloud auth application-default login
   ```

3. **Set your Firebase project**:
   ```bash
   gcloud config set project YOUR_FIREBASE_PROJECT_ID
   ```

### Step 2: Prepare Test Environment

```bash
# Set environment variables
export DEEPLINKLY_API_KEY="your_api_key"
export TEST_LINK="https://your-deeplinkly-link.com/TEST_LINK"
export FIREBASE_PROJECT_ID="your-firebase-project-id"
```

### Step 3: Build the App

```bash
# Navigate to example app
cd example

# Build debug APK
flutter build apk --debug

# The APK will be at:
# example/build/app/outputs/flutter-apk/app-debug.apk
```

### Step 4: Run on Firebase Test Lab

```bash
# From project root
./scripts/run_device_farm_tests.sh
```

This script will:
1. Build the app APK
2. Upload to Firebase Test Lab
3. Run on Samsung S24 Ultra (SM-S928B, Android 14)
4. Wait for results

### Step 5: Monitor Test Execution

**View test status:**
```bash
# List recent test executions
gcloud firebase test android results list

# View specific test
gcloud firebase test android results describe TEST_ID
```

**Or use Firebase Console:**
1. Go to [Firebase Console](https://console.firebase.google.com)
2. Select your project
3. Go to Test Lab → Test Results
4. View your test execution

### Step 6: View Results

**In Firebase Console:**
- **Screenshots**: See app screenshots at different stages
- **Videos**: Watch the test execution (if enabled)
- **Logs**: View console logs including `DEEPLINKLY_TEST:` messages
- **Performance**: Check app performance metrics

**Download Results:**
```bash
# Download test results
gsutil -m cp -r gs://YOUR_PROJECT_ID-test-results/deeplinkly-deferred-tests/* ./test-results/
```

**View Logs:**
```bash
# Extract and view logs
unzip test-results/*/logcat.txt
grep DEEPLINKLY_TEST logcat.txt
```

---

## Verifying Test Results

### Success Criteria

✅ **Physical Device:**
- App displays deferred deep link data on screen
- Console logs show `DEEPLINKLY_TEST:` messages
- Install attribution contains correct `click_id`
- Deferred params match original link content

✅ **Firebase Test Lab:**
- Test completes without crashes
- Screenshots show app with data displayed
- Console logs contain `DEEPLINKLY_TEST:` messages
- Test status is "Passed"

### What to Look For

**In App Display:**
- Status: "Deep link received!" or "Install attribution found"
- Deep Link Data card shows:
  - `click_id`: The click ID from TEST_LINK
  - `params`: Resolved parameters (if network call succeeded)
  - UTM parameters: `utm_source`, `utm_medium`, etc.
  - Custom data: Any custom parameters from the link

**In Console Logs:**
```
DEEPLINKLY_TEST: Deep link data received
DEEPLINKLY_TEST: Data: {click_id: xxx, params: {...}}
DEEPLINKLY_TEST: Install attribution: {source: install_referrer, click_id: xxx}
```

**In Install Attribution:**
```dart
final attribution = await FlutterDeeplinkly.getInstallAttribution();
// Should contain: source, click_id, utm_source, etc.
```

---

## Troubleshooting

### Physical Device Issues

**Device not detected:**
```bash
# Check ADB connection
adb devices

# Restart ADB
adb kill-server
adb start-server
```

**App not installing:**
```bash
# Uninstall existing app first
adb uninstall com.deeplinkly.example

# Install fresh
cd example
flutter install
```

**No deferred data received:**
- Verify TEST_LINK was clicked **before** app installation
- Check install referrer is available:
  ```bash
  adb shell dumpsys package com.deeplinkly.example | grep referrer
  ```
- Verify API key is correct in AndroidManifest.xml
- Check network connectivity

**Install referrer empty:**
- Install referrer only works when app is installed from Play Store or compatible source
- For testing, use ADB to simulate:
  ```bash
  adb shell am broadcast -a com.android.vending.INSTALL_REFERRER \
    -n com.deeplinkly.example/com.google.android.gms.measurement.AppMeasurementInstallReferrerReceiver \
    --es "referrer" "click_id=test123"
  ```

### Firebase Test Lab Issues

**Authentication errors:**
```bash
# Re-authenticate
gcloud auth login
gcloud auth application-default login
```

**Build failures:**
```bash
# Clean and rebuild
cd example
flutter clean
flutter pub get
flutter build apk --debug
```

**Test timeout:**
- Increase timeout in `scripts/run_device_farm_tests.sh`
- Check device availability in Firebase Test Lab

**No logs in results:**
- Ensure app is logging with `DEEPLINKLY_TEST:` prefix
- Check Firebase Test Lab console for full logs
- Download test artifacts to view complete logs

**Device not available:**
- Samsung S24 Ultra may not always be available
- Check [Firebase Test Lab device availability](https://firebase.google.com/docs/test-lab/android/available-testing-devices)
- Update device in `test/device_farm_config.yaml` if needed

### General Issues

**API Key not working:**
- Verify API key is set correctly in AndroidManifest.xml
- Check API key is valid in your Deeplinkly dashboard
- Ensure API key has proper permissions

**Network errors:**
- Check internet connectivity
- Verify backend is responding
- Check API endpoint is correct

**Data not displaying:**
- Check console logs for errors
- Verify `FlutterDeeplinkly.init()` was called
- Ensure `deepLinkStream` listener is set up
- Check app lifecycle state

---

## Quick Reference

### Physical Device Testing
```bash
# Setup
./scripts/setup_deferred_test.sh YOUR_API_KEY

# Build and install
cd example
flutter install

# View logs
adb logcat | grep DEEPLINKLY_TEST

# Simulate install referrer
adb shell am broadcast -a com.android.vending.INSTALL_REFERRER \
  -n com.deeplinkly.example/com.google.android.gms.measurement.AppMeasurementInstallReferrerReceiver \
  --es "referrer" "click_id=test123"
```

### Firebase Test Lab
```bash
# Setup
export DEEPLINKLY_API_KEY="your_key"
export TEST_LINK="https://your-link.com/TEST_LINK"
export FIREBASE_PROJECT_ID="your_project"

# Run
./scripts/run_device_farm_tests.sh

# View results
gcloud firebase test android results list
```

---

## Next Steps

After successful testing:

1. **Review Results**: Check both app display and console logs
2. **Document Issues**: Note any device-specific problems
3. **Iterate**: Update test scenarios based on results
4. **Automate**: Set up automated testing pipeline if needed

For more details, see:
- `test/DEFERRED_DEEPLINK_TESTING.md` - Detailed testing guide
- `example/README.md` - Example app documentation
- `DEFERRED_TESTING_SETUP.md` - Setup summary

