import 'package:flutter/services.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';
import 'package:flutter_test/flutter_test.dart';

/// `setTrackingEnabled` is the only public method that inverts its argument on
/// the way to the platform — Dart says `enabled`, both native sides implement
/// `disableTracking(disabled:)`. Flipping that by accident would silently turn
/// tracking on for every user who asked for it off, so it is pinned here.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('deeplinkly/channel');
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return true;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('disabling tracking sends disableTracking(disabled: true)', () async {
    expect(await FlutterDeeplinkly.setTrackingEnabled(false), isTrue);
    expect(calls.single.method, 'disableTracking');
    expect(calls.single.arguments, {'disabled': true});
  });

  test('enabling tracking sends disableTracking(disabled: false)', () async {
    expect(await FlutterDeeplinkly.setTrackingEnabled(true), isTrue);
    expect(calls.single.method, 'disableTracking');
    expect(calls.single.arguments, {'disabled': false});
  });

  test('a platform failure is reported rather than thrown', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      throw PlatformException(code: 'SDK_DISABLED');
    });

    expect(await FlutterDeeplinkly.setTrackingEnabled(false), isFalse);
  });

  test('a null reply is reported as failure, not a crash', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async => null);

    expect(await FlutterDeeplinkly.setTrackingEnabled(false), isFalse);
  });
}
