# Next steps — iOS SDK extraction

Start-here guide for the next working session. Written 2026-08-11.

**Read first:** `docs/NATIVE_SDK_MIGRATION.md` (status, constraints,
load-bearing decisions) and `example/ios/RunnerTests/SEAM_TESTS.md` (what the
458 Swift tests cover, what they don't, and why).

---

## Where things stand

Android is extracted, published and consumed. iOS has not moved to its own repo
yet, but the preconditions are done:

- **458 Swift tests**, stable across repeated runs.
- **The Flutter coupling is gone from the SDK proper.** 28 of 31 files are
  Flutter-free. The three that import Flutter are exactly the ones that should:
  `FlutterDeeplinklyPlugin`, `PasteControlFactory` (a platform view, cannot
  move), and `MethodChannelDeepLinkListener` (25 lines whose purpose is to be
  left behind).
- **The `Deeplinkly` facade exists** and the bridge is a bridge. See below.
- **The retry queue uses the cross-platform key** `dl_pending_retries`, with a
  tested migration from the former iOS key `sdk_retry_queue`.

---

## Step 1 — the `Deeplinkly` facade — **done**

`ios/Classes/Deeplinkly.swift` mirrors Android's `Deeplinkly` object, and
`FlutterDeeplinklyPlugin` translates method-channel calls onto it and owns
nothing. The bridge's reach into SDK internals went from **24 static entry
points across 16 types to one** (`Logger.d`, which Android's bridge also uses).

The public surface is now `Deeplinkly`, `DeeplinklyDeepLinkListener` and
`AttributionLevel`; everything else stays `internal`, which is what makes the
extraction a file move rather than a mass visibility edit.

All three gaps it was meant to house are closed:

- **`logEvent` validation** — `DeeplinklyEvent.swift`, ported from Kotlin.
  `DeeplinklyEventTests` is the table.
- **`_dl_event_seq`** — read-modify-write under a lock, with `synchronize()`
  standing in for Android's `commit()`. `testConcurrentEventsGetDistinctSequenceNumbers`
  is the regression pin.
- **`setTrackingEnabled`** — surfaced on the public Dart API; it was a Dart
  export, not a facade concern.

Two decisions worth knowing:

- **`initialize()` reads `Bundle.main` only.** The bridge used to check its own
  bundle first, which resolves to the app bundle when statically linked and to a
  bundle that never carries the key otherwise — so it was dead weight. `LinkDomains`
  and `AttributionLevel` already read `Bundle.main`, and that is where the docs
  put the key. `initialize(apiKey:)` covers hosts that hold it elsewhere.
- **The pre-init link buffer moved into the facade** (`handleLink` before
  `initialize` buffers; `initialize` flushes it). It used to be the plugin's
  `pendingUniversalLink`, and `getInitialUniversalLink` now answers from
  `Deeplinkly.takePendingLink()`.

---

### Why it came before the extraction

Kept because the same reasoning applies to anything else tempted to move first.

The bridge reached into 24 static entry points across 16 enums. Extract as-is
and *that* becomes the SDK's public API — and it isn't an API, it's internals.
Building the facade first meant only three types went `public`, the two
platforms stayed symmetric, and the whole thing was verified against the
existing suite **in one repo** rather than across two after the split.

**Expected asymmetry — do not try to force parity.** Android has
Activity/Intent entry points iOS cannot have; iOS has a pasteboard surface
Android has no use for. Both are correct. Only the *shared* concepts line up,
and `Deeplinkly.swift`'s doc comment says which is which.

---

## Step 2 — Retry-queue key migration — **done**

iOS stored the retry queue under `sdk_retry_queue`; Android already used
`dl_pending_retries`, and 13 other keys use the `dl_` prefix. The queue is now
canonicalised on the Android name, so **iOS is the side that migrates**.

`RetryQueue.items()` now moves the old value to `dl_pending_retries` before
deleting `sdk_retry_queue`; every queue operation passes through that read.
The canonical value wins if both keys exist, which safely completes a migration
interrupted after the new value was written but before cleanup.

`RetryQueueTests.testStorageKeyIsStable` now pins the canonical key, while the
legacy-migration and interrupted-migration tests pin payload survival and
cleanup. `TestSupport.persistedKeys` clears both keys between tests.

---

## Step 3 — The extraction (do this next)

Only after 2.

**What moves:** the 28 Flutter-free files in `ios/Classes/`.
**What stays:** `FlutterDeeplinklyPlugin`, `PasteControlFactory`,
`MethodChannelDeepLinkListener`.

**The tests move too — budget for it.** Roughly 440 of the 458 are `@testable`
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

The plugin is already a bridge — step 1 did that — so what is left here is the
file move itself, not another round of untangling.

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
