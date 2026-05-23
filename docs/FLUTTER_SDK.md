# Flutter SDK Documentation

This page documents Deeplinkly's Flutter SDK integration flow and runtime API.

## Install

```yaml
dependencies:
  flutter_deeplinkly: ^1.7.0
```

```bash
flutter pub get
```

## Configure Android

In `android/app/src/main/AndroidManifest.xml`:

```xml
<activity android:name=".MainActivity">
  <intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="yourapp" android:host="deeplink" />
  </intent-filter>
</activity>

<application ...>
  <meta-data
      android:name="DEEPLINKLY_API_KEY"
      android:value="your_api_key_here" />
</application>
```

## Configure iOS

In `ios/Runner/Info.plist`:

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

<key>DEEPLINKLY_API_KEY</key>
<string>your_api_key_here</string>
```

## Initialize

```dart
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  FlutterDeeplinkly.init();
  runApp(const MyApp());
}
```

## Handle deep links

```dart
FlutterDeeplinkly.instance.deepLinkStream.listen((params) {
  debugPrint("Deep link payload: $params");
});
```

## Use attribution and identity APIs

```dart
final attribution = await FlutterDeeplinkly.getInstallAttribution();
final deeplinklyId = await FlutterDeeplinkly.getDeeplinklyId();
await FlutterDeeplinkly.setUserId("user_123");
```

## Log events

```dart
await FlutterDeeplinkly.logEvent(
  "purchase",
  parameters: {
    "order_id": "ord_42",
    "amount": 49.99,
    "currency": "USD",
  },
);
```

Validation constraints:

- Event name max length: 64
- Max custom params: 25
- Param key max length: 64
- Param string value max length: 256

## Generate Deeplinkly links

```dart
final result = await FlutterDeeplinkly.generateLink(
  content: const DeeplinklyContent(
    canonicalIdentifier: "product/sku_42",
    title: "Pro Plan",
    metadata: {"plan": "pro"},
  ),
  options: const DeeplinklyLinkOptions(
    channel: "email",
    feature: "upgrade_campaign",
  ),
);
```

## AI Skill

The project includes an integration skill at:

- `.cursor/skills/flutter-sdk/SKILL.md`

Use that skill in Cursor to run autonomous, goal-driven integration:

- It infers intent (setup, routing, attribution, analytics, link generation).
- It gathers context directly from project files before asking for missing inputs.
- It executes implementation + validation end-to-end with guardrails.
- It includes a troubleshooting decision tree for common failure modes.

This allows an AI agent to implement Deeplinkly with very little manual guidance.
