// Generates the per-platform signal catalogues from tool/signals.json.
//
//   dart run tool/gen_signals.dart                    # write Kotlin + Swift
//   dart run tool/gen_signals.dart --backend=<path>   # ...and the Django module
//   dart run tool/gen_signals.dart --check            # exit 1 if anything is stale
//
// The --check form is the CI gate. Three hand-written tables in three languages
// drift — ours already had, with Kotlin listing 11 high-entropy keys against
// Swift's 5 — so the tables are generated and the check is what keeps them
// honest.
//
// The backend lives in its own repository, so its path cannot be hard-coded.
// Pass --backend=/path/to/deeplinkly or set DEEPLINKLY_BACKEND; without either,
// the Python module is skipped (and --check does not fail on it).

import 'dart:convert';
import 'dart:io';

const _tiers = ['minimal', 'reduced', 'full'];
const _scopes = ['static', 'dynamic', 'identity'];
const _types = ['string', 'int', 'float', 'bool', 'datetime'];
const _defaultMaxLen = 256;

void main(List<String> args) {
  final check = args.contains('--check');
  final backend = _flag(args, '--backend') ??
      Platform.environment['DEEPLINKLY_BACKEND'];

  final root = _repoRoot();
  final sourcePath = '$root/tool/signals.json';
  final source = File(sourcePath);
  if (!source.existsSync()) {
    _fail('missing $sourcePath');
  }

  final Map<String, dynamic> doc;
  try {
    doc = jsonDecode(source.readAsStringSync()) as Map<String, dynamic>;
  } on FormatException catch (e) {
    _fail('$sourcePath is not valid JSON: ${e.message}');
  }

  final version = doc['catalogue_version'];
  if (version is! int) {
    _fail('catalogue_version must be an integer');
  }

  final raw = doc['signals'];
  if (raw is! Map<String, dynamic>) {
    _fail('signals must be an object');
  }

  // Sorted so regeneration is byte-stable regardless of the order someone
  // happened to add keys to the JSON.
  final names = raw.keys.toList()..sort();
  final signals = <_Signal>[];
  for (final name in names) {
    signals.add(_parse(name, raw[name]));
  }
  _validate(signals);

  final outputs = <String, String>{
    '$root/android/src/main/kotlin/com/deeplinkly/flutter_deeplinkly/privacy/SignalCatalogue.kt':
        _kotlin(version, signals),
    '$root/ios/Classes/SignalCatalogue.swift': _swift(version, signals),
    '$root/docs/SIGNALS.md': _markdown(version, signals),
  };

  if (backend != null && backend.isNotEmpty) {
    final dir = backend.endsWith('/')
        ? backend.substring(0, backend.length - 1)
        : backend;
    if (!Directory('$dir/links').existsSync()) {
      _fail('--backend=$dir does not look like the Django repo (no links/ dir)');
    }
    outputs['$dir/links/signal_catalogue.py'] = _python(version, signals);
    // The backend's parity test needs a local copy to compare against; it
    // cannot reach across repositories.
    outputs['$dir/links/signals.json'] = source.readAsStringSync();
  } else {
    stderr.writeln(
      'note: no --backend/DEEPLINKLY_BACKEND, skipping links/signal_catalogue.py',
    );
  }

  var stale = 0;
  outputs.forEach((path, content) {
    final file = File(path);
    final current = file.existsSync() ? file.readAsStringSync() : null;
    if (current == content) return;
    stale++;
    if (check) {
      stderr.writeln('stale: $path');
    } else {
      file.parent.createSync(recursive: true);
      file.writeAsStringSync(content);
      stdout.writeln('wrote: $path');
    }
  });

  if (check) {
    if (stale > 0) {
      stderr.writeln(
        '\n$stale file(s) out of sync with tool/signals.json.\n'
        'Run: dart run tool/gen_signals.dart',
      );
      exit(1);
    }
    stdout.writeln('signal catalogue is in sync (${signals.length} signals)');
  } else if (stale == 0) {
    stdout.writeln('signal catalogue already up to date');
  }
}

// ---------------------------------------------------------------------------

class _Signal {
  _Signal({
    required this.name,
    required this.tier,
    required this.scope,
    required this.type,
    required this.platforms,
    required this.maxLen,
    required this.deprecated,
  });

  final String name;
  final String tier;
  final String scope;
  final String type;
  final List<String> platforms; // empty means every platform
  final int maxLen;
  final String? deprecated;

  bool get onAndroid => platforms.isEmpty || platforms.contains('android');
  bool get onIos => platforms.isEmpty || platforms.contains('ios');
}

_Signal _parse(String name, dynamic value) {
  if (value is! Map<String, dynamic>) {
    _fail('signal "$name" must be an object');
  }
  final tier = value['tier'];
  final scope = value['scope'];
  final type = value['type'];
  if (!_tiers.contains(tier)) {
    _fail('signal "$name" has tier "$tier"; expected one of $_tiers');
  }
  if (!_scopes.contains(scope)) {
    _fail('signal "$name" has scope "$scope"; expected one of $_scopes');
  }
  if (!_types.contains(type)) {
    _fail('signal "$name" has type "$type"; expected one of $_types');
  }
  final platforms = <String>[];
  final rawPlatforms = value['platforms'];
  if (rawPlatforms != null) {
    if (rawPlatforms is! List) {
      _fail('signal "$name" has a non-list "platforms"');
    }
    for (final p in rawPlatforms) {
      if (p != 'android' && p != 'ios') {
        _fail('signal "$name" lists unknown platform "$p"');
      }
      platforms.add(p as String);
    }
    if (platforms.isEmpty) {
      _fail('signal "$name" has an empty "platforms"; omit it to mean both');
    }
  }
  return _Signal(
    name: name,
    tier: tier as String,
    scope: scope as String,
    type: type as String,
    platforms: platforms,
    maxLen: (value['max_len'] as int?) ?? _defaultMaxLen,
    deprecated: value['deprecated'] as String?,
  );
}

void _validate(List<_Signal> signals) {
  final seen = <String>{};
  for (final s in signals) {
    if (!seen.add(s.name)) {
      _fail('duplicate signal "${s.name}"');
    }
    if (!RegExp(r'^[a-z][a-z0-9_]*$').hasMatch(s.name)) {
      _fail('signal "${s.name}" must be lower_snake_case');
    }
    // The payload is one flat namespace shared with the link-identity keys.
    // A device signal named `source` or `click_id` would silently overwrite
    // the attribution it is meant to accompany.
    if (s.scope != 'identity' && _reservedIdentityKeys.contains(s.name)) {
      _fail(
        'signal "${s.name}" collides with a link-identity key; '
        'device signals and attribution share one flat namespace',
      );
    }
  }
}

// Keys the SDK writes into the same flat payload to name the link being
// reported on. Kept here rather than derived from the catalogue so that
// removing a signal from signals.json cannot silently free up the name.
const _reservedIdentityKeys = <String>{
  'click_id',
  'code',
  'source',
  'install_referrer',
  'custom_user_id',
  'utm_source',
  'utm_medium',
  'utm_campaign',
  'utm_term',
  'utm_content',
  'gclid',
  'fbclid',
  'ttclid',
};

// ---------------------------------------------------------------------------

const _banner = '''
// GENERATED FILE — do not edit.
// Source: tool/signals.json
// Regenerate: dart run tool/gen_signals.dart
''';

String _kotlin(int version, List<_Signal> signals) {
  final b = StringBuffer()
    ..write(_banner)
    ..writeln('package com.deeplinkly.flutter_deeplinkly.privacy')
    ..writeln()
    ..writeln('/** The lowest [AttributionLevel] at which a signal still ships. */')
    ..writeln('enum class SignalTier(val rank: Int) {')
    ..writeln('    MINIMAL(0),')
    ..writeln('    REDUCED(1),')
    ..writeln('    FULL(2),')
    ..writeln('}')
    ..writeln()
    ..writeln('/** Where a signal comes from, and where the backend stores it. */')
    ..writeln('enum class SignalScope {')
    ..writeln('    /** Collected once per device and cached until the profile stamp changes. */')
    ..writeln('    STATIC,')
    ..writeln('    /** Collected fresh at send time. Never persisted in a queue. */')
    ..writeln('    DYNAMIC,')
    ..writeln('    /** Names the link or user being reported on, not the device. */')
    ..writeln('    IDENTITY,')
    ..writeln('}')
    ..writeln()
    ..writeln('data class SignalSpec(val tier: SignalTier, val scope: SignalScope)')
    ..writeln()
    ..writeln('/**')
    ..writeln(' * Every signal the SDK may send, and the level at which each is permitted.')
    ..writeln(' *')
    ..writeln(' * Fail-closed by construction: [allows] returns false for any key that is')
    ..writeln(' * not in [SPECS], at every level including FULL. A new signal that nobody')
    ..writeln(' * classified therefore never leaves the device, which is the failure mode')
    ..writeln(' * we want. The previous design was the opposite — REDUCED was a denylist,')
    ..writeln(' * so an unclassified key shipped to users who had asked us not to.')
    ..writeln(' */')
    ..writeln('object SignalCatalogue {')
    ..writeln('    /** Part of the static-profile stamp; bumping it forces a re-collect. */')
    ..writeln('    const val VERSION = $version')
    ..writeln()
    ..writeln('    val SPECS: Map<String, SignalSpec> = mapOf(');
  for (final s in signals) {
    if (!s.onAndroid) continue;
    final tier = s.tier.toUpperCase();
    final scope = s.scope.toUpperCase();
    final suffix = s.deprecated != null ? ' // deprecated' : '';
    b.writeln(
      '        "${s.name}" to SignalSpec(SignalTier.$tier, SignalScope.$scope),$suffix',
    );
  }
  b
    ..writeln('    )')
    ..writeln()
    ..writeln('    /** Whether [key] may be sent at [level]. Unknown keys are never sent. */')
    ..writeln('    fun allows(key: String, level: AttributionLevel): Boolean {')
    ..writeln('        val spec = SPECS[key] ?: return false')
    ..writeln('        return when (level) {')
    ..writeln('            AttributionLevel.FULL -> true')
    ..writeln('            AttributionLevel.REDUCED -> spec.tier.rank <= SignalTier.REDUCED.rank')
    ..writeln('            AttributionLevel.MINIMAL -> spec.tier.rank <= SignalTier.MINIMAL.rank')
    ..writeln('            AttributionLevel.NONE -> false')
    ..writeln('        }')
    ..writeln('    }')
    ..writeln()
    ..writeln('    fun keysFor(scope: SignalScope): Set<String> =')
    ..writeln('        SPECS.filterValues { it.scope == scope }.keys')
    ..writeln('}');
  return b.toString();
}

String _swift(int version, List<_Signal> signals) {
  final b = StringBuffer()
    ..write(_banner)
    ..writeln('import Foundation')
    ..writeln()
    ..writeln('/// The lowest `AttributionLevel` at which a signal still ships.')
    ..writeln('enum SignalTier: Int {')
    ..writeln('    case minimal = 0')
    ..writeln('    case reduced = 1')
    ..writeln('    case full = 2')
    ..writeln('}')
    ..writeln()
    ..writeln('/// Where a signal comes from, and where the backend stores it.')
    ..writeln('///')
    ..writeln('/// The cases are named `staticProfile`/`dynamicSignal` rather than')
    ..writeln('/// `static`/`dynamic` because both are Swift keywords.')
    ..writeln('enum SignalScope {')
    ..writeln('    /// Collected once per device and cached until the profile stamp changes.')
    ..writeln('    case staticProfile')
    ..writeln('    /// Collected fresh at send time. Never persisted in a queue.')
    ..writeln('    case dynamicSignal')
    ..writeln('    /// Names the link or user being reported on, not the device.')
    ..writeln('    case identity')
    ..writeln('}')
    ..writeln()
    ..writeln('struct SignalSpec {')
    ..writeln('    let tier: SignalTier')
    ..writeln('    let scope: SignalScope')
    ..writeln('}')
    ..writeln()
    ..writeln('/// Every signal the SDK may send, and the level at which each is permitted.')
    ..writeln('///')
    ..writeln('/// Fail-closed by construction: `allows` returns false for any key not in')
    ..writeln('/// `specs`, at every level including `.full`. Must stay in lockstep with')
    ..writeln('/// the Kotlin twin — which is why both are generated from tool/signals.json')
    ..writeln('/// rather than maintained by hand.')
    ..writeln('enum SignalCatalogue {')
    ..writeln('    /// Part of the static-profile stamp; bumping it forces a re-collect.')
    ..writeln('    static let version = $version')
    ..writeln()
    ..writeln('    static let specs: [String: SignalSpec] = [');
  for (final s in signals) {
    if (!s.onIos) continue;
    final scope = _swiftScope(s.scope);
    final suffix = s.deprecated != null ? ' // deprecated' : '';
    b.writeln(
      '        "${s.name}": SignalSpec(tier: .${s.tier}, scope: .$scope),$suffix',
    );
  }
  b
    ..writeln('    ]')
    ..writeln()
    ..writeln('    /// Whether `key` may be sent at `level`. Unknown keys are never sent.')
    ..writeln('    static func allows(_ key: String, at level: AttributionLevel) -> Bool {')
    ..writeln('        guard let spec = specs[key] else { return false }')
    ..writeln('        switch level {')
    ..writeln('        case .full: return true')
    ..writeln('        case .reduced: return spec.tier.rawValue <= SignalTier.reduced.rawValue')
    ..writeln('        case .minimal: return spec.tier.rawValue <= SignalTier.minimal.rawValue')
    ..writeln('        case .none: return false')
    ..writeln('        }')
    ..writeln('    }')
    ..writeln()
    ..writeln('    static func keys(for scope: SignalScope) -> Set<String> {')
    ..writeln('        Set(specs.filter { \$0.value.scope == scope }.keys)')
    ..writeln('    }')
    ..writeln('}');
  return b.toString();
}

/// The user-facing field reference.
///
/// Generated so it cannot drift from what the SDK actually sends — which is the
/// failure mode a hand-written table of 70+ fields is guaranteed to reach.
String _markdown(int version, List<_Signal> signals) {
  final b = StringBuffer()
    ..writeln('<!-- GENERATED FILE — do not edit. -->')
    ..writeln('<!-- Source: tool/signals.json -->')
    ..writeln('<!-- Regenerate: dart run tool/gen_signals.dart -->')
    ..writeln()
    ..writeln('# Device signals')
    ..writeln()
    ..writeln('Every field the SDK may send to `/api/v1/enrich`, and the lowest')
    ..writeln('[attribution level](FLUTTER_SDK.md#attribution-levels) at which each still')
    ..writeln('ships. Catalogue version $version.')
    ..writeln()
    ..writeln('A field absent from this table is never sent, at any level: the SDK drops')
    ..writeln('anything it cannot find in the catalogue rather than defaulting to')
    ..writeln('permissive.')
    ..writeln()
    ..writeln('**Level** — `minimal` also ships at `reduced` and `full`; `reduced` also')
    ..writeln('ships at `full`; `full` ships only at `full`. At `none` nothing is sent.')
    ..writeln()
    ..writeln('**When** — `static` is collected once per device and cached until the app,')
    ..writeln('OS or SDK version changes. `dynamic` is re-read on every send. `identity`')
    ..writeln('names the link or user being reported on rather than the device.')
    ..writeln();

  for (final scope in ['identity', 'static', 'dynamic']) {
    final rows = signals.where((s) => s.scope == scope).toList();
    if (rows.isEmpty) continue;
    b
      ..writeln('## ${_scopeHeading(scope)}')
      ..writeln()
      ..writeln('| Field | Level | Type | Platforms |')
      ..writeln('| --- | --- | --- | --- |');
    // Lowest tier first, so the fields that survive the strictest level lead.
    rows.sort((a, c) {
      final byTier = _tierRank(a.tier).compareTo(_tierRank(c.tier));
      return byTier != 0 ? byTier : a.name.compareTo(c.name);
    });
    for (final s in rows) {
      final platforms = s.platforms.isEmpty ? 'both' : s.platforms.join(', ');
      b.writeln('| `${s.name}` | ${s.tier} | ${s.type} | $platforms |');
    }
    b.writeln();
  }

  return b.toString();
}

int _tierRank(String tier) => const {'minimal': 0, 'reduced': 1, 'full': 2}[tier]!;

String _scopeHeading(String scope) {
  if (scope == 'identity') return 'Link identity';
  if (scope == 'static') return 'Static device profile';
  return 'Dynamic signals';
}

/// `static` and `dynamic` are Swift keywords, so the enum cases are renamed.
String _swiftScope(String scope) {
  if (scope == 'static') return 'staticProfile';
  if (scope == 'dynamic') return 'dynamicSignal';
  return 'identity';
}

String _python(int version, List<_Signal> signals) {
  final b = StringBuffer()
    ..writeln('# GENERATED FILE — do not edit.')
    ..writeln('# Source: links/signals.json (copied from the SDK repo)')
    ..writeln('# Regenerate: dart run tool/gen_signals.dart --backend=<this repo>')
    ..writeln('"""Every device signal the SDK may send, and the level each requires.')
    ..writeln()
    ..writeln('The tier/scope tables here are the same ones compiled into the Android and')
    ..writeln('iOS SDKs. `type` names a caster rather than holding one: the casters live')
    ..writeln('in links/views.py where they always have, and importing them here would be')
    ..writeln('circular. Callers map TYPE_* to their own caster.')
    ..writeln()
    ..writeln('A key absent from SIGNAL_SPECS is dropped on ingest, mirroring the SDK.')
    ..writeln('"""')
    ..writeln()
    ..writeln('CATALOGUE_VERSION = $version')
    ..writeln()
    ..writeln('TIER_MINIMAL = "minimal"')
    ..writeln('TIER_REDUCED = "reduced"')
    ..writeln('TIER_FULL = "full"')
    ..writeln()
    ..writeln('# Rank order, lowest tier first. A signal ships at a level when its tier')
    ..writeln('# rank is at or below that level\'s rank.')
    ..writeln('TIER_RANK = {TIER_MINIMAL: 0, TIER_REDUCED: 1, TIER_FULL: 2}')
    ..writeln()
    ..writeln('SCOPE_STATIC = "static"')
    ..writeln('SCOPE_DYNAMIC = "dynamic"')
    ..writeln('SCOPE_IDENTITY = "identity"')
    ..writeln()
    ..writeln('SIGNAL_SPECS = {');
  for (final s in signals) {
    final platforms = s.platforms.isEmpty
        ? 'None'
        : '(${s.platforms.map((p) => '"$p"').join(', ')},)';
    b
      ..writeln('    "${s.name}": {')
      ..writeln('        "tier": "${s.tier}",')
      ..writeln('        "scope": "${s.scope}",')
      ..writeln('        "type": "${s.type}",')
      ..writeln('        "max_len": ${s.maxLen},')
      ..writeln('        "platforms": $platforms,')
      ..writeln('    },');
  }
  b
    ..writeln('}')
    ..writeln()
    ..writeln('STATIC_KEYS = frozenset(')
    ..writeln('    k for k, v in SIGNAL_SPECS.items() if v["scope"] == SCOPE_STATIC')
    ..writeln(')')
    ..writeln('DYNAMIC_KEYS = frozenset(')
    ..writeln('    k for k, v in SIGNAL_SPECS.items() if v["scope"] == SCOPE_DYNAMIC')
    ..writeln(')')
    ..writeln('IDENTITY_KEYS = frozenset(')
    ..writeln('    k for k, v in SIGNAL_SPECS.items() if v["scope"] == SCOPE_IDENTITY')
    ..writeln(')')
    ..writeln()
    ..writeln()
    ..writeln('def allows(key, level):')
    ..writeln('    """Whether `key` is permitted at attribution level `level`.')
    ..writeln()
    ..writeln('    Unknown keys and unknown levels are refused, matching the SDK.')
    ..writeln('    """')
    ..writeln('    spec = SIGNAL_SPECS.get(key)')
    ..writeln('    if spec is None:')
    ..writeln('        return False')
    ..writeln('    if level == "none":')
    ..writeln('        return False')
    ..writeln('    level_rank = TIER_RANK.get(level)')
    ..writeln('    if level_rank is None:')
    ..writeln('        return False')
    ..writeln('    return TIER_RANK[spec["tier"]] <= level_rank');
  return b.toString();
}

// ---------------------------------------------------------------------------

String? _flag(List<String> args, String name) {
  for (final a in args) {
    if (a.startsWith('$name=')) return a.substring(name.length + 1);
  }
  final i = args.indexOf(name);
  if (i >= 0 && i + 1 < args.length) return args[i + 1];
  return null;
}

/// The repo root, found by walking up from this script rather than trusting the
/// working directory — CI and IDEs invoke it from both.
String _repoRoot() {
  var dir = File.fromUri(Platform.script).parent;
  for (var i = 0; i < 6; i++) {
    if (File('${dir.path}/pubspec.yaml').existsSync()) return dir.path;
    dir = dir.parent;
  }
  return Directory.current.path;
}

Never _fail(String message) {
  stderr.writeln('gen_signals: $message');
  exit(2);
}
