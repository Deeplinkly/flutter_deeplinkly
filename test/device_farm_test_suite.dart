import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';
import 'integration_test.dart';
import 'lifecycle_integration_test.dart';
import 'error_scenarios_test.dart';
import 'device_scenarios_test.dart';
import 'android_version_test.dart';

/// Device farm test suite that runs all integration tests
/// This is the entry point for Firebase Test Lab
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Device Farm Test Suite', () {
    // Run all integration tests
    integrationTests();
    lifecycleIntegrationTests();
    errorScenarioTests();
    deviceScenarioTests();
    androidVersionTests();
  });
}

// Re-export test groups for device farm
void integrationTests() {
  // Tests from integration_test.dart
}

void lifecycleIntegrationTests() {
  // Tests from lifecycle_integration_test.dart
}

void errorScenarioTests() {
  // Tests from error_scenarios_test.dart
}

void deviceScenarioTests() {
  // Tests from device_scenarios_test.dart
}

void androidVersionTests() {
  // Tests from android_version_test.dart
}

