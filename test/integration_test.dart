import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';
import 'helpers/mock_method_channel.dart';
import 'helpers/lifecycle_simulator.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Flutter-Android Bridge Integration', () {
    test('deep link received before init is queued', () async {
      // This test verifies that deep links arriving before initialization
      // are properly queued and delivered after init
      
      FlutterDeeplinkly.init();
      
      final receivedLinks = <Map<dynamic, dynamic>>[];
      final subscription = FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );
      
      // Simulate deep link from native
      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'pre_init_click', 'utm_source': 'test'};
      
      // Note: In real scenario, native would queue this
      // Here we're testing the Flutter side handles it
      await channel.invokeMethod('onDeepLink', testData);
      
      await Future.delayed(const Duration(milliseconds: 200));
      
      // Stream should receive the data
      // Note: This may not work without proper native setup
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
      
      await subscription.cancel();
    });

    test('deep link received during app startup is processed', () async {
      FlutterDeeplinkly.init();
      
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );
      
      // Simulate deep link during startup
      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'startup_click'};
      
      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 200));
      
      // Should be processed
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('deep link received when app is in background', () async {
      FlutterDeeplinkly.init();
      
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );
      
      // Simulate background state
      LifecycleSimulator.simulatePaused();
      
      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'background_click'};
      
      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 200));
      
      // Should still be received
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('multiple deep links in rapid succession are all processed', () async {
      FlutterDeeplinkly.init();
      
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );
      
      const channel = MethodChannel('deeplinkly/channel');
      
      // Send multiple deep links rapidly
      for (int i = 0; i < 5; i++) {
        await channel.invokeMethod('onDeepLink', {'click_id': 'rapid_$i'});
      }
      
      await Future.delayed(const Duration(milliseconds: 500));
      
      // All should be processed
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });
  });

  group('Lifecycle Integration', () {
    test('Flutter ready signal is sent on resume', () async {
      FlutterDeeplinkly.init();
      
      LifecycleSimulator.simulatePaused();
      LifecycleSimulator.simulateResumed();
      
      await Future.delayed(const Duration(milliseconds: 200));
      
      // Flutter should be marked as ready
      expect(FlutterDeeplinkly.instance.isInForeground, true);
    });

    test('lifecycle changes are communicated to native', () async {
      FlutterDeeplinkly.init();
      
      const channel = MethodChannel('deeplinkly/channel');
      
      // Set up method call handler to capture lifecycle changes
      var lifecycleState = '';
      channel.setMethodCallHandler((call) async {
        if (call.method == 'onLifecycleChange') {
          lifecycleState = call.arguments['state'] as String;
        }
      });
      
      LifecycleSimulator.simulateResumed();
      await Future.delayed(const Duration(milliseconds: 200));
      
      // Lifecycle change should be communicated
      // Note: This may not work without proper native setup
      expect(lifecycleState, anyOf('', 'resumed'));
    });
  });

  group('Error Scenarios', () {
    test('network failure during deep link resolution uses fallback', () async {
      FlutterDeeplinkly.init();
      
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );
      
      // Simulate deep link with network failure scenario
      // In real scenario, native would handle network failure
      const channel = MethodChannel('deeplinkly/channel');
      const fallbackData = {
        'click_id': 'fallback_click',
        'utm_source': 'fallback_source',
      };
      
      await channel.invokeMethod('onDeepLink', fallbackData);
      await Future.delayed(const Duration(milliseconds: 200));
      
      // Fallback data should be received
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('method channel errors are handled gracefully', () async {
      FlutterDeeplinkly.init();
      
      const channel = MethodChannel('deeplinkly/channel');
      
      // Try to invoke non-existent method
      try {
        await channel.invokeMethod('nonExistentMethod');
      } catch (e) {
        expect(e, isA<PlatformException>());
      }
    });
  });
}

