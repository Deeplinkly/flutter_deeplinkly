# Next steps — iOS SDK extraction

Start-here guide for the next working session. Updated 2026-08-12.

**Read first:** `docs/NATIVE_SDK_MIGRATION.md` (status, constraints,
load-bearing decisions) and `example/ios/RunnerTests/SEAM_TESTS.md` (how the
native and app-hosted Swift suites are split, what they cover, and why).

---

## Where things stand

Android is extracted, published and consumed. The iOS extraction is complete
and its first public release is live from the sibling `../ios_deeplinkly` repo:

- **Native iOS `1.0.0` is released.** The immutable tag and GitHub Release are
  at <https://github.com/Deeplinkly/ios_deeplinkly/releases/tag/1.0.0>, and
  CocoaPods Trunk lists `Deeplinkly 1.0.0` (published 2026-08-11 19:33:16 UTC).
- **Native CI/CD is configured.** The release job validates the version,
  package, 453 tests, and podspec; publishes to Trunk; then creates the GitHub
  Release. `COCOAPODS_TRUNK_TOKEN` is stored in GitHub's protected `release`
  environment. Never copy the token into this repo, logs, or documentation.
- **The release workflow handles Trunk's false-negative failure mode.** The
  first publish committed `1.0.0` and then returned HTTP 500, making Actions
  look red even though the pod was live. Commit `ad49b23` checks Trunk before a
  push and polls Trunk after a failed push, so future tags are idempotent. Do
  not retry `pod trunk push` for a version already listed by
  `pod trunk info Deeplinkly`.

- **453 SDK tests** now live in `../ios_deeplinkly/Tests/DeeplinklyTests` and
  pass as a Swift package. The Flutter example retains **23 app-hosted tests**:
  4 MethodChannel adapter tests, 18 real-Keychain tests, and the Runner template
  smoke test.
- **The 28 Flutter-free SDK files and both privacy manifests have moved** to
  `../ios_deeplinkly`. The package builds at the iOS 12 floor through SwiftPM
  and CocoaPods.
- **The Flutter plugin keeps exactly three Swift files**, which are the ones
  that should remain:
  `FlutterDeeplinklyPlugin`, `PasteControlFactory` (a platform view, cannot
  move), and `MethodChannelDeepLinkListener` (25 lines whose purpose is to be
  left behind).
- **The Flutter plugin consumes `Deeplinkly 1.0.0` as a pod dependency,
  end to end.** The example's local-path development seam is removed;
  `Podfile.lock` resolves `Deeplinkly (1.0.0)` from Trunk, and all 23
  app-hosted iOS tests pass against it.
- **The catalogue generator crosses both native repo boundaries.** Pass
  `--ios=../ios_deeplinkly --android=../android_deeplinkly`; the corresponding
  environment variables are `DEEPLINKLY_IOS` and `DEEPLINKLY_ANDROID`.
- **Native verification is green:** the post-release CI rerun passed all 453
  tests and CocoaPods validation. The earlier
  `DeepLinkHandlerTests.testTheFallbackArrivesOnlyWhenTheRetryBudgetIsSpent`
  timeout was a runner flake: it passed alone locally and in the full rerun. No
  production or test code was changed for it.
- **The `Deeplinkly` facade exists** and the bridge is a bridge. See below.
- **The retry queue uses the cross-platform key** `dl_pending_retries`, with a
  tested migration from the former iOS key `sdk_retry_queue`.

---

## Resume here — ordered next steps

### 1. Make the Flutter example consume the public pod — **done**

`example/ios/Podfile`'s local override
(`pod 'Deeplinkly', :path => '../../../ios_deeplinkly'`) is removed. After
hitting a local CocoaPods CDN issue (`Error in the HTTP2 framing layer` on
`pod update`, fixed by removing and letting `pod repo` re-clone `trunk`),
`pod update Deeplinkly --repo-update` from `example/ios` resolved cleanly.
`Podfile.lock` contains `Deeplinkly (1.0.0)` with no `../../../ios_deeplinkly`
entry or `EXTERNAL SOURCES` entry for Deeplinkly.

### 2. Verify the real consumer seam — **done**

`flutter pub get`, `flutter test` (15 Dart tests, including the signal-drift
gate run for real against both sibling repos), `flutter analyze` (clean
besides the pre-existing `avoid_print` info in the example app), and
`dart run tool/gen_signals.dart --check --ios=../ios_deeplinkly
--android=../android_deeplinkly` (73 signals, in sync) all pass. The hosted
`xcodebuild test` run against `Runner.xcworkspace` passed all **23** tests
against the published pod.

### 3. Finish and merge the Flutter extraction branch — **done**

`docs/NATIVE_SDK_MIGRATION.md`'s “Still to do” section now says the native
`1.0.0` release is complete. PR #1 (`ios-test-suite` → `main`) went green on
CI — including catching and fixing a real cross-repo drift, an unpushed
`android_deeplinkly` commit that updated `tool/signals.json`'s comments,
pushed to that repo's `origin/main` before the gate passed — and was merged.
`ios-test-suite` is deleted, both locally and on `origin`. The native `1.0.0`
tag was left untouched throughout.

### 4. Add Flutter-repository CI — **done**

`.github/workflows/ci.yml` adds a `dart` job (checks out `flutter_deeplinkly`
plus sibling `ios_deeplinkly`/`android_deeplinkly` — both public — so the
73-signal drift gate runs for real rather than skipping; runs `flutter pub
get`, `flutter analyze`, `flutter test`, and `gen_signals.dart --check`) and
an `ios` job (macOS runner; `pod install` then the hosted `xcodebuild test`
suite with a dynamically-selected iPhone simulator, matching
`ios_deeplinkly`'s own CI style).

### 5. Publish `flutter_deeplinkly 1.9.1` when ready for real users

Publishing is now automated: `.github/workflows/publish.yml` fires on any
pushed tag matching `vX.Y.Z`, checks the tag against `pubspec.yaml`'s
`version:`, re-runs the exact same `dart` + `ios` jobs as `ci.yml` (via
`workflow_call`) as a release gate, then runs `flutter pub publish --dry-run`
and `flutter pub publish --force`. It authenticates to pub.dev via OIDC
("automated publishing") — no token lives in this repo.

**One-time setup, not done yet — pub.dev dashboard only, cannot be scripted
from here:**

1. On <https://pub.dev/packages/flutter_deeplinkly/admin>, under "Automated
   publishing", enable GitHub Actions publishing with:
   - Repository: `Deeplinkly/flutter_deeplinkly`
   - Workflow filename: `publish.yml`
   - Tag pattern: `v{{version}}`
   - Environment: `pub.dev` (matches the `environment: pub.dev` the publish
     job declares — required for the environment name to line up, optional
     for the automation to work at all)
2. Optional but recommended: add required reviewers to the `pub.dev`
   environment in this repo's Settings → Environments, so a human approves
   the run between the release gate passing and the actual publish step.
3. Optional: pub.dev also lets you disable manual (token-based) publishing
   once automated publishing is trusted, so every release provably went
   through CI. Not required for the workflow to work.

Before tagging a release:

- retitle CHANGELOG.md's `## Unreleased` heading to the version being
  released;
- confirm README installation instructions, repository/homepage metadata,
  license, and package contents are current (`flutter pub publish --dry-run`
  locally catches most of this without pushing a tag);
- confirm a clean example install resolves the public `Deeplinkly 1.0.0` pod.

Then `git tag v1.9.1 && git push origin v1.9.1`. Publishing is irreversible,
so don't push the tag until the above is done — the workflow's own dry-run
and CI gate catch mechanical problems, not judgment calls like changelog
accuracy.

### 6. Plan beyond CocoaPods Trunk

CocoaPods' published plan makes Trunk permanently read-only on **2026-12-02**;
existing pods should remain installable, but new pod versions will no longer be
accepted. Source: <https://blog.cocoapods.org/CocoaPods-Specs-Repo/>.

Before the November 2026 test shutdown, choose and validate the long-term
Flutter iOS distribution path. Prefer Swift Package Manager where Flutter's
plugin tooling supports the required integration; otherwise evaluate vendoring
the native source or maintaining a private specs repository. Keep CocoaPods
`1.0.0` working for existing consumers, but do not design future native releases
around Trunk remaining writable.

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

## Step 3 — The extraction — **done locally**

Only after 2.

**Sources:** the 28 Flutter-free files moved from `ios/Classes/` to
`../ios_deeplinkly/Sources/Deeplinkly`. The files left here are
`FlutterDeeplinklyPlugin`, `PasteControlFactory`, and
`MethodChannelDeepLinkListener`.

**Tests:** 453 SDK tests moved with `StubURLProtocol` and their support code.
Unhosted SwiftPM tests cannot use the simulator Keychain entitlement, so they
opt into a process-memory Keychain backend. Production still uses
Security.framework. The original 18 storage cases also remain in RunnerTests,
where they verify the real Keychain in a host app.

**Packaging:** `Package.swift` processes the required-reason privacy manifest
and excludes the opt-in IDFA template. `Deeplinkly.podspec` ships the same
manifest as a resource bundle. The plugin podspec depends on `Deeplinkly 1.0.0`.

**Tooling:** `tool/gen_signals.dart` supports `--ios=<path>` and
`DEEPLINKLY_IOS`; the Dart catalogue test follows the same sibling/env seam and
skips native assertions only when that checkout is unavailable.

**Release status:** complete. Native `1.0.0` is on GitHub, SwiftPM, and
CocoaPods, and the example Podfile's local-path override is removed and
verified end to end against the published dependency.

---

## Mechanics

```bash
# Run the native SDK suite (453 tests)
cd ../ios_deeplinkly
xcodebuild test -scheme Deeplinkly \
  -destination 'platform=iOS Simulator,name=iPhone 17'

# Run the app-hosted bridge + real-Keychain suite (23 tests)
cd ../flutter_deeplinkly/example
xcodebuild test -workspace ios/Runner.xcworkspace -scheme Runner \
  -destination 'platform=iOS Simulator,name=iPhone 17'

# After adding or deleting a test file (explicit file references, not a
# synchronized folder group — a new file is not compiled until registered)
ruby example/ios/add_test_files.rb

# After changing pod dependencies
cd example/ios && pod install

# Check all generated native catalogues
dart run tool/gen_signals.dart --check \
  --ios=../ios_deeplinkly --android=../android_deeplinkly
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
  format is frozen: signal names, the `{click_id, params}`
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

## Later / product decisions

- Remaining items in the migration doc's "Open items", including the committed
  API key in `example/android/app/src/main/AndroidManifest.xml` (worth clearing
  whenever you are next in that file) and the untouched
  `example/test/widget_test.dart` counter template.
- Decide whether fixing the pre-existing example `avoid_print` lint and counter
  template belongs in the extraction PR or a separate cleanup PR; neither
  should block validating the published native pod.
