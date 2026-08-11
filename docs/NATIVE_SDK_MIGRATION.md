# Native SDK migration — status and handoff

Living status doc for the extraction of the platform SDKs out of the Flutter
plugin. **Android is done and published. iOS has its tests and its delivery
seam; no iOS code has moved to a new repo yet.**

Last updated: 2026-08-11.

**Picking this up fresh? Read `NEXT.md` in the repo root** — it is the ordered
plan for the remaining iOS work. This file is the reference behind it.

---

## Why this exists

Deeplinkly ships a Flutter plugin whose Android and iOS folders held all the
real logic — deep link resolution, install referrer, attribution, queues,
retries, device signals, networking. Shipping native SDKs by copying that code
would fork it permanently. So each platform's implementation is being extracted
into its own repo and published, and the Flutter plugin reduced to a thin
bridge over it. One implementation, three distribution channels.

## Repos and packages

| Artifact | Repo | Package | Status |
|---|---|---|---|
| Native Android SDK | `Deeplinkly/android_deeplinkly` | `com.deeplinkly.android_deeplinkly` | **done**, `com.deeplinkly:deeplinkly-android:1.0.0` |
| Flutter plugin | `Deeplinkly/flutter_deeplinkly` | `com.deeplinkly.flutter_deeplinkly` | **done**, consumes the above |
| Native iOS SDK | (not created) | `com.deeplinkly.ios_deeplinkly` | **not started** |

Local checkouts are siblings under `~/StudioProjects/`. Several tools assume
that layout (see [Cross-repo tooling](#cross-repo-tooling)).

The Flutter bridge's package **must not move** — `pubspec.yaml` pins
`pluginClass: FlutterDeeplinklyPlugin` under `com.deeplinkly.flutter_deeplinkly`.

## Current state

`flutter_deeplinkly/android/` holds **two** Kotlin files: the bridge and its
test. Everything else moved. The bridge translates method-channel calls onto the
`Deeplinkly` facade and owns nothing.

The native SDK's public surface is `Deeplinkly` (facade),
`DeeplinklyDeepLink`, `DeeplinklyDeepLinkListener`, `DeeplinklyEvent`
(validation), and the models in `DeeplinklyModels.kt`.

---

## Constraints — read before changing anything

**The backend is production.** One customer is live on an older SDK and is not
upgrading soon. Treat the wire format as frozen: signal names, the
`{click_id, params, probability}` envelope, the `/resolve`, `/enrich`,
`/event` payload shapes. SDK *versioning* is relaxed — there is no urgency to
publish to pub.dev, and no real user base waiting on a plugin release.

**Persisted state must survive upgrades.** No renaming of `deeplinkly_prefs`
keys without a migration. Install id, first-touch attribution, session and
event sequence all have to carry across.

**Host manifest contract is frozen.** `com.deeplinkly.sdk.api_key`,
`.link_domains`, `.attribution_level`. An existing integrator must be able to
upgrade without touching their manifest.

**Merged permissions stay at `INTERNET` only.** `play-services-ads-identifier`
is `compileOnly` on purpose — its AAR declares `AD_ID`, which would land in
every host app including ones under Play's Families policy. `androidx.work` was
removed for the same class of reason. Do not casually add a dependency that
merges a permission or a `ContentProvider`.

---

## Load-bearing decisions

Things that look arbitrary and are not.

**The listener attaches on `flutterReady`, not at engine attach.** Dart has not
registered its `onDeepLink` handler when `onAttachedToEngine` runs, and
`invokeMethod` to an unhandled channel *succeeds silently* — so delivering
there would dequeue the link and lose it. This was caught and fixed during the
work; do not "simplify" it.

**`DeeplinklyDeepLink.raw` is forwarded to Dart unchanged.** The typed
accessors read from `raw`; the bridge sends `raw` itself. Parsing and
re-serialising would put a lossy round trip in the one path that must be exact.

**`SdkRuntime.deliverDeepLink` is the single delivery funnel** on *both*
platforms now, and its structure is deliberate: claim in-flight → post to main →
deliver → remove on success, leave queued on failure, release the claim in
`finally`. The comment above it records three separate bugs that shape it. Do
not restructure.

**`onDelivered` runs after the listener, never alongside the dispatch.** It is
what lets a caller hold durable state until delivery is real — `DeepLinkHandler`
drops the queue entry inside it. iOS used to run it synchronously while the
delivery itself was dispatched to the main queue, so every link (resolves
complete on a URLSession queue) had its queue entry dropped before it had been
handed to anyone. Fixed when the iOS funnel was collapsed.

**`Deeplinkly.init` takes a `Context`, not an `Application`.**
`DeeplinklyContext.app` is `internal`, so the bridge could not set it across the
module boundary. Rather than widen an internal to public API, `init` records the
app context always and skips the two `Application`-only features (app-open
reporting, auto launch-intent capture) if one cannot be reached.

**`logEvent` validation lives in Kotlin, not Dart.** It used to be Dart-only, so
native integrators got none of it. Dart now forwards and returns native's
answer. Rules are in `DeeplinklyEvent`.

**`SdkInfo.VERSION` is derived from Gradle's `VERSION_NAME`**, guarded by
`SdkInfoTest`. It was a hand-maintained literal and had already drifted —
shipping as 1.0.0 while reporting 1.9.0. It reports the **native** version;
a Flutter app no longer reports the plugin version. If the plugin version is
also wanted, it needs its own signal rather than overloading `sdk_version`.

**`init` is idempotent**, and the cold-start path is safe to run twice:
`DeepLinkHandler` marks intents consumed with an extra and honours
`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`; the install referrer has a pref latch.

---

## Cross-repo tooling

**Signal catalogue.** `tool/signals.json` in `flutter_deeplinkly` is canonical.
`tool/gen_signals.dart` writes Swift + docs locally, and takes
`--android=<path>` (or `DEEPLINKLY_ANDROID`) for the Kotlin catalogue, mirroring
the pre-existing `--backend=<path>`. Regenerate with:

```bash
dart run tool/gen_signals.dart --android=../android_deeplinkly
dart run tool/gen_signals.dart --check --android=../android_deeplinkly   # CI gate
```

**`test/signal_catalogue_test.dart` skips its Kotlin assertions** when
`android_deeplinkly` is not reachable, so a contributor with one repo checked
out is not failed. **Release CI must set `DEEPLINKLY_ANDROID`** or catalogue
drift passes silently.

**Where the SDK comes from.** `com.deeplinkly:deeplinkly-android:1.0.0` is on
Maven Central, and both the plugin and the example resolve it from there — the
example builds exactly the way a consumer's app does. `deeplinkly.useMavenLocal`
is `false` in `example/android/gradle.properties`.

**Building against an unreleased SDK** flips that seam back on:

```bash
cd ../android_deeplinkly && ./gradlew publishToMavenLocal -PsigningEnabled=false
cd ../flutter_deeplinkly/example && flutter build apk --debug \
  -Pdeeplinkly.useMavenLocal=true
```

`example/android/build.gradle.kts` adds `mavenLocal()` **last**. It has to be in
the *example app's* root build file — the app resolves the plugin's transitive
dependencies with its own repository list, so adding it only on the plugin side
leaves `:app:debugRuntimeClasspath` unable to find the SDK.

**Publishing.** See `android_deeplinkly/PUBLISHING.md`. Tag `vX.Y.Z` triggers
the workflow, which uploads to *staging* and stops; the final Publish is a
manual click on central.sonatype.com because Central releases are immutable.
`-PsigningEnabled=false` disables signing for local publishes.

---

## Verified

Unit: **172 Kotlin tests** in `android_deeplinkly` (168 + 4 version guards),
1 bridge test in `flutter_deeplinkly`, **395 Swift tests** in
`example/ios/RunnerTests`, 11 Dart tests, `flutter analyze` clean.

On device (Galaxy A33, Android 16), both the Flutter example and the native
sample:

- cold-start deep link delivered exactly once
- warm `onNewIntent` delivered exactly once
- custom scheme **without** `click_id` correctly ignored
- configuration change does not replay the link
- **offline → queued → reconnect → delivered exactly once**
- native sample captured a real Play install-referrer attribution
- advertising-id opt-in path works (34 signals with it, 32 without)

## Not verified

- **App Link OS verification.** `pm get-app-links` reads `1024` — the debug
  signing fingerprint is not in the dashboard, so `https://` links go to the
  browser. Tests injected intents at the component instead, which exercises the
  SDK but not Android's routing. Add the release fingerprint to the dashboard to
  close this.
- **A real deferred install from Play.** The referrer API was exercised, but not
  a genuine click → store → install chain.
- **Missing-API-key on device** — covered by `FlutterDeeplinklyPluginTest` only.

---

## Next: iOS

No code has moved to a new repo yet, but the two preconditions for moving it —
tests, and a delivery seam — are in place. **It is still materially harder than
Android was** — do not assume the same shape.

| | Android | iOS (was) | iOS (now) |
|---|---|---|---|
| Files importing Flutter | 5 of 28 | 6 of 26 | **3 of 29** |
| …genuinely coupled | 2 | 6 | **3** |
| Delivery sites | 1 funnel | 2 direct `postToFlutter` calls | **1 funnel** |
| Tests | 168 | 0 | **395** |

The three files that still import Flutter are the three that should: the plugin
itself, `PasteControlFactory` (a platform view, which cannot move), and
`MethodChannelDeepLinkListener` — 25 lines whose entire purpose is to be left
behind.

### Done

- **Swift tests.** 395 in `example/ios/RunnerTests`, run with
  `xcodebuild test -workspace ios/Runner.xcworkspace -scheme Runner
  -destination 'platform=iOS Simulator,name=iPhone 17'` from `example/`. They
  reach the SDK via `@testable import flutter_deeplinkly`; the target already
  existed as the Flutter template stub. **Read
  `example/ios/RunnerTests/SEAM_TESTS.md` before refactoring** — it records what
  is covered, what is not, and which refactor unblocks each gap. New test files
  need registering (`ruby example/ios/add_test_files.rb`); the project uses
  explicit file references, not a synchronized folder group.

- **One delivery funnel, behind `DeeplinklyDeepLinkListener`.** Delivery used to
  happen at two sites in `DeepLinkHandler`, each holding a
  `FlutterMethodChannel`. Everything now goes through
  `SdkRuntime.deliverDeepLink`, and the channel parameter is gone from
  `DeepLinkHandler`, `PasteboardHandler` and `StartupEnrichment` — where it was
  vestigial exactly as it had been on Android. The buffering and readiness gate
  are unchanged; only who receives the payload moved behind a protocol.

  This also fixed an ordering bug it exposed: `onDelivered` ran synchronously
  while the delivery itself was dispatched async, so a link resolved off the
  main thread — which is every link — had its durable queue entry dropped
  *before* it had been handed to anyone. It now runs after the listener, which
  is what Android's funnel always did.

  `DeepLinkHandler`'s double-dispatch state moved out to
  `DeepLinkDeliveryGuard`, unchanged, so the three duplicate-arrival rules are
  testable for the first time.

- **Network injection.** `NetworkUtils.session` is now the single seam between
  the SDK and the network: production uses `URLSession.shared`, tests swap in a
  session carrying a `URLProtocol` stub that claims *every* request, so nothing
  can reach the production host even by mistake. That closed the largest
  remaining gap in one move — `DeepLinkHandler`'s resolve path, the send
  helpers, `RetryQueue.retryAll`'s dispatch and `EnrichmentSender`'s dedupe
  latch are all covered now. `SEAM_TESTS.md` has the usage pattern and the two
  traps (`canInit` must claim everything; the body must be read off
  `httpBodyStream`, never `httpBody`).

### Still to do

Ordered plan with specifics in **`NEXT.md`**. In short:

1. **A `Deeplinkly` facade** mirroring Android's, *before* moving any files.
   The plugin currently reaches into 24 static entry points across 16 enums;
   extract as-is and that becomes the SDK's public API. Building the facade
   first keeps everything else `internal`, so the extraction is a file move
   rather than a mass visibility edit. It also houses three existing bugs that
   have no natural home today — most importantly that **iOS `logEvent` enforces
   none of the validation the public Dart API documents as "enforced
   natively"**, which Android has had since `DeeplinklyEvent`.
2. **The retry-queue key migration** (`sdk_retry_queue` → `dl_pending_retries`),
   while there is still one repo and a green suite.
3. **The extraction itself** — including moving ~380 of the 395 tests, which is
   mechanical but not free.

**`PasteControlFactory` stays in the plugin** either way: it is a `UiKitView`
and cannot move. That is no longer a blocker, though — `PasteboardHandler` is
Flutter-free now and the dependency runs plugin → SDK, which is the right
direction. What is left is deciding which calls become public, which the facade
answers.

Distribution is also different: CocoaPods **and** SPM, plus the privacy
manifest (`PrivacyInfo.xcprivacy`) and the IDFA template need to move with it.

**Key alignment folds in here.** The only real cross-platform storage-key
divergence is the retry queue: `dl_pending_retries` (Android) vs
`sdk_retry_queue` (iOS). Canonicalise on the Android name — 13 other keys use
the `dl_` prefix — so iOS is the side that migrates. Everything else already
matches: `dl_event_seq`, `dl_session_id`, `dl_static_profile`,
`initial_attribution`, `tracking_disabled`, `custom_user_id`,
`deeplinkly_device_id`, and the rest.

---

## Open items

**Deliberately deferred** — no real user base yet, so no urgency:

- Publishing `flutter_deeplinkly` to pub.dev. The Android work reaches no users
  until this happens, and that is currently fine.

**Pre-existing, none caused by this work:**

- iOS `_dl_event_seq` uses plain `UserDefaults.integer + 1` — not synchronised,
  not crash-safe. Android's is `synchronized` + `commit()`; port that.
- `setTrackingEnabled` is documented in `README.md` and
  `docs/FLUTTER_SDK.md:223` but is unreachable from the public Dart API — it
  exists only on `MethodChannelFlutterDeeplinkly`, which is not exported.
- `CHANGELOG.md`'s `## Unreleased` **Breaking** entry (iOS pasteboard default
  flip) is already live in shipped 1.9.0, and
  `.cursor/skills/flutter-sdk/SKILL.md:74,121-123` still documents the old
  behaviour.
- A real-looking API key is committed in
  `example/android/app/src/main/AndroidManifest.xml`.
- `example/test/widget_test.dart` is the unmodified Flutter counter template and
  cannot pass against the example app.

---

## Environment notes

- **`adb` over WiFi**: disabling WiFi on the device kills the adb connection
  too. Use USB for anything touching connectivity. Target a specific transport
  with `export ANDROID_SERIAL=<serial>` — **not** `adb -s "$VAR"`, because zsh
  does not word-split unquoted variables and the flag arrives empty.
- `flutter install` does not respect `ANDROID_SERIAL`; use
  `adb install -r <apk>`.
- Kotlin 2.2.0, AGP 8.7.0, Gradle 8.12, JVM 11, minSdk 21, compileSdk 35 —
  pinned identically in both repos on purpose.
