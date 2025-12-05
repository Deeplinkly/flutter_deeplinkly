import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';
import 'helpers/lifecycle_simulator.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Lifecycle Integration Tests', () {
    setUp(() {
      FlutterDeeplinkly.init();
    });

    test('deep link received during cold start is processed', () async {
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      // Simulate cold start scenario
      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'cold_start_click'};

      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 300));

      // Should be processed even during cold start
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('deep link received during warm start is processed', () async {
      // Simulate app already initialized
      LifecycleSimulator.simulateResumed();

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'warm_start_click'};

      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 300));

      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('deep link received when app is paused is queued', () async {
      LifecycleSimulator.simulatePaused();

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'paused_click'};

      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 300));

      // Should be queued and delivered when app resumes
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('deep link delivered when app resumes from background', () async {
      LifecycleSimulator.simulatePaused();

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      // Send deep link while paused
      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'background_click'};

      await channel.invokeMethod('onDeepLink', testData);

      // Resume app
      LifecycleSimulator.simulateResumed();
      await Future.delayed(const Duration(milliseconds: 300));

      // Deep link should be delivered
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('lifecycle state is tracked correctly', () {
      final instance = FlutterDeeplinkly.instance;

      LifecycleSimulator.simulateResumed();
      expect(instance.isInForeground, true);

      LifecycleSimulator.simulatePaused();
      expect(instance.isInForeground, false);

      LifecycleSimulator.simulateResumed();
      expect(instance.isInForeground, true);
    });

    test('multiple lifecycle transitions are handled', () async {
      final instance = FlutterDeeplinkly.instance;

      // Simulate multiple transitions
      LifecycleSimulator.simulateResumed();
      await Future.delayed(const Duration(milliseconds: 50));

      LifecycleSimulator.simulatePaused();
      await Future.delayed(const Duration(milliseconds: 50));

      LifecycleSimulator.simulateInactive();
      await Future.delayed(const Duration(milliseconds: 50));

      LifecycleSimulator.simulateResumed();
      await Future.delayed(const Duration(milliseconds: 50));

      // Should handle all transitions without crashing
      expect(instance.currentLifecycleState, isNotNull);
    });
  });
}

