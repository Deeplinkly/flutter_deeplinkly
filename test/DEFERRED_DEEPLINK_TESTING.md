# Deferred Deep Link Testing Guide

## Overview

This document describes how to test deferred deep linking functionality where a link (TEST_LINK) is clicked before app installation, and the deferred deep link data is delivered when the app launches for the first time.

## Test Flow

```
1. User clicks TEST_LINK (app not installed)
   ↓
2. App gets installed (install referrer contains click_id)
   ↓
3. App launches for first time
   ↓
4. InstallReferrerHandler retrieves click_id
   ↓
5. SDK resolves click_id via network
   ↓
6. Deferred deep link params delivered to Flutter
   ↓
7. App displays data on screen + logs to console
```

## Test Files

### Flutter Tests
- `test/deferred_deeplink_test.dart` - Unit tests for deferred deep link scenarios

### Android Tests
- `android/src/test/kotlin/.../handlers/InstallReferrerHandlerTest.kt` - Install referrer handler tests

### Example App
- `example/lib/main.dart` - Example app that displays deferred deep link data
- `example/android/` - Android configuration for example app

## Firebase Test Lab Setup

### Device Configuration
- **Device**: Samsung Galaxy S24 Ultra (SM-S928B)
- **Android Version**: 34 (Android 14)
- **Configuration**: `test/device_farm_config.yaml`

### Test Script
- **Script**: `scripts/run_device_farm_tests.sh`
- **Usage**: 
  ```bash
  export DEEPLINKLY_API_KEY="your_key"
  export TEST_LINK="https://your-link.com/TEST_LINK"
  export FIREBASE_PROJECT_ID="your_project"
  ./scripts/run_device_farm_tests.sh
  ```

## Test Scenarios

### 1. Basic Deferred Deep Link
- TEST_LINK clicked → App installed → Deferred params delivered
- Verify: Data appears on screen and in console logs

### 2. Deferred Deep Link with UTM Parameters
- TEST_LINK with UTM params → App installed → UTM params preserved
- Verify: UTM parameters are present in delivered data

### 3. Install Attribution
- TEST_LINK clicked → App installed → Attribution saved
- Verify: `getInstallAttribution()` returns correct data

### 4. Network Resolution
- Install referrer contains click_id → SDK resolves via network
- Verify: Resolved params match original link content

## Expected Console Logs

When deferred deep link is successfully delivered, you should see:

```
DEEPLINKLY_TEST: Deep link data received
DEEPLINKLY_TEST: Data: {click_id: xxx, params: {...}}
DEEPLINKLY_TEST: Install attribution: {source: install_referrer, click_id: xxx}
```

## Verification Checklist

- [ ] TEST_LINK was clicked before app installation
- [ ] App installed successfully
- [ ] App launched and displayed deferred deep link data
- [ ] Console logs show `DEEPLINKLY_TEST:` messages
- [ ] Install attribution contains correct click_id
- [ ] Deferred params match original link content
- [ ] Data is displayed on screen correctly

## Troubleshooting

### No Data Received
- Verify TEST_LINK was clicked before installation
- Check install referrer is available (may not work in all test environments)
- Verify API key is correct

### Network Resolution Fails
- Check API key is valid
- Verify network connectivity in test environment
- Check backend is responding correctly

### Data Not Displayed
- Check console logs for errors
- Verify FlutterDeeplinkly.init() was called
- Check deepLinkStream listener is set up correctly

