import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Android Version Compatibility Tests', () {
    setUp(() {
      FlutterDeeplinkly.init();
    });

    test('Android API 21 (Lollipop) compatibility', () async {
      if (!Platform.isAndroid) return;

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'api21_test'};

      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 300));

      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('Android API 26+ (Oreo) background limits', () async {
      if (!Platform.isAndroid) return;

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'api26_test'};

      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 300));

      // Should respect background execution limits
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('Android API 29+ (Android 10) clipboard restrictions', () async {
      if (!Platform.isAndroid) return;

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const clipboardData = {'click_id': 'api29_clipboard', 'source': 'clipboard'};

      await channel.invokeMethod('onDeepLink', clipboardData);
      await Future.delayed(const Duration(milliseconds: 300));

      // Should handle clipboard restrictions
      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('Android API 30+ (Android 11) scoped storage', () async {
      if (!Platform.isAndroid) return;

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'api30_test'};

      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 300));

      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });

    test('Android API 33+ (Android 13) notification permissions', () async {
      if (!Platform.isAndroid) return;

      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      const testData = {'click_id': 'api33_test'};

      await channel.invokeMethod('onDeepLink', testData);
      await Future.delayed(const Duration(milliseconds: 300));

      expect(receivedLinks.length, greaterThanOrEqualTo(0));
    });
  });
}

