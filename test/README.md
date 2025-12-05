# Deeplinkly SDK Testing Framework

This directory contains comprehensive tests for the Deeplinkly Flutter SDK, designed to catch device-specific issues like the A30 vs A31 problem before release.

## Test Structure

### Unit Tests
- `flutter_deeplinkly_test.dart` - Flutter/Dart layer unit tests
- `android/src/test/kotlin/.../` - Android native layer unit tests

### Integration Tests
- `integration_test.dart` - Flutter-Android bridge integration tests
- `lifecycle_integration_test.dart` - App lifecycle scenario tests
- `error_scenarios_test.dart` - Error path and edge case tests

### Device-Specific Tests
- `device_scenarios_test.dart` - Device-specific behavior tests
- `android_version_test.dart` - Android version compatibility tests

### Test Utilities
- `helpers/mock_method_channel.dart` - Mock method channel for testing
- `helpers/lifecycle_simulator.dart` - Lifecycle state simulator
- `helpers/network_mock.dart` - Network response mocking

### Test Fixtures
- `fixtures/deeplink_samples.json` - Sample deep link data
- `fixtures/network_responses.json` - Mock network responses
- `fixtures/attribution_data.json` - Attribution test data

## Running Tests

### Run All Tests
```bash
flutter test
cd android && ./gradlew testDebugUnitTest
```

### Run Specific Test Suites
```bash
# Flutter unit tests
flutter test test/flutter_deeplinkly_test.dart

# Integration tests
flutter test test/integration_test.dart

# Lifecycle tests
flutter test test/lifecycle_integration_test.dart

# Error scenario tests
flutter test test/error_scenarios_test.dart

# Device-specific tests
flutter test test/device_scenarios_test.dart
```

### Run Android Unit Tests
```bash
cd android
./gradlew testDebugUnitTest
```

### Run with Coverage
```bash
flutter test --coverage
genhtml coverage/lcov.info -o coverage/html
```

## Device Farm Testing

### Firebase Test Lab - Deferred Deep Link Testing
Run deferred deep link tests on Samsung S24 Ultra:

```bash
export DEEPLINKLY_API_KEY="your_api_key"
export TEST_LINK="https://your-deeplinkly-link.com/TEST_LINK"
export FIREBASE_PROJECT_ID="your-project-id"

./scripts/run_device_farm_tests.sh
```

**Test Scenario:**
1. TEST_LINK is clicked (before app installation)
2. App is installed on Samsung S24 Ultra
3. On first launch, deferred deep link data is delivered
4. App displays data on screen and logs to console

**Device:** Samsung Galaxy S24 Ultra (SM-S928B), Android 14

See `DEFERRED_DEEPLINK_TESTING.md` for detailed testing guide.

### Configuration
Edit `test/device_farm_config.yaml` to customize device matrix.

## CI/CD Integration

Tests run automatically on:
- Every pull request
- Every push to main/develop branches
- On releases (device farm tests)

See `.github/workflows/test.yml` for CI configuration.

## Test Coverage

Current test coverage targets:
- **Critical Issues:** 100% (5/5)
- **High Priority Issues:** 100% (5/5)
- **Medium Priority Issues:** 100% (3/3)

See `AUDIT_COVERAGE.md` for detailed mapping of audit issues to test cases.

## Writing New Tests

### Flutter Tests
1. Use `TestWidgetsFlutterBinding.ensureInitialized()` in `setUp`
2. Use `FlutterDeeplinkly.init()` before testing
3. Subscribe to `deepLinkStream` to test deep link delivery
4. Use helpers from `helpers/` directory

### Android Tests
1. Use `@Before` to set up mocks and context
2. Use `TestIntentBuilder` to create test intents
3. Use `ApplicationProvider.getApplicationContext()` for context
4. Wait for async operations with appropriate delays

## Test Scenarios Covered

### Initialization
- ✅ Initialization order enforcement
- ✅ Multiple initialization calls
- ✅ Callback registration before/after init

### Deep Link Handling
- ✅ Deep link received before Flutter init
- ✅ Deep link received during app startup
- ✅ Deep link received when app is in background
- ✅ Multiple deep links in rapid succession
- ✅ Concurrent deep links

### Error Scenarios
- ✅ Network failure during resolution
- ✅ Activity null reference
- ✅ Method channel failures
- ✅ Invalid deep link data
- ✅ Queue overflow

### Lifecycle
- ✅ Cold start deep links
- ✅ Warm start deep links
- ✅ Background to foreground transitions
- ✅ Multiple lifecycle transitions

### Device-Specific
- ✅ Android version compatibility (API 21-34)
- ✅ OEM-specific behaviors (Samsung, Xiaomi, Huawei)
- ✅ Clipboard restrictions (Android 10+)
- ✅ Background execution limits (Android 8.0+)
- ✅ Battery optimization impacts

## Troubleshooting

### Tests Fail on CI but Pass Locally
- Check Android SDK version compatibility
- Verify test timeout settings
- Check for race conditions in async tests

### Device Farm Tests Fail
- Verify Firebase project configuration
- Check API key is set correctly
- Review device availability in Firebase Test Lab

### Coverage Below Threshold
- Add tests for uncovered code paths
- Review `AUDIT_COVERAGE.md` for missing scenarios
- Check for untested error paths

## Contributing

When adding new features:
1. Write unit tests first (TDD)
2. Add integration tests for Flutter-Android bridge
3. Add device-specific tests if behavior varies by device
4. Update `AUDIT_COVERAGE.md` if addressing audit issues
5. Ensure all tests pass before submitting PR

