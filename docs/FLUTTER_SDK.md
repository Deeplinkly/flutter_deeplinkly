# Flutter SDK Documentation

This page documents Deeplinkly's Flutter SDK integration flow and runtime API.

## Install

```yaml
dependencies:
  flutter_deeplinkly: ^1.8.0
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
      android:name="com.deeplinkly.sdk.api_key"
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

<key>DeeplinklyApiKey</key>
<string>your_api_key_here</string>

<!-- Hosts the SDK will accept a deferred deep link from. Subdomains count. -->
<key>DeeplinklyLinkDomains</key>
<array>
  <string>yourbrand.deeplinkly.com</string>
</array>
```

Then add an **Associated Domains** capability with `applinks:yourbrand.deeplinkly.com`
for each link domain. The plugin registers for the `UIApplicationDelegate` and
`UIScene` link callbacks itself, so no AppDelegate changes are required.

### Deferred deep linking on iOS

iOS has no install-referrer API, so the link survives an App Store install via
the pasteboard: the Deeplinkly interstitial copies the link when the visitor
taps through to the store, and the SDK reads it back once, on first launch.

Consequences worth knowing:

- The visitor must **tap through** the interstitial — there is no auto-redirect
  on iOS. Safari does not allow a clipboard write without a user gesture, so a
  timed redirect could never carry one.
- iOS shows its standard "Pasted from Safari" banner on that one read. The SDK
  probes the pasteboard's *metadata* first — `detectPatterns(.probableWebURL)`
  on iOS 16+, `hasStrings` below that — which is banner-free, so the banner
  only appears when there is plausibly a URL to read.
- The read happens once per install, guarded by a persisted flag. It is skipped
  entirely when tracking is disabled via `setTrackingEnabled(false)`.
- Only URLs matching `DeeplinklyLinkDomains` are read and resolved; anything
  else on the pasteboard is ignored and left untouched. Your own link is cleared
  from the pasteboard, but only after the resolve has been durably queued.
- The resolved click is stamped `attribution_source = "clipboard"`, not
  `install_referrer` — that API does not exist on iOS.
- If the first launch is offline the pending resolve is persisted and retried on
  the next launch, so an offline install is not lost.

The SDK does **not** do probabilistic ("fingerprint") matching on iOS. Apple's
Developer Program License Agreement and App Review Guideline 5.1.2 prohibit
deriving a device identifier from device signals. The trade-off: a visitor who
does not tap through the interstitial has no deferred attribution.

### Privacy manifest

The SDK ships `PrivacyInfo.xcprivacy` in its resource bundle, so you do not need
to declare its API usage yourself. `NSPrivacyTracking` is `false`; the SDK links
neither `AppTrackingTransparency` nor `AdSupport` and never reads the IDFA, so
it triggers **no ATT prompt** and needs no `NSUserTrackingUsageDescription`.
Declared required-reason APIs are `UserDefaults` (`CA92.1`) and system boot time
(`35F9.1`).

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
FlutterDeeplinkly.instance.deepLinkStream.listen((payload) {
  final params = payload['params'] as Map? ?? {};
  debugPrint("Deep link ${payload['click_id']} -> $params");
});
```

Every deep link arrives in the same envelope on both platforms:

```dart
{
  'click_id': 'ab12…',            // null if the backend did not recognise the click
  'params': {'screen': 'home'},   // the link's own parameters
  'probability': 0.92,            // deferred-match confidence, when the backend sends it
}
```

`params` carries the link's parameters whether they came back from the backend
or, when it could not be reached, from the URL itself - so a single read path
covers both.

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
- Max custom params: 25 (the SDK's own `_dl_*` keys do not count towards this,
  and passing a key with that prefix is rejected)
- Param key max length: 64
- Param string value max length: 256
- `List`/`Map` values are stored as compact JSON; the 256 limit applies to that
  encoded form

`num` and `bool` values keep their JSON types end to end — `49.99` is stored as a
number, not `"49.99"`.

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
    tags: ["spring", "sale"],
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
