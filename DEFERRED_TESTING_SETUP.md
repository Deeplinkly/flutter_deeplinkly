# Deferred Deep Link Testing Setup

## Summary

This setup enables testing of deferred deep linking functionality where a link (TEST_LINK) is clicked before app installation, and the deferred deep link data is delivered when the app launches for the first time.

## What Was Changed

### 1. Removed CI/CD Integration
- ✅ Removed `.github/workflows/test.yml`
- ✅ Removed `scripts/ci_test.sh`
- ✅ Removed `test/ci_test_suite.dart`

### 2. Created Example App
- ✅ `example/lib/main.dart` - App that displays deferred deep link data
- ✅ `example/android/` - Android configuration
- ✅ App shows deep link data on screen and logs to console with `DEEPLINKLY_TEST:` prefix

### 3. Added Deferred Deep Link Tests
- ✅ `test/deferred_deeplink_test.dart` - Flutter unit tests
- ✅ `android/src/test/kotlin/.../handlers/InstallReferrerHandlerTest.kt` - Android tests

### 4. Updated Firebase Test Lab Configuration
- ✅ `test/device_farm_config.yaml` - Updated for Samsung S24 Ultra only
- ✅ `scripts/run_device_farm_tests.sh` - Updated for deferred deep link testing

## Test Flow

```
1. TEST_LINK is clicked (before app installation)
   ↓
2. App gets installed (install referrer contains click_id)
   ↓
3. App launches for first time
   ↓
4. InstallReferrerHandler retrieves click_id from install referrer
   ↓
5. SDK resolves click_id via network call
   ↓
6. Deferred deep link params delivered to Flutter via deepLinkStream
   ↓
7. App displays data on screen + logs to console
```

## Setup Instructions

### 1. Configure API Key

```bash
./scripts/setup_deferred_test.sh YOUR_API_KEY
```

This will set the API key in `example/android/app/src/main/AndroidManifest.xml`.

### 2. Create TEST_LINK

Create a link using your Deeplinkly dashboard or API with identifier "TEST_LINK". Note the link URL.

### 3. Run on Firebase Test Lab

```bash
export DEEPLINKLY_API_KEY="your_api_key"
export TEST_LINK="https://your-deeplinkly-link.com/TEST_LINK"
export FIREBASE_PROJECT_ID="your-project-id"

./scripts/run_device_farm_tests.sh
```

## Device Configuration

- **Device**: Samsung Galaxy S24 Ultra (SM-S928B)
- **Android Version**: 34 (Android 14)
- **Test Type**: Instrumentation (or app-only for manual testing)

## What to Verify

After running the test, check:

1. **Firebase Test Lab Console**:
   - Screenshots showing app with deep link data
   - Console logs with `DEEPLINKLY_TEST:` prefix
   - Test results

2. **Console Logs** (look for):
   ```
   DEEPLINKLY_TEST: Deep link data received
   DEEPLINKLY_TEST: Data: {click_id: xxx, params: {...}}
   DEEPLINKLY_TEST: Install attribution: {...}
   ```

3. **App Display**:
   - Status shows "Deep link received!" or "Install attribution found"
   - Deep Link Data card shows deferred parameters
   - Log Messages section shows all events

## Files Created/Modified

### New Files
- `example/lib/main.dart`
- `example/android/app/src/main/AndroidManifest.xml`
- `example/android/app/build.gradle`
- `example/android/build.gradle`
- `example/android/settings.gradle`
- `example/android/app/src/main/kotlin/com/deeplinkly/example/MainActivity.kt`
- `example/android/app/src/main/res/values/styles.xml`
- `example/pubspec.yaml`
- `example/README.md`
- `test/deferred_deeplink_test.dart`
- `test/DEFERRED_DEEPLINK_TESTING.md`
- `scripts/setup_deferred_test.sh`

### Modified Files
- `test/device_farm_config.yaml` - Updated for Samsung S24 Ultra
- `scripts/run_device_farm_tests.sh` - Updated for deferred testing

### Removed Files
- `.github/workflows/test.yml`
- `scripts/ci_test.sh`
- `test/ci_test_suite.dart`

## Next Steps

1. **Set up API key**: Run `./scripts/setup_deferred_test.sh YOUR_API_KEY`
2. **Create TEST_LINK**: Use your dashboard to create a link
3. **Run test**: Execute `./scripts/run_device_farm_tests.sh`
4. **Verify results**: Check Firebase Test Lab console for screenshots and logs

## Troubleshooting

- **No data received**: Ensure TEST_LINK was clicked before installation
- **Install referrer empty**: Link must be clicked from Play Store or compatible source
- **Network errors**: Check API key and network connectivity
- **Data not displayed**: Check console logs for errors

