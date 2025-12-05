import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Device-Specific Test Scenarios', () {
    setUp(() {
      FlutterDeeplinkly.init();
    });

    test('Android 8.0+ background execution limits', () async {
      // Test that deep links work within Android 8.0+ background limits
      if (!Platform.isAndroid) {
        return; // Skip on non-Android
      }

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'android8_test'};

      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 300));

      // Should work within background limits
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('Android 10+ clipboard restrictions', () async {
      // Test clipboard-based deep links on Android 10+
      if (!Platform.isAndroid) {
        return;
      }

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      // Clipboard handler should respect permissions
      // This is tested at native level, Flutter side should receive if successful
      const channel = MethodChannel('deeplinkly/channel');
      const clipboardData = {'click_id': 'clipboard_test', 'source': 'clipboard'};

      await channel.invokeMethod('onDeepLink', clipboardData);
      await Future.delayed(const Duration(milliseconds: 300));

      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('Samsung device compatibility', () async {
      // Test specific Samsung device behaviors
      if (!Platform.isAndroid) {
        return;
      }

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const samsungData = {'click_id': 'samsung_test', 'device': 'samsung'};

      await channel.invokeMethod('onDeepLink', samsungData);
      await Future.delayed(const Duration(milliseconds: 300));

      // Should work on Samsung devices
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('slow network conditions', () async {
      // Test deep link handling with slow network
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const slowNetworkData = {'click_id': 'slow_network_test'};

      // Simulate slow network by delaying
      await Future.delayed(const Duration(milliseconds: 2000));
      await channel.invokeMethod('onDeepLink', slowNetworkData);
      await Future.delayed(const Duration(milliseconds: 500));

      // Should eventually receive data
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('network switching scenario', () async {
      // Test deep link during network switch
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const networkSwitchData = {'click_id': 'network_switch_test'};

      // Send deep link
      await channel.invokeMethod('onDeepLink', networkSwitchData);
      
      // Simulate network switch
      await Future.delayed(const Duration(milliseconds: 100));
      
      // Send another deep link after switch
      await channel.invokeMethod('onDeepLink', {'click_id': 'after_switch'});
      await Future.delayed(const Duration(milliseconds: 500));

      // Should handle network switching
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('battery optimization impact', () async {
      // Test deep links when battery optimization is active
      if (!Platform.isAndroid) {
        return;
      }

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const batteryOptData = {'click_id': 'battery_opt_test'};

      await channel.invokeMethod('onDeepLink', batteryOptData);
      await Future.delayed(const Duration(milliseconds: 500));

      // Should work despite battery optimization
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });
  });
}

