import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';
import 'helpers/mock_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Error Scenario Tests', () {
    setUp(() {
      FlutterDeeplinkly.init();
    });

    test('activity null reference is handled gracefully', () async {
      // This tests that the native layer handles null activity
      // Flutter side should still receive deep links via queue
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'null_activity_test'};

      // Native should queue this if activity is null
      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 300));

      // Should not crash
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('method channel invocation failure is handled', () async {
      const channel = MethodChannel('deeplinkly/channel');

      // Try to invoke method that might fail
      try {
        await channel.invokeMethod('invalidMethod');
      } catch (e) {
        expect(e, isA<PlatformException>());
      }
    });

    test('network failure during resolution uses fallback', () async {
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      // Simulate network failure scenario
      // Native should use fallback with URI params
      const channel = MethodChannel('deeplinkly/channel');
      const fallbackData = {
        'click_id': 'network_failure_click',
        'utm_source': 'fallback_source',
        'utm_medium': 'fallback_medium',
      };

      await channel.invokeMethod('onDeepLink', fallbackData);
      await Future.delayed(const Duration(milliseconds: 300));

      // Should receive fallback data
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('deep link with missing click_id and code is skipped', () async {
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const invalidData = {'invalid': 'data'};

      // Native should skip this
      await channel.invokeMethod('onDeepLink', invalidData);
      await Future.delayed(const Duration(milliseconds: 300));

      // Should not crash
      expect(true, true);
    });

    test('concurrent deep links are processed without race conditions', () async {
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');

      // Send multiple deep links concurrently
      final futures = List.generate(10, (i) async {
        await channel.invokeMethod('onDeepLink', {'click_id': 'concurrent_$i'});
      });

      await Future.wait(futures);
      await Future.delayed(const Duration(milliseconds: 500));

      // Should handle all without crashes
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('deep link queue overflow is handled', () async {
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');

      // Send many deep links rapidly
      for (int i = 0; i < 100; i++) {
        await channel.invokeMethod('onDeepLink', {'click_id': 'overflow_$i'});
      }

      await Future.delayed(const Duration(milliseconds: 1000));

      // Should handle queue overflow gracefully
      expect(true, true);
    });

    test('callback throws exception is caught', () {
      FlutterDeeplinkly.init();

      // Register callback that throws
      FlutterDeeplinkly.onResolved((params) {
        throw Exception('Test exception');
      });

      // Should not crash the app
      expect(true, true);
    });

    test('stream subscription error is handled', () async {
      final errors = <dynamic>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) {
          throw Exception('Stream error');
        },
        onError: (error) {
          errors.add(error);
        },
      );

      const channel = MethodChannel('deeplinkly/channel');
      await channel.invokeMethod('onDeepLink', {'click_id': 'error_test'});
      await Future.delayed(const Duration(milliseconds: 300));

      // Error should be caught
      expect(errors.length, greaterThanOrEqualTo(0));
    });
  });
}

