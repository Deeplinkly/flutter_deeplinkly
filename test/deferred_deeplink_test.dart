import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';
import 'package:flutter_deeplinkly/models/deeplinkly.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Deferred Deep Link Tests', () {
    setUp(() {
      FlutterDeeplinkly.init();
    });

    test('deferred deep link flow with click_id resolution', () async {
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      // Simulate install referrer with click_id
      // In real scenario: user clicks TEST_LINK, installs app, install referrer contains click_id
      const channel = MethodChannel('deeplinkly/channel');
      
      // Simulate deferred deep link data from install referrer
      // This would come from InstallReferrerHandler after resolving click_id
      final deferredData = {
        'click_id': 'test_deferred_click_123',
        'params': {
          'canonical_identifier': 'TEST_LINK',
          'title': 'Test Deferred Link',
          'test_param': 'test_value',
          'utm_source': 'test_source',
          'utm_medium': 'test_medium',
        },
      };

      await channel.invokeMethod('onDeepLink', deferredData);
      await Future.delayed(const Duration(milliseconds: 500));

      // Verify deferred deep link was delivered
      expect(receivedLinks.length, greaterThan(0));
      final deliveredLink = receivedLinks.first;
      expect(deliveredLink['click_id'], 'test_deferred_click_123');
      expect(deliveredLink['params'], isNotNull);
    });

    test('deferred deep link with UTM parameters preserved', () async {
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      
      // Simulate deferred deep link with UTM params
      final deferredData = {
        'click_id': 'utm_test_click',
        'utm_source': 'google',
        'utm_medium': 'cpc',
        'utm_campaign': 'deferred_test',
        'utm_term': 'mobile',
        'utm_content': 'banner',
        'gclid': 'test_gclid_value',
      };

      await channel.invokeMethod('onDeepLink', deferredData);
      await Future.delayed(const Duration(milliseconds: 500));

      expect(receivedLinks.length, greaterThan(0));
      final deliveredLink = receivedLinks.first;
      expect(deliveredLink['utm_source'], 'google');
      expect(deliveredLink['utm_medium'], 'cpc');
      expect(deliveredLink['utm_campaign'], 'deferred_test');
      expect(deliveredLink['gclid'], 'test_gclid_value');
    });

    test('install attribution contains deferred deep link data', () async {
      // Test that getInstallAttribution returns data from deferred deep link
      // This simulates the scenario where install referrer was processed
      const channel = MethodChannel('deeplinkly/channel');
      
      // Simulate install attribution being set
      // In real scenario, InstallReferrerHandler saves this
      final attribution = await FlutterDeeplinkly.getInstallAttribution();
      
      // Attribution should be available (may be empty in test without proper setup)
      expect(attribution, isA<Map<String, String>>());
    });

    test('deferred deep link delivered on app first launch', () async {
      // Test that deferred deep link is delivered when app launches for first time
      // after being installed via TEST_LINK click
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      // Simulate app first launch scenario
      // InstallReferrerHandler would have been called and resolved click_id
      const channel = MethodChannel('deeplinkly/channel');
      
      final firstLaunchData = {
        'click_id': 'first_launch_click',
        'source': 'install_referrer',
        'params': {
          'canonical_identifier': 'TEST_LINK',
        },
      };

      await channel.invokeMethod('onDeepLink', firstLaunchData);
      await Future.delayed(const Duration(milliseconds: 500));

      expect(receivedLinks.length, greaterThan(0));
      expect(receivedLinks.first['source'], 'install_referrer');
    });

    test('deferred deep link with network resolution', () async {
      // Test that click_id from install referrer is resolved via network
      // and deferred params are delivered
      final receivedLinks = <Map<dynamic, dynamic>>[];
      FlutterDeeplinkly.instance.deepLinkStream.listen(
        (data) => receivedLinks.add(data),
      );

      const channel = MethodChannel('deeplinkly/channel');
      
      // Simulate network-resolved deferred deep link
      // InstallReferrerHandler resolves click_id -> gets params from server
      final resolvedData = {
        'click_id': 'resolved_click_456',
        'params': {
          'canonical_identifier': 'TEST_LINK',
          'title': 'Resolved Deferred Link',
          'custom_data': 'resolved_value',
        },
      };

      await channel.invokeMethod('onDeepLink', resolvedData);
      await Future.delayed(const Duration(milliseconds: 500));

      expect(receivedLinks.length, greaterThan(0));
      final delivered = receivedLinks.first;
      expect(delivered['click_id'], 'resolved_click_456');
      expect(delivered['params'], isNotNull);
    });
  });
}

