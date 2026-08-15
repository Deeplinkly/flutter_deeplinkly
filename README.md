# flutter_deeplinkly

`flutter_deeplinkly` is Deeplinkly's official Flutter SDK for deep linking, deferred deep linking, attribution enrichment, and event tracking across Android and iOS.

## Features

- Deep link resolution via a stream-based API
- Deferred deep linking and install attribution
- Stable Deeplinkly device id retrieval
- Custom user id association for enrichment
- Strongly-validated custom event logging
- Deeplink URL generation with metadata payloads

## Installation

Add the package to your `pubspec.yaml`:

```yaml
dependencies:
  flutter_deeplinkly: ^1.9.2
```

Then run:

```bash
flutter pub get
```

## Platform setup

### Android (`android/app/src/main/AndroidManifest.xml`)

Add **two** intent filters under your `MainActivity` — one for App Links, one
for your custom scheme:

```xml
<activity android:name=".MainActivity">
  <!-- App Links: lets https://links.yourapp.com/abc123 open the app
       directly. Required. -->
  <intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="links.yourapp.com" />
  </intent-filter>

  <!-- Custom scheme: the browser fallback path. -->
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="yourapp" android:host="deeplink" />
  </intent-filter>
</activity>
```

The App Links filter is the one that decides whether deep linking feels native.
Without it, a tapped link always goes to the browser first and comes back
through an `intent://` redirect — which in-app browsers such as Instagram,
Facebook and TikTok frequently block outright, so the link dies even though the
app is installed. `android:autoVerify` has no effect on a custom scheme; it
only applies to `http`/`https`.

For verification to pass, the dashboard needs your package name **and** your
SHA-256 signing fingerprint — Deeplinkly serves
`/.well-known/assetlinks.json` from them, and returns 404 if either is missing.
With Play App Signing, use the fingerprint from **Play Console → Setup → App
signing**, not your upload keystore. Check it with:

```bash
adb shell pm get-app-links <your.package.name>
```

See [docs/FLUTTER_SDK.md](docs/FLUTTER_SDK.md#verifying-app-links) for the full
walkthrough.

Add Deeplinkly API key metadata:

```xml
<application ...>
  <meta-data
      android:name="com.deeplinkly.sdk.api_key"
      android:value="your_api_key_here" />
</application>
```

### iOS (`ios/Runner/Info.plist`)

Register URL schemes:

```xml
<key>CFBundleURLTypes</key>
<array>
  <dict>
    <key>CFBundleURLSchemes</key>
    <array>
      <string>yourapp</string>
    </array>
  </dict>
</array>
```

Add Deeplinkly API key:

```xml
<key>DeeplinklyApiKey</key>
<string>your_api_key_here</string>
```

Add your link domains. These gate the deferred deep link check: the SDK only
resolves pasteboard URLs whose host matches one of these (subdomains included).
Without this key, deferred deep linking is limited to `deeplinkly.com` links.

```xml
<key>DeeplinklyLinkDomains</key>
<array>
  <string>yourbrand.deeplinkly.com</string>
  <string>links.yourbrand.com</string>
</array>
```

### Deferred deep linking on iOS

iOS has no install-referrer API, so a link tapped before install survives the
App Store trip on the pasteboard. Choose how you read it back:

**Recommended — a system paste button, no banner.** Because the user taps it,
iOS shows no "Pasted from…" prompt:

```dart
DeeplinklyPasteButton(
  onPasted: (handled) => setState(() => _showPasteButton = !handled),
  fallback: const SizedBox.shrink(),
)
```

Requires iOS 16+; renders `fallback` elsewhere, so it is safe to place
unconditionally.

**Also on by default: an automatic read.** The SDK reads the pasteboard once on
first launch, which shows the system paste banner — but only when a URL is
actually on the clipboard. A user whose clipboard is empty, or holds text that
is not a URL, sees nothing.

To turn it off — in `Info.plist`, not from Dart, since the read happens before
any Dart runs:

```xml
<key>DeeplinklyCheckPasteboardOnInstall</key>
<false/>
```

Use `willShowPasteboardBanner()` and `checkPasteboardNow()` if you would rather
drive it yourself and explain the prompt first.

### Attribution levels

```dart
await FlutterDeeplinkly.setAttributionLevel(DeeplinklyAttributionLevel.reduced);
```

`full` (default) → `reduced` (no hardware signals or ad IDs) → `minimal` (no
device data at all) → `none` (no reporting). Deep links resolve and deliver at
every level. Set `DeeplinklyAttributionLevel` in `Info.plist` or the
`com.deeplinkly.sdk.attribution_level` meta-data to start restricted before any
Dart runs.

For a plain off switch rather than a middle ground:

```dart
await FlutterDeeplinkly.setTrackingEnabled(false);
```

No enrichment, events or error reports are sent while disabled, pending report
retries are deleted, and the iOS pasteboard read is skipped. Deep links still
resolve and still reach `onResolved`, but functional requests omit the stable
Deeplinkly ID and custom user ID. It persists across launches and wins over
`setAttributionLevel` — `getAttributionLevel()` reports `none` while it is off.

For a deletion request, remove all locally stored Deeplinkly identifiers,
attribution, device/session state, and queues:

```dart
await FlutterDeeplinkly.resetPrivacyData();
```

The reset leaves tracking disabled. Re-enable it only after a fresh opt-in.

### iOS Universal Links

Add an **Associated Domains** capability in Xcode with an entry per link domain:

```
applinks:yourbrand.deeplinkly.com
applinks:links.yourbrand.com
```

Deeplinkly serves the matching `apple-app-site-association` for you at
`https://<your-link-domain>/.well-known/apple-app-site-association` once the
domain is verified and the project's iOS bundle ID and team ID are set in the
dashboard.

No AppDelegate changes are needed — the plugin registers for both the
`UIApplicationDelegate` and `UIScene` link callbacks itself.

> **Privacy:** the SDK ships its own `PrivacyInfo.xcprivacy` and **never
> triggers an App Tracking Transparency prompt** — it only reads the status your
> own prompt produced. By default it collects no IDFA and needs no
> `NSUserTrackingUsageDescription`. IDFA collection is opt-in via the
> `DeeplinklyEnableIDFA` Info.plist key; see
> [docs/FLUTTER_SDK.md](docs/FLUTTER_SDK.md#collecting-the-idfa-opt-in) for what
> enabling it obliges you to declare.

## Quick start

Initialize before `runApp()` and subscribe to incoming deep links:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  FlutterDeeplinkly.init();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  @override
  void initState() {
    super.initState();
    // Every link arrives as {click_id, params} on both platforms;
    // `params` holds the link's own parameters.
    FlutterDeeplinkly.instance.deepLinkStream.listen((payload) {
      final params = payload['params'] as Map? ?? {};
      debugPrint("Deep link ${payload['click_id']} -> $params");
      // Route user to relevant in-app destination.
    });
  }

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      home: Scaffold(body: Center(child: Text("Deeplinkly ready"))),
    );
  }
}
```

## Core API

### Attribution and identity

```dart
final attribution = await FlutterDeeplinkly.getInstallAttribution();
final deeplinklyId = await FlutterDeeplinkly.getDeeplinklyId();
await FlutterDeeplinkly.setUserId("user_123");
```

### Event logging

```dart
final ok = await FlutterDeeplinkly.logEvent(
  "purchase",
  parameters: {
    "order_id": "ord_42",
    "amount": 49.99,
    "currency": "USD",
    "is_first_purchase": true,
  },
);
```

### Link generation

```dart
import 'package:flutter_deeplinkly/models/deeplinkly.dart';

final result = await FlutterDeeplinkly.generateLink(
  content: const DeeplinklyContent(
    canonicalIdentifier: "product/sku_42",
    title: "Pro Plan",
    description: "Upgrade to Pro",
    metadata: {"plan": "pro"},
  ),
  options: const DeeplinklyLinkOptions(
    channel: "email",
    feature: "upgrade_campaign",
    tags: ["spring", "sale"],
  ),
);

if (result.success) {
  debugPrint("Generated link: ${result.url}");
}
```

## Validation rules for `logEvent`

- Event name must be non-empty and at most 64 chars
- At most 25 parameters
- Parameter keys must be non-empty and at most 64 chars
- Keys starting with `_dl_` are reserved for the SDK and rejected
- Supported value types: `String`, `num`, `bool`, `List`, `Map`
- `String` values must be at most 256 chars
- `List` and `Map` values are stored as compact JSON, and it is that encoded form
  that must be at most 256 chars

`num` and `bool` values keep their JSON types end to end — `49.99` is stored as a
number, not `"49.99"`.

## Testing

Trigger Android install referrer test payload:

```bash
adb shell am broadcast -a com.android.vending.INSTALL_REFERRER -n your.package.name/com.google.android.gms.measurement.AppMeasurementInstallReferrerReceiver --es "referrer" "utm_source=test&utm_medium=deeplink&utm_campaign=demo"
```

## Documentation

- Website docs: <https://www.deeplinkly.com/docs/sdk/flutter>
- Repo docs: [`docs/FLUTTER_SDK.md`](docs/FLUTTER_SDK.md)
- AI skill (autonomous integrator): [`.cursor/skills/flutter-sdk/SKILL.md`](.cursor/skills/flutter-sdk/SKILL.md)

## Support

- Email: <support@deeplinkly.com>
- Issues: <https://github.com/deeplinkly/flutter_deeplinkly/issues>

## License

MIT License. See [`LICENSE`](LICENSE).
