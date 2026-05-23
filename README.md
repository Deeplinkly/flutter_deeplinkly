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
  flutter_deeplinkly: ^1.7.0
```

Then run:

```bash
flutter pub get
```

## Platform setup

### Android (`android/app/src/main/AndroidManifest.xml`)

Add your deep link intent filter under your `MainActivity`:

```xml
<activity android:name=".MainActivity">
  <intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="yourapp" android:host="deeplink" />
  </intent-filter>
</activity>
```

Add Deeplinkly API key metadata:

```xml
<application ...>
  <meta-data
      android:name="DEEPLINKLY_API_KEY"
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
<key>DEEPLINKLY_API_KEY</key>
<string>your_api_key_here</string>
```

For universal links, also configure associated domains and host your `apple-app-site-association`.

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
    FlutterDeeplinkly.instance.deepLinkStream.listen((params) {
      debugPrint("Deep link payload: $params");
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
- String parameter values must be at most 256 chars
- Supported value types: `String`, `num`, `bool`, `List`, `Map`

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

