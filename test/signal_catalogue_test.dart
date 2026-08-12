// Guards tool/signals.json and the three files generated from it.
//
// The CI gate is `dart run tool/gen_signals.dart --check`, but a gate that only
// exists in CI is one `--no-verify` away from being skipped. These tests assert
// the same invariants from inside the test suite, and — more importantly —
// assert that the *generated* Kotlin and Swift still agree with the source,
// which is what catches someone editing a generated file by hand.

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

const _tiers = {'minimal', 'reduced', 'full'};
const _scopes = {'static', 'dynamic', 'identity'};
const _types = {'string', 'int', 'float', 'bool', 'datetime'};

void main() {
  final source = File('tool/signals.json');
  final doc = jsonDecode(source.readAsStringSync()) as Map<String, dynamic>;
  final signals = (doc['signals'] as Map<String, dynamic>).map(
    (key, value) => MapEntry(key, value as Map<String, dynamic>),
  );

  String? platformsOf(String key) {
    final list = signals[key]!['platforms'] as List<dynamic>?;
    if (list == null) return null;
    return list.join(',');
  }

  bool onAndroid(String key) {
    final p = platformsOf(key);
    return p == null || p.contains('android');
  }

  bool onIos(String key) {
    final p = platformsOf(key);
    return p == null || p.contains('ios');
  }

  group('signals.json', () {
    test('is not empty and declares a catalogue version', () {
      expect(doc['catalogue_version'], isA<int>());
      expect(signals, isNotEmpty);
    });

    test('every signal declares a valid tier, scope and type', () {
      for (final entry in signals.entries) {
        expect(_tiers, contains(entry.value['tier']),
            reason: '${entry.key} has an invalid tier');
        expect(_scopes, contains(entry.value['scope']),
            reason: '${entry.key} has an invalid scope');
        expect(_types, contains(entry.value['type']),
            reason: '${entry.key} has an invalid type');
      }
    });

    test('every signal name is lower_snake_case', () {
      final pattern = RegExp(r'^[a-z][a-z0-9_]*$');
      for (final key in signals.keys) {
        expect(pattern.hasMatch(key), isTrue, reason: '$key is not snake_case');
      }
    });

    // Device signals and link attribution share one flat payload namespace. A
    // device signal named `source` or `click_id` would overwrite the very
    // attribution it is meant to accompany.
    test('no device signal reuses a link-identity key', () {
      final identity = signals.entries
          .where((e) => e.value['scope'] == 'identity')
          .map((e) => e.key)
          .toSet();
      final device = signals.entries
          .where((e) => e.value['scope'] != 'identity')
          .map((e) => e.key)
          .toSet();

      expect(identity.intersection(device), isEmpty);
    });

    test('a platform-scoped signal names at least one real platform', () {
      for (final entry in signals.entries) {
        final platforms = entry.value['platforms'] as List<dynamic>?;
        if (platforms == null) continue;
        expect(platforms, isNotEmpty,
            reason: '${entry.key}: omit "platforms" to mean both');
        for (final p in platforms) {
          expect(['android', 'ios'], contains(p),
              reason: '${entry.key} lists unknown platform $p');
        }
      }
    });
  });

  group('generated catalogues', () {
    // The Kotlin catalogue moved to the native Android SDK repo when the
    // Android implementation was extracted, so it can only be checked when that
    // repo is reachable. Looked for via DEEPLINKLY_ANDROID, then at the sibling
    // path a normal checkout produces.
    //
    // The Kotlin assertions below skip rather than fail when it is not — a
    // contributor with only this repo checked out is not doing anything wrong.
    // What must never happen is them passing silently, so `skip` carries a
    // reason and the CI that gates a release sets the variable.
    final androidRepo =
        Platform.environment['DEEPLINKLY_ANDROID'] ?? '../android_deeplinkly';
    final kotlinFile = File(
      '$androidRepo/deeplinkly/src/main/kotlin/com/deeplinkly/'
      'android_deeplinkly/privacy/SignalCatalogue.kt',
    );
    final kotlin =
        kotlinFile.existsSync() ? kotlinFile.readAsStringSync() : null;
    final noKotlin = kotlin == null
        ? 'SignalCatalogue.kt not reachable at ${kotlinFile.path} — set '
            'DEEPLINKLY_ANDROID to the android_deeplinkly checkout'
        : null;

    final iosRepo =
        Platform.environment['DEEPLINKLY_IOS'] ?? '../ios_deeplinkly';
    final swiftFile = File('$iosRepo/Sources/Deeplinkly/SignalCatalogue.swift');
    final swift = swiftFile.existsSync() ? swiftFile.readAsStringSync() : null;
    final noSwift = swift == null
        ? 'SignalCatalogue.swift not reachable at ${swiftFile.path} — set '
            'DEEPLINKLY_IOS to the ios_deeplinkly checkout'
        : null;

    test('Swift carries the generated-file banner', () {
      expect(swift, contains('GENERATED FILE'));
    }, skip: noSwift);

    test('Swift pins the same catalogue version as the source', () {
      expect(
          swift, contains('static let version = ${doc['catalogue_version']}'));
    }, skip: noSwift);

    test('Kotlin carries the banner and pins the same version', () {
      expect(kotlin, contains('GENERATED FILE'));
      expect(
          kotlin, contains('const val VERSION = ${doc['catalogue_version']}'));
    }, skip: noKotlin);

    test('Kotlin lists exactly the Android signals', () {
      for (final key in signals.keys) {
        final present = kotlin!.contains('"$key" to SignalSpec(');
        expect(present, onAndroid(key),
            reason: onAndroid(key)
                ? '$key is missing from SignalCatalogue.kt — regenerate'
                : '$key is iOS-only but appears in SignalCatalogue.kt');
      }
    }, skip: noKotlin);

    test('Swift lists exactly the iOS signals', () {
      for (final key in signals.keys) {
        final present = swift!.contains('"$key": SignalSpec(');
        expect(present, onIos(key),
            reason: onIos(key)
                ? '$key is missing from SignalCatalogue.swift — regenerate'
                : '$key is Android-only but appears in SignalCatalogue.swift');
      }
    }, skip: noSwift);

    test('the two agree on the tier of every shared signal', () {
      for (final entry in signals.entries) {
        if (!onAndroid(entry.key) || !onIos(entry.key)) continue;
        final tier = entry.value['tier'] as String;

        expect(
            kotlin,
            contains(
                '"${entry.key}" to SignalSpec(SignalTier.${tier.toUpperCase()},'),
            reason: '${entry.key} has the wrong tier in Kotlin');
        expect(swift, contains('"${entry.key}": SignalSpec(tier: .$tier,'),
            reason: '${entry.key} has the wrong tier in Swift');
      }
    }, skip: noKotlin ?? noSwift);
  });
}
