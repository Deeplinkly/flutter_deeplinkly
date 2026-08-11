# Next steps — iOS SDK extraction

Start-here guide for the next working session. Written 2026-08-11.

**Read first:** `docs/NATIVE_SDK_MIGRATION.md` (status, constraints,
load-bearing decisions) and `example/ios/RunnerTests/SEAM_TESTS.md` (what the
395 Swift tests cover, what they don't, and why).

---

## Where things stand

Android is extracted, published and consumed. iOS has not moved to its own repo
yet, but the two preconditions are done:

- **395 Swift tests**, stable across repeated runs.
- **The Flutter coupling is gone from the SDK proper.** 26 of 29 files are
  Flutter-free. The three that import Flutter are exactly the ones that should:
  `FlutterDeeplinklyPlugin`, `PasteControlFactory` (a platform view, cannot
  move), and `MethodChannelDeepLinkListener` (25 lines whose purpose is to be
  left behind).

The migration doc's "step 3" — untangling `PasteboardHandler` from
`PasteControlFactory` — is **largely resolved already**. `PasteboardHandler` no
longer imports Flutter, and the dependency runs plugin → SDK, which is the
correct direction. The only remaining back-reference is a doc comment. What is
left there is an API-visibility decision, not an untangling job.

---

## Step 1 — Build the `Deeplinkly` facade (do this first)

### Why before the extraction, not after

The plugin currently reaches into **24 static entry points across 16 enums**:

```
AppOpenReporter.start              DeviceProfile.primeUserAgent    RetryQueue.retryAll
AttributionLevel.current           Logger.d                        SdkInfo.elapsedSinceInit
AttributionLevel.set               Logger.setDebugMode             SdkRuntime.setListener
AttributionStore.get               NetworkUtils.generateLink       SessionManager.currentSessionId
AttributionStore.saveOnce          NetworkUtils.logEvent           StartupEnrichment.schedule
DeepLinkHandler.drainPendingResolves  PasteboardHandler.check      TrackingPreferences.setTrackingDisabled
DeepLinkHandler.handle             PasteboardHandler.handle        UserIdManager.updateCustomUserId
DeviceIdManager.getOrCreate        PasteboardHandler.setCheckEnabled
                                   PasteboardHandler.willShowBanner
```

Extract as-is and *that* becomes the SDK's public API. It isn't an API, it's
internals. Android's equivalent is one `object Deeplinkly` with ~17 members, and
the Android bridge "translates method-channel calls onto the `Deeplinkly` facade
and owns nothing."

Doing the facade first means:

- Only the facade, `DeeplinklyDeepLinkListener` and the models go `public`.
  Everything else stays `internal`, so the extraction becomes a **file move**
  rather than a mass visibility edit.
- The two platforms stay symmetric, which is the entire point of the exercise.
- It is verified against the existing 395 tests **in one repo**, not across two
  after the split.

### The surface to mirror

Source of truth:
`android_deeplinkly/deeplinkly/src/main/kotlin/com/deeplinkly/android_deeplinkly/Deeplinkly.kt`

| Android | iOS delegates to | Notes |
|---|---|---|
| `init(context, autoCaptureLaunchIntents)` | plugin `bootstrap()` body | iOS reads `DeeplinklyApiKey` from `Bundle.main`; no `Context`. Must stay idempotent. |
| `setDeepLinkListener(listener)` | `SdkRuntime.setListener` | Already exists. |
| `onActivityLaunch` / `onNewIntent` | `handleUniversalLink(_:)` / open-URL | Platform-shaped; see "expected asymmetry" below. |
| `onForeground()` | `AppOpenReporter.report` | |
| `shutdown()` | — | No iOS equivalent yet; decide whether to add. |
| `getInstallAttribution()` | `AttributionStore.get` | |
| `getDeeplinklyId()` | `DeviceIdManager.getOrCreate` | |
| `setUserId(_:)` | `UserIdManager.updateCustomUserId` | |
| `logEvent(...)` | `NetworkUtils.logEvent` + validation | **See gap 1.** |
| `generateLink(...)` | `NetworkUtils.generateLink` | |
| `setTrackingEnabled` / `isTrackingEnabled` | `TrackingPreferences` | **See gap 3.** |
| `setAttributionLevel` / `getAttributionLevel` | `AttributionLevel` | |
| `setDebugMode(_:)` | `Logger.setDebugMode` | |
| `isEnabled` | plugin's `sdkEnabled` | Currently private to the plugin. |
| `version` | `SdkInfo.version` | |

**Expected asymmetry — do not try to force parity.** Android has
Activity/Intent entry points iOS cannot have. iOS has a pasteboard surface
Android has no use for (`checkPasteboardNow`, `willShowBanner`,
`setCheckPasteboardOnInstall`, and `handle(itemProviders:)` for the paste
control). Both are correct. Only the *shared* concepts need to line up.

### Two gaps to close while doing it

Each is an existing bug with no natural home today. The facade is the home, and
Android already solved each one — port, don't redesign.

**Gap 1 — iOS `logEvent` performs no validation, and the public Dart API says
it does.**

`lib/flutter_deeplinkly.dart:180-196` documents the full contract ("The rules
are enforced natively rather than here") and lists every limit. Android enforces
them in `DeeplinklyEvent.validate`. iOS enforces **none** of them — the plugin
only trims the name and checks it is non-empty. So on iOS an app can today send
100 parameters, a 10KB name, or `_dl_`-prefixed keys that smuggle past the
backend's parameter budget, and they go straight to a production backend.

This is the strongest single argument for the facade. Port
`DeeplinklyEvent.validate` to Swift verbatim; the limits are asserted by the
backend too, so changing one without changing it there starts silently
truncating:

```
MAX_NAME_LENGTH = 64      MAX_PARAM_KEY_LENGTH = 64
MAX_PARAMS_COUNT = 25     MAX_PARAM_VALUE_LENGTH = 256
RESERVED_PARAM_PREFIX = "_dl_"
```

Note the subtleties: keys are trimmed *for the check only* and forwarded as
supplied; `List`/`Map` values are measured as compact-JSON encoded length;
anything not `String`/number/`Bool`/`List`/`Map` is rejected.

**Gap 2 — `_dl_event_seq` is unsynchronised and not crash-safe.**

`FlutterDeeplinklyPlugin.swift` does a plain
`UserDefaults.integer(forKey:) + 1`. Android does the read-modify-write under a
lock and `commit()`s it. The counter belongs in the facade, not the bridge.

~~**Gap 3 — `setTrackingEnabled` is documented but unreachable.**~~ **Done** —
surfaced on `FlutterDeeplinkly` and the orphan on
`MethodChannelFlutterDeeplinkly` removed. It was never a facade concern; it was
a Dart export. `test/tracking_test.dart` pins the `enabled` → `disabled`
inversion.

### How to verify

The facade is a thin delegating layer; the 395 tests already cover the
internals it calls. So:

- Existing tests must stay green untouched — that is the regression signal.
- Add tests for the facade's *own* logic only: idempotent `init`, the event
  validation table, the event-sequence counter under concurrency.
- `DeeplinklyEvent`'s Swift twin deserves a full table test, mirroring
  Android's.

---

## Step 2 — Retry-queue key migration

iOS stores the retry queue under `sdk_retry_queue`; Android uses
`dl_pending_retries`, and 13 other keys already use the `dl_` prefix.
Canonicalise on the Android name, so **iOS is the side that migrates**.

Small and independent, but do it while there is one repo and a green suite. It
needs a real migration (read the old key, move, delete) — persisted state must
survive upgrades.

`RetryQueueTests.testStorageKeyIsStable` will fail when you do this. That is
deliberate: it is the prompt to write the migration rather than just rename the
constant.

---

## Step 3 — The extraction

Only after 1 and 2.

**What moves:** the 26 Flutter-free files in `ios/Classes/`.
**What stays:** `FlutterDeeplinklyPlugin`, `PasteControlFactory`,
`MethodChannelDeepLinkListener`.

**The tests move too — budget for it.** Roughly 380 of the 395 are `@testable`
against internals that are leaving, so they move near-verbatim, along with
`StubURLProtocol.swift` and `TestSupport.swift`. Only the plugin-level ones
stay. Mechanical, not risky, but it is a real chunk of the work rather than an
afterthought.

**Packaging:** CocoaPods **and** SPM (there is no `Package.swift` anywhere
today, so SPM is net-new). `PrivacyInfo.xcprivacy` and the IDFA template move
with it. SPM also gives the new repo a test target essentially free
(`swift test`), which is a second reason to do it properly.

**Tooling:** `tool/gen_signals.dart` needs an `--ios=<path>` flag mirroring the
existing `--android=`. `tool/signals.json` stays canonical here.

Then reduce the plugin to a bridge, mirroring `flutter_deeplinkly/android/`'s
two files.

---

## Mechanics

```bash
# Run the Swift suite
cd example
xcodebuild test -workspace ios/Runner.xcworkspace -scheme Runner \
  -destination 'platform=iOS Simulator,name=iPhone 17'

# After adding or deleting a test file (explicit file references, not a
# synchronized folder group — a new file is not compiled until registered)
ruby example/ios/add_test_files.rb

# After adding a file to ios/Classes/
cd example/ios && pod install
```

Three traps, all documented at length in `SEAM_TESTS.md`:

- **`StubURLProtocol.canInit` claims every request.** `DomainConfig` points at a
  production backend with a live customer; nothing may reach it. An unstubbed
  path fails loudly rather than escaping.
- **Request bodies must be read off `httpBodyStream`, not `httpBody`.**
  URLSession converts one to the other, so `httpBody` is always nil inside a
  `URLProtocol` and every body assertion would silently pass against nothing.
- **Assert on the retry-queue *type*, not on the queue being empty.** Another
  suite's async work can land an unrelated item after the test that started it
  finished.

---

## Constraints — do not violate

Full list in `docs/NATIVE_SDK_MIGRATION.md`; the ones that bite during this
work:

- **The backend is production.** One customer is live on an older SDK. The wire
  format is frozen: signal names, the `{click_id, params, probability}`
  envelope, the `/resolve`, `/enrich`, `/event` payload shapes.
- **Persisted state must survive upgrades.** No key renames without a migration
  (which is exactly what step 2 is).
- **`SdkRuntime.deliverDeepLink` is the single delivery funnel** on both
  platforms. Its ordering is deliberate — `onDelivered` runs *after* the
  listener, never alongside the dispatch. Do not restructure.
- **The Flutter bridge's package must not move** — `pubspec.yaml` pins
  `pluginClass: FlutterDeeplinklyPlugin` under
  `com.deeplinkly.flutter_deeplinkly`.

---

## Known findings, deliberately not fixed

Pinned by tests as they actually behave, with the reasoning in `SEAM_TESTS.md`.
Each is a product decision, not a bug to quietly patch:

- **`EnrichmentSender` merges the caller's map last**, despite its comment
  saying device signals passed in are overwritten. No caller passes device
  signals, so it has never mattered.
- **`sendOnce` never writes its `source` into the payload.** It reaches the
  backend only when a caller also puts it in `attributionData` —
  `DeepLinkHandler` and `StartupEnrichment` do; `UserIdManager` and
  `AppOpenReporter` do not. So `custom_user_id` and `app_open` enrichments carry
  no `source`, despite it being a catalogued minimal-tier signal.
- **`NetworkUtils.attributionSnapshot` removes absent keys** rather than storing
  present nils (Swift subscript semantics). Invisible to the only consumer.

## Deferred, no urgency

- Publishing `flutter_deeplinkly` to pub.dev. The Android work reaches no users
  until this happens, and that is currently fine — no real user base.
- Remaining items in the migration doc's "Open items", including the committed
  API key in `example/android/app/src/main/AndroidManifest.xml` (worth clearing
  whenever you are next in that file) and the untouched
  `example/test/widget_test.dart` counter template.
- No CI exists in this repo at all, so the `gen_signals --check` catalogue gate
  runs nowhere. Signal drift between repos would pass silently today.
