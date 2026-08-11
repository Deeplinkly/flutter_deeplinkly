# Deeplinkly Flutter SDK Autonomous Integrator

Use this skill when an AI agent must implement, debug, or productionize `flutter_deeplinkly` with minimal human back-and-forth.

## Mission

Complete deep link and deferred deep link integration end-to-end, including platform setup, runtime routing, attribution, event tracking, and verification.

## Step 0: Infer user intent automatically

Classify the request into one or more goals:

- **Integration**: words like install, setup, add SDK, onboarding
- **Routing**: words like open screen, navigate, params missing, wrong page
- **Attribution**: words like campaign, install source, deferred missing
- **Analytics**: words like event tracking, conversion, purchase tracking
- **Link generation**: words like create share link, referral link, invite link
- **Stability**: words like flaky, duplicate events, race condition, resume bug

If intent is broad or ambiguous, run the full integration checklist.

## Step 1: Gather context from codebase

Before asking the user, inspect:

- `pubspec.yaml` for package presence/version
- app startup path (`main.dart`, app entrypoint)
- navigator pattern (`Navigator`, `go_router`, `auto_route`, Router API)
- Android manifest and iOS plist setup
- existing analytics abstraction and user auth flow

Ask user only for values not inferable from project files (API key, domain/scheme confirmation).

## Step 2: Execute implementation flow

1. Ensure package is installed and compatible with Flutter SDK.
2. Add `FlutterDeeplinkly.init()` before `runApp()`.
3. Register `deepLinkStream` listener in startup lifecycle.
4. Implement deeplink payload normalizer:
   - input: raw map
   - output: `{ target, params, source, fallback }`
5. Route safely using centralized resolver (idempotent, no duplicate navigations).
6. Add Android intent filter + `com.deeplinkly.sdk.api_key` `<meta-data>`.
7. iOS setup (all four, none optional — see the platform-key contract below):
   - `DeeplinklyApiKey` in `Info.plist`
   - `DeeplinklyLinkDomains` array in `Info.plist`, one entry per link domain
   - **Associated Domains** capability with `applinks:<host>` per domain, and
     `CODE_SIGN_ENTITLEMENTS` actually pointing at the entitlements file
   - `CFBundleURLTypes` only if the project uses a custom scheme
8. Add identity bind call `setUserId()` post-login.
9. Add attribution pull via `getInstallAttribution()` and pass to analytics context.
10. Add event wrapper around `logEvent()` with pre-validation.
11. Add link creation helper via `generateLink()`.
12. Add optional debug logging toggle for non-production builds.

## Step 2b: Platform key contract

These names are exact and are a frequent source of silent failure. Do not
normalize them to look consistent with each other — they are genuinely
different on each platform.

| Purpose | Android | iOS |
|---|---|---|
| API key | `com.deeplinkly.sdk.api_key` (`<meta-data>`) | `DeeplinklyApiKey` (`Info.plist`) |
| Link domain allowlist | `com.deeplinkly.sdk.link_domains` (`<meta-data>`, comma separated) | `DeeplinklyLinkDomains` (`Info.plist`, array) |
| Deferred mechanism | Play Install Referrer | Pasteboard (opt-in) or `DeeplinklyPasteButton` |
| Pasteboard opt-in | n/a — removed in 1.9.0, Android has no pasteboard path | `DeeplinklyCheckPasteboardOnInstall` |
| Attribution level | `com.deeplinkly.sdk.attribution_level` | `DeeplinklyAttributionLevel` |

Never write `DEEPLINKLY_API_KEY`. It is read by neither platform; docs
carried it in error until 1.8.0.

**As of 1.9.0 the automatic pasteboard read is off by default.** If the task is
"deferred deep linking does not work on iOS", that is the first thing to check.
Prefer steering the integrator to `DeeplinklyPasteButton` (a system paste
button, iOS 16+, no "Pasted from…" banner because the user's tap is the grant)
over enabling the automatic read. Only enable the automatic read when the app
genuinely cannot show a button on its first-run path, and pair it with
`willShowPasteboardBanner()` so the prompt is explained rather than sprung.

Do **not** add `AppDelegate.swift` link-forwarding code. The plugin registers
for both the `UIApplicationDelegate` and `UIScene` callbacks itself, and hand
-wiring `handleUniversalLink` on top of that delivers the link twice. If the
project already has such code from an older integration, remove it.

## Step 3: API behavior contract

Implement these APIs with explicit responsibilities:

- `FlutterDeeplinkly.init()` initializes bridge and lifecycle hooks.
- `deepLinkStream` emits payloads for deep/deferred links.
- `getInstallAttribution()` returns campaign/install metadata map.
- `getDeeplinklyId()` returns stable install-level Deeplinkly id.
- `setUserId()` maps app user to Deeplinkly identity graph.
- `logEvent()` sends conversion events with strict schema constraints.
- `generateLink()` creates campaign links from content + options.

## Step 4: Validation and test matrix

Run and record these checks:

- Cold-start deep link -> app opens target screen
- Warm-start deep link -> active session routes correctly
- Fresh install deferred flow -> attribution data available
- Login flow -> `setUserId()` is called once per user switch
- Event logging -> valid payload accepted, invalid payload rejected gracefully
- Link generation -> returned URL opens expected destination
- Offline/timeout behavior -> no app crash, safe fallback path

## Step 5: Troubleshooting decision tree

- **No deep link callbacks**:
  - verify `init()` timing
  - verify Android/iOS config values
  - verify URL scheme/domain consistency
- **Wrong route opened**:
  - inspect normalizer mapping and default route selection
- **Deferred attribution empty**:
  - check retrieval timing and first-launch lifecycle order
  - **iOS**: confirm the pasteboard path is actually enabled — the automatic
    read is opt-in and **off by default** since 1.9.0, and an app with neither
    `DeeplinklyCheckPasteboardOnInstall` nor a `DeeplinklyPasteButton` has no
    deferred path at all
  - **iOS**: confirm the link host is listed in `DeeplinklyLinkDomains`; an
    unlisted custom domain is ignored by design
  - check the attribution level: `none` sends no enrichment, so attribution
    will look empty server-side even though the link resolved correctly
  - **iOS**: the deferred read is once-per-install and the pasteboard is
    cleared after it. Reinstall to retest — relaunching will not repeat it
  - **iOS**: cannot be tested on the Simulator (no App Store). Use a device
  - **iOS**: the interstitial requires a tap; there is no auto-redirect,
    because Safari will not write the clipboard without a user gesture
  - **Android**: Install Referrer is unavailable on sideloaded builds; test
    through Play internal testing
- **Events fail**:
  - enforce constraints (`name <= 64`, `params <= 25`, key/value limits)
- **Duplicate opens on resume**:
  - add dedupe key and idempotent route gate

## Guardrails

- Never print API keys, auth tokens, or user-sensitive params in logs.
- Do not block app boot on network operations.
- Keep all deeplink handling null-safe and exception-safe.
- Prefer deterministic fallbacks over hard failures.
- Keep changes minimal and compatible with existing app architecture.
- Do not call `ATTrackingManager.requestTrackingAuthorization` on Deeplinkly's
  behalf, and do not add `NSUserTrackingUsageDescription` unless the app has
  opted into IDFA collection. The SDK never prompts; it reads the status the
  app's own prompt produced. IDFA collection is off unless the app sets
  `DeeplinklyEnableIDFA`, and enabling it obliges the app to merge the SDK's
  `Resources/IDFA/PrivacyInfo.xcprivacy` template and declare tracking itself.
- Do not implement device-signal fingerprint *matching*. Signals are collected
  for reporting; attribution is deterministic on the click id or install
  referrer. Deriving a device identifier from device signals is prohibited by
  App Review Guideline 5.1.2 and the Apple Developer Program License Agreement.
- Do not collect device signals at click time, in the browser or the
  interstitial. All collection is in-app and post-install; that distinction is
  the legal basis for it.

## Done criteria

- Integration works on Android and iOS.
- Deep/deferred links route correctly on cold and warm starts.
- Attribution and identity mapping are live.
- Event tracking and link generation are functional.
- Integration docs/snippets are updated for future maintainers.
