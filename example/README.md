# flutter_deeplinkly example

A minimal runnable integration of the Deeplinkly Flutter SDK, used to exercise
the deep link and deferred deep link paths on a real device.

## Run it

```bash
flutter pub get
flutter run
```

`lib/main.dart` initializes the SDK and subscribes to
`FlutterDeeplinkly.instance.deepLinkStream`, printing every payload it receives.
That is the whole app — it is a harness for the link paths, not a tour of the
API. For the attribution, identity, event, and link-generation APIs, see the
[SDK documentation](../docs/FLUTTER_SDK.md).

## What is already wired up

**Android** — `android/app/src/main/AndroidManifest.xml`

- Custom scheme intent filter (`deeplinkly://`)
- `com.deeplinkly.sdk.api_key` `<meta-data>`

> The example does **not** declare an App Links intent filter, because HTTPS
> App Links only verify against a real domain you control. For your own app,
> add one with `android:autoVerify="true"` listing each HTTPS host — see the
> Android setup guide in the Deeplinkly dashboard docs.

**iOS** — `ios/Runner/Info.plist` and `ios/Runner/Runner.entitlements`

- `DeeplinklyApiKey`
- `DeeplinklyLinkDomains` — the host allowlist for the deferred pasteboard read
- `applinks:example.deeplinkly.com` in the entitlements file

> **Note:** the entitlements file is present but is **not** attached to the
> Xcode target (`CODE_SIGN_ENTITLEMENTS` is unset), because the example is
> built unsigned in CI. To exercise Universal Links, open
> `ios/Runner.xcworkspace`, select the Runner target → **Signing &
> Capabilities**, and add the **Associated Domains** capability. Xcode will
> pick up the existing file.

## Point it at your own project

1. Replace the API key in both `AndroidManifest.xml` and `Info.plist` with your
   key from the Deeplinkly dashboard.
2. Replace `example.deeplinkly.com` with your link domain in **both**
   `DeeplinklyLinkDomains` and the entitlements file — they must agree — and in
   your Android App Links intent filter if you add one.
3. On the dashboard, set your iOS bundle ID, **team ID**, and App Store ID, then
   confirm `https://<your-domain>/.well-known/apple-app-site-association`
   returns JSON rather than 404.

## Testing the deferred flow

Deferred deep linking on iOS goes through the pasteboard and **cannot be tested
on the Simulator** — there is no App Store there. On a physical device without
the app installed: open a link in Safari, tap through the interstitial (there is
no auto-redirect, by design), install, and launch. The link should arrive on
`deepLinkStream` with its original UTM parameters.

The read is once-per-install and clears the pasteboard, so **reinstall** to
retest — relaunching will not repeat it.

On Android, the Install Referrer API only works for builds delivered by Play, so
test through internal testing rather than a sideloaded APK.

## Docs

- [SDK README](../README.md)
- [Full SDK documentation](../docs/FLUTTER_SDK.md)
