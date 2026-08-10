## 1.8.0

### Breaking

* `DeeplinklyLinkOptions.tags` is now `List<String>?` (was `Map<String, dynamic>?`).
  The API only ever accepted a list or a comma-separated string, so every map
  passed here was silently discarded server-side - no working behaviour changes,
  but callers that set `tags` need a one-line migration:

  ```dart
  - tags: {'spring': true, 'sale': true}
  + tags: ['spring', 'sale'],
  ```

### Added

* **Deferred deep linking now works on iOS.** A user who taps a link, installs
  from the App Store, and opens the app gets the deep link and its attribution,
  which previously did not happen at all on this platform. The link travels via
  the pasteboard: the Deeplinkly interstitial copies it when the visitor taps
  through to the store, and the SDK reads it back once on first launch.

  Two Info.plist keys drive it:

  ```xml
  <key>DeeplinklyLinkDomains</key>
  <array><string>yourbrand.deeplinkly.com</string></array>
  ```

  Only URLs whose host matches this list are read and resolved - anything else
  on the pasteboard is ignored and left untouched. Without the key, deferred
  deep linking is limited to `deeplinkly.com` links. The read happens once per
  install and is skipped when tracking is disabled. Pasteboard *metadata* is
  probed first (`detectPatterns(.probableWebURL)` on iOS 16+, `hasStrings`
  below), which is banner-free, so iOS only shows its "Pasted from Safari"
  banner when there is plausibly a URL to read.

* The iOS plugin now registers for link callbacks itself, on both the
  `UIApplicationDelegate` and `UIScene` lifecycles. Universal Links and custom
  schemes previously reached the SDK only if the host app forwarded them from
  its own AppDelegate, which nothing documented as necessary - so on a stock
  integration, no deep link of any kind arrived.

* `flutterReady`, `onLifecycleChange` and `setDebugMode` are implemented on
  iOS. They returned `FlutterMethodNotImplemented` before, and Dart swallowed
  the error, so iOS had no readiness gating: a deep link resolved before the
  Dart listener attached was delivered into nothing and lost. Native-side
  deliveries are now buffered until Dart reports it is listening.

* The pod ships a filled-in `PrivacyInfo.xcprivacy`. It was a stub with empty
  arrays and, because the podspec never referenced it, was not bundled at all.
  It declares the required-reason APIs the SDK uses (UserDefaults, system boot
  time) and the data it collects. `NSPrivacyTracking` is false: attribution is
  scoped to one tenant's own links and app, so no ATT prompt is involved and
  integrators need no `NSUserTrackingUsageDescription`.

### Removed

* `AdvertisingId.swift`, which had no callers since it was written. iOS never
  collected an IDFA, but the file's `AppTrackingTransparency` and `AdSupport`
  imports still linked both frameworks into every host app - the signature of
  an app that tracks, on an SDK that does not.

### Fixed

* Fixed the iOS device id being regenerated when the app launches in the
  background before the device has been unlocked. The keychain item used the
  default protection class, so the read failed and a fresh id was minted,
  silently splitting one user's attribution across two identities.
* Fixed iOS enrichment being sent at most once per source for the life of the
  install. The second and every later deep link never enriched, and
  `setUserId()` linked only the first login on a device - every later user was
  invisible. The guard is now keyed on what is being reported, and is set only
  after the payload is actually delivered rather than before it is attempted.
* Fixed `setUserId()` doing nothing for users who installed the app organically.
  Linking a login was gated on attribution data being present, which an organic
  install never has.
* Fixed a stale click being delivered as a real deep link. `/resolve` answers an
  unknown click id with HTTP 200 and `stale: true`, but the SDK reported the id
  it had asked about as valid and fired `onDeepLink` with empty parameters on
  every cold start. All four Android paths - deep link, install referrer,
  clipboard, and the retry queue - share the check with iOS now.
* Unresolved deep links now arrive in the same shape as resolved ones. When
  `/resolve` could not be reached, both platforms delivered a flat map of query
  parameters rather than the `{click_id, params}` envelope every resolved link
  uses, so `payload['params']` was null and an app reading its own parameters
  found nothing there. iOS also dropped everything that was not a UTM or
  ad-click key on that path - `screen`, ids, and the rest of what the link was
  addressed to - which is exactly the data an app needs when the backend is
  unreachable.
* Fixed Android deep links being dropped when they arrived before Dart was
  listening. A queued delivery is persisted as JSON and was read back with
  `params` still a `JSONObject`, which the method channel cannot encode, so the
  `onDeepLink` call failed for every link that had waited in the queue.
* Fixed iOS startup enrichment never being sent. It waited 30 seconds for
  evidence of attribution but did not count a `click_id` as evidence - which is
  all a deferred link often produces - so the wait always timed out, and a
  timeout dropped the payload entirely instead of sending it anyway.
* iOS resolve calls now retry transient failures, matching Android. A single
  network blip on first launch previously lost the install for good.
* Fixed deep link attribution losing every UTM and ad-click parameter. The
  resolve response nests them under `params`, but the SDK read them from the top
  level, so `getInstallAttribution()` returned only a source and a click id, and
  first/last-touch attribution was never populated for deep-link installs. The
  Android install-referrer path was unaffected.
* Fixed `generateLink` reporting failure on Android for links it had successfully
  created - `DeeplinklyResult.success` was read from a field the API does not
  send, so it came back false alongside a perfectly good URL. Backend error codes
  (for example `ER_011`, account paused for billing) now surface through
  `errorCode`/`errorMessage` on both platforms instead of a generic `LINK_ERROR`.
* Fixed event parameters losing their types. Nested maps and lists are now
  serialised as real JSON rather than their Java `toString()` form, and numbers
  and booleans are no longer flattened into strings.
* Fixed events being dropped when they carried 22-25 parameters. The native
  layers add four `_dl_*` bookkeeping keys after the Dart-side check, which
  pushed the payload over the limit. Those keys no longer count against the
  documented budget of 25, and `_dl_` is now reserved: passing a key with that
  prefix makes `logEvent` return false.
* Fixed the SDK retrying requests the server had already rejected. A revoked API
  key, a suspended account (HTTP 402), or a malformed payload now stops the
  retry immediately instead of being replayed up to 15 times per deep link and
  re-queued on every launch. 408 and 429 are still treated as transient.
* Fixed a resolve queue entry that had exhausted its retry budget being retried
  anyway once its backoff window elapsed.
* Fixed `resolveClick` treating any non-200 success (2xx) as an error.
* `logEvent` now validates container values against their encoded length, which
  is what the API measures, so oversized payloads fail fast on the client.

### Internal

* The Android unit test suite runs again: it declared JUnit 5 while every test
  was written against JUnit 4, and the JUnit 4 / Robolectric / androidx.test
  dependencies were missing, so nothing compiled.

## 1.7.1

* Fixed lifecycle notifications reporting a fatal crash in the host app. The
  `onLifecycleChange` call was not awaited, so its rejection escaped the
  surrounding `try`/`catch` as an unhandled async error - apps routing
  `PlatformDispatcher.onError` into Crashlytics logged a fatal crash on every
  foreground/background transition.
* Fixed the same deep link being delivered more than once. A new
  `OnNewIntentListener` was registered on every activity attach without the
  previous one being removed, so each re-attach added a duplicate that replayed
  the link (and its attribution) again.
* Fixed a configuration change replaying the launch intent, which resolved and
  delivered the original deep link a second time.
* Fixed `onNewIntent` using an activity captured at registration time, which went
  stale once the activity was recreated.
* Fixed a failure to read the API key from the manifest leaving `apiKey` unset,
  turning any later background failure into an
  `UninitializedPropertyAccessException` thrown from the coroutine error handler.
* Removed a redundant JSON pass over every resolved deep link payload.
* Added comprehensive Flutter SDK docs in `README.md`
* Added detailed integration guide in `docs/FLUTTER_SDK.md`
* Added Cursor AI skill in `.cursor/skills/flutter-sdk/SKILL.md`

## 1.7.0

* Added Android Support For Deeplinking, Deferred Deeplinking
* Added Support For Link Generation
* Added Support For UTM & Ad Data
* GDPR Ready
* Advanced Attribution
* Added Support For iOS