# Flutter SDK Documentation

This page documents Deeplinkly's Flutter SDK integration flow and runtime API.

## Install

```yaml
dependencies:
  flutter_deeplinkly: ^1.9.2
```

```bash
flutter pub get
```

## Configure Android

In `android/app/src/main/AndroidManifest.xml`:

```xml
<activity android:name=".MainActivity">
  <!-- App Links. This is the one that matters: it lets a tap on
       https://links.yourapp.com/abc123 open the app directly. Without it
       every link detours through the browser, and in-app browsers that
       block intent:// URLs (Instagram, Facebook, TikTok) never reach your
       app at all — even when it is installed.
       autoVerify only does anything on http/https. -->
  <intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="links.yourapp.com" />
  </intent-filter>

  <!-- Custom scheme. The browser fallback path uses this, so keep it —
       but it is a fallback, not a substitute for the filter above. -->
  <intent-filter>
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

  <!-- Optional, but set it if the app App Links any host besides its
       Deeplinkly link domain. Comma separated. See below. -->
  <meta-data
      android:name="com.deeplinkly.sdk.link_domains"
      android:value="links.yourapp.com" />
</application>
```

Replace `links.yourapp.com` with your Deeplinkly link domain, and `yourapp`
with the URI scheme you set in the dashboard.

### Which links the SDK claims

This rule is the same on both platforms. It is described here because the
Android meta-data is above; the iOS key is `DeeplinklyLinkDomains`, set in
`Info.plist` — see [Configure iOS](#configure-ios).

A link that came through the redirect carries a `click_id`, and the SDK acts on
that whatever the scheme. The ambiguous case is the App Link / Universal Link
bypass, where the OS routes `https://links.yourapp.com/<code>` straight to the
app and the first path segment is the only thing there is to resolve on.

So the rule is:

- **Custom-scheme URLs without a `click_id` are ignored.** Your own routes
  (`yourapp://settings/notifications`) are yours; the SDK will not resolve them.
- **http(s) URLs** are resolved by code. If the link-domains list is set, only
  those hosts are; without it every https link the app handles is, which is
  fine for an app whose only App Link filter is its link domain.

Set the link domains if you App Link anything else — a marketing site, say — or
`https://www.yourapp.com/pricing` will be resolved as code `pricing`.

### Verifying App Links

Android checks `https://<your-link-domain>/.well-known/assetlinks.json` on
install. Deeplinkly serves that file for you, but only once the dashboard has
both your **package name** and your **SHA-256 signing certificate
fingerprint** — with either missing the endpoint returns 404 and verification
silently fails.

Get the fingerprint from your release keystore:

```bash
keytool -list -v -keystore <your-keystore> -alias <your-alias> | grep SHA256
```

If you use Play App Signing, take the fingerprint from **Play Console → Test
and release → Setup → App signing**, not from your upload keystore — Google
re-signs the APK, so the upload fingerprint will not match what ships.

Confirm the file is live and the verification passed:

```bash
curl https://links.yourapp.com/.well-known/assetlinks.json
adb shell pm get-app-links <your.package.name>
```

The second command should report `verified` for your domain. `none` or
`legacy_failure` means the fingerprint does not match or the file is not
reachable.

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

<!-- Your Deeplinkly link domains. Subdomains count. Two jobs: the hosts a
     deferred link may be read from, and the hosts whose first path segment
     may be read as a link code — see "Which links the SDK claims" above.
     Set it if the app Universal Links any host besides its link domain. -->
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
taps through to the store, and the SDK reads it back on first launch.

There are two ways to read it back. **Pick one.**

#### Option A — `DeeplinklyPasteButton` (recommended, no banner)

A system paste button rendered in your widget tree. Because the user taps it
themselves, iOS treats the tap as the grant and shows **no "Pasted from…"
banner at all**.

```dart
DeeplinklyPasteButton(
  onPasted: (handled) => setState(() => _showPasteButton = !handled),
  fallback: const SizedBox.shrink(),
)
```

Put it on a first-run screen next to something like *"Tapped a link to get
here? Restore where you left off."* The recovered link arrives on
`deepLinkStream` exactly like any other; `onPasted` only tells you whether the
pasted content was one of your links, so you can hide the button or explain
that it was not.

Requires iOS 16+. Renders `fallback` on Android and on older iOS, so it is safe
to place unconditionally.

#### Option B — automatic read (on by default, shows the banner)

**On by default.** You do not need to enable it.

To turn it off:

```xml
<key>DeeplinklyCheckPasteboardOnInstall</key>
<false/>
```

Do that in `Info.plist`, not from Dart — the read happens during plugin
registration, before any Dart runs, so `setCheckPasteboardOnInstall(false)`
arrives too late to prevent the first one.

Turning it on from Dart at runtime reads immediately rather than waiting for a
next launch the pasteboard may not survive to.

To explain the prompt before it appears, turn the automatic read off in
`Info.plist` and drive it yourself:

```dart
if (await FlutterDeeplinkly.willShowPasteboardBanner()) {
  await showMyPrimingDialog();   // "we can restore where you left off"
  await FlutterDeeplinkly.checkPasteboardNow();
}
```

`willShowPasteboardBanner` reads no content and shows no banner itself.

#### Either way

- The visitor must **tap through** the interstitial — there is no auto-redirect
  on iOS. Safari does not allow a clipboard write without a user gesture, so a
  timed redirect could never carry one.
- The automatic read happens once per install, guarded by a persisted flag, and
  probes the pasteboard's *types* first with `hasURLs`, which is banner-free.
  The banner appears only when a URL is actually there. This pairs with the
  interstitial writing a `text/uri-list` clipboard item, which WebKit puts on
  the pasteboard as `public.url`. On iOS 16+ a second banner-free probe,
  `detectPatterns(.probableWebURL)`, catches links that arrived as plain text
  from the interstitial's `writeText`/`execCommand` fallbacks.
- The banner is **not** limited to your own links. Any URL on the clipboard
  triggers the read; the SDK then discards anything whose host is not in
  `DeeplinklyLinkDomains`, but the banner has already shown. A user who copied
  a news article before opening your app gets a prompt for nothing. A user with
  no URL copied — plain text, a phone number, an empty clipboard — sees
  nothing.
- Both paths are skipped entirely when tracking is disabled via
  `setTrackingEnabled(false)`.
- Only URLs matching `DeeplinklyLinkDomains` are read and resolved; anything
  else on the pasteboard is ignored and left untouched. The automatic read
  clears your own link from the pasteboard once the resolve is durably queued;
  the paste button leaves the pasteboard alone, since the user pasted
  deliberately.
- The resolved click is stamped `attribution_source = "clipboard"`, not
  `install_referrer` — that API does not exist on iOS.
- If the first launch is offline the pending resolve is persisted and retried on
  the next launch, so an offline install is not lost.

### Attribution levels

For consent flows that need a middle ground between "track" and "don't":

```dart
await FlutterDeeplinkly.setAttributionLevel(DeeplinklyAttributionLevel.reduced);
```

| Level | What is sent |
|---|---|
| `full` | Everything. The default |
| `reduced` | Drops every high-entropy hardware signal: screen geometry, model, CPU, the local IP, the WebView user agent, the advertising ID / Android ID / IDFA / IDFV. Keeps the coarse context campaign reporting reads — locale, timezone, OS and app version |
| `minimal` | Only the install id, app build, and the link being reported on. Nothing describing the device |
| `none` | No enrichment at all. Links still resolve and still deliver |

Each level is a strict subset of the one above. Deep link delivery works at
every level, including `none` — this restricts reporting, not functionality.
Resolving a link never sends anything describing the device, at any level.

[**docs/SIGNALS.md**](SIGNALS.md) is the field-by-field reference: every signal
the SDK can send, its level, and which platforms report it. It is generated from
the same catalogue the SDK compiles against, so it cannot drift from what is
actually sent.

Our `reduced` is stricter than the equivalent tier in some other SDKs, which
drop only the advertising identifiers and keep shipping screen geometry, local
IP and the user agent. Here, a level below `full` drops all of it.

To start restricted before any Dart runs (enrichment can be sent during plugin
registration), set it natively:

```xml
<key>DeeplinklyAttributionLevel</key>
<string>reduced</string>
```

```xml
<meta-data android:name="com.deeplinkly.sdk.attribution_level"
           android:value="reduced" />
```

The off switch is separate, and wins over any level set here:

```dart
await FlutterDeeplinkly.setTrackingEnabled(false);
```

It behaves as `none` — `getAttributionLevel()` reports `none` while it is off,
whatever was set — and additionally stops event and error reporting and skips
the iOS pasteboard read. Deep links still resolve and still deliver. It persists
across launches, and is enabled by default.

The SDK does **not** do probabilistic ("fingerprint") matching. Device signals
are collected for reporting, never to derive an identifier that links a click to
an install — matching is deterministic, on the click id or the install referrer.
Resolving a link sends its id and nothing describing the device, at every
attribution level. The trade-off on iOS: a visitor who does not tap through the
interstitial has no deferred attribution.

Nothing describing the device is collected at click time, in the browser or the
interstitial. All of it is collected in-app, after install.

### Privacy manifest

The SDK ships `PrivacyInfo.xcprivacy` in its resource bundle, so you do not need
to declare its API usage yourself. Declared required-reason APIs are
`UserDefaults` (`CA92.1`), system boot time (`35F9.1`), file timestamps
(`C617.1`, for the install date) and disk space (`E174.1`, for the storage
tier).

By default `NSPrivacyTracking` is `false` and the SDK reads no IDFA, so it needs
no `NSUserTrackingUsageDescription`. **The SDK never calls
`requestTrackingAuthorization` in any configuration** — it reads the status your
own prompt produced. It does report `att_status` so you can see the consent
state of your install base, which is not itself tracking and triggers no prompt.

### Collecting the advertising ID on Android (opt-in)

The SDK compiles against `play-services-ads-identifier` but does **not** bundle
it. That library's own manifest declares
`com.google.android.gms.permission.AD_ID`, so bundling it would add that
permission to every app embedding this SDK — including apps under Play's
Families policy, which may not collect an advertising ID at all.

To report `advertising_id`, add the dependency to your app:

```groovy
// android/app/build.gradle
dependencies {
    implementation 'com.google.android.gms:play-services-ads-identifier:18.2.0'
}
```

Without it the SDK reports no `advertising_id` and everything else works
unchanged — attribution still resolves deterministically on the click id and
the Play install referrer. `unidentified_device` tells you when a payload
carries no durable identifier at all.

The SDK declares only `INTERNET`. `ACCESS_NETWORK_STATE` is likewise not
declared: if your app already holds it the SDK reports `connection_type`, and
if not, that one field is omitted.

### Collecting the IDFA (opt-in)

Set `DeeplinklyEnableIDFA` to `true` in your `Info.plist`. The IDFA is then
reported *only* while ATT status is already `authorized`; if you never prompt,
nothing is collected and that is the correct outcome rather than a bug.

Enabling it makes your app a tracking app, so you must also:

1. Merge `Resources/IDFA/PrivacyInfo.xcprivacy` from the plugin into your app's
   own `PrivacyInfo.xcprivacy`.
2. Add `NSUserTrackingUsageDescription` to your `Info.plist`.
3. Call `ATTrackingManager.requestTrackingAuthorization` yourself, wherever in
   your flow it belongs.

That manifest is a template rather than something the pod bundles, deliberately:
Xcode aggregates every bundled manifest into the containing app's privacy
report, so shipping `NSPrivacyTracking = true` would declare tracking on behalf
of every app embedding the SDK — including those that never enabled IDFA and
never prompt.

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
