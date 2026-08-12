## 1.9.0

### Fixed

- **iOS enforced none of `logEvent`'s documented validation.** The public Dart
  API documents the full rule set — name and key lengths, the 25-parameter cap,
  the reserved `_dl_` prefix, value types and lengths — as "enforced natively
  rather than here". Android has enforced it since `DeeplinklyEvent`; iOS only
  trimmed the name and checked it was non-empty, so an app could send a 10KB
  name, a hundred parameters, or keys that smuggle past the backend's parameter
  budget. Now enforced identically on both platforms. A rejected event returns
  false and sends nothing, as documented.

- **iOS's `_dl_event_seq` counter could hand out duplicates.** It was a plain
  `UserDefaults.integer(forKey:) + 1`, so two events logged together read the
  same value and both wrote it back — the counter that exists to *order* events
  was the one thing that could not be relied on to. It is now a read-modify-write
  under a lock, followed by a synchronous write so a process killed immediately
  after cannot reissue a number it already sent.

### Added

- **`FlutterDeeplinkly.setTrackingEnabled(bool)`.** Documented since it shipped,
  but it existed only on `MethodChannelFlutterDeeplinkly` and so was not
  reachable from the public API. Both native sides already handled the
  `disableTracking` call it makes; only the Dart entry point was missing.

### Changed

- **iOS: the SDK no longer depends on Flutter either.** Everything below the
  method channel now sits behind a new `Deeplinkly` facade mirroring Android's,
  and `FlutterDeeplinklyPlugin` translates method-channel calls onto it and owns
  nothing. **Nothing changes for apps using this plugin**: same Dart API, same
  `deeplinkly/channel` methods, same envelope, same `Info.plist` keys, and no
  persisted state is renamed.

  Groundwork for a standalone native iOS SDK from the same source, so the two
  can never drift.

- **Android: the SDK no longer depends on Flutter.** Everything below the
  method channel — deep link resolution, the install referrer, attribution,
  the queues, retries, device signals and networking — is now plain Android,
  reachable through a new `Deeplinkly` facade. `FlutterDeeplinklyPlugin` is a
  translation layer over it and is the only file that imports `io.flutter`.

  This is groundwork for shipping a standalone native Android SDK from the
  same source, so the two can never drift. **Nothing changes for apps using
  this plugin**: same Dart API, same `deeplinkly/channel` methods, same
  `{click_id, params, probability}` envelope, same
  `com.deeplinkly.sdk.*` manifest keys, and no persisted state is renamed —
  install id, first-touch attribution, session and event sequence all survive
  the upgrade.

  Internally, `SdkRuntime` now holds a `DeeplinklyDeepLinkListener` rather than
  a `MethodChannel`, and the `MethodChannel` parameter that was threaded
  through `DeepLinkHandler`, `InstallReferrerHandler` and `QueueProcessor` is
  gone — it was only ever null-checked, never used to send anything.

- **`logEvent` validation moved from Dart to the native layer.** The rules were
  Dart-only, so they applied to Flutter callers and nobody else. They are now
  enforced where the event is actually assembled, which is the copy every host
  runs. Identical rules, identical `false` for a rejected event, and still no
  network call — the only difference is that a rejection now crosses the
  channel before answering.

### Breaking

- **iOS: the automatic pasteboard read is now on by default.** Deferred deep
  linking is why most apps integrate this SDK, and on iOS the pasteboard is the
  only mechanism there is — off by default meant it silently did not work for
  anyone who had not found the right doc page. Branch reached the same
  conclusion: their Flutter plugin enables it unless the host app opts out.

  **Upgrading apps will start showing the system "Pasted from…" banner** on
  first launch, once per install, when the clipboard holds a URL. To keep the
  previous behaviour:

  ```xml
  <key>DeeplinklyCheckPasteboardOnInstall</key>
  <false/>
  ```

  It must be Info.plist rather than `setCheckPasteboardOnInstall(false)`: the
  read happens during plugin registration, before any Dart runs.

  `DeeplinklyPasteButton` is unaffected and still shows no banner at all.

### Changed

- **iOS: the pasteboard is probed with `hasURLs`**, paired with the
  interstitial now writing a `ClipboardItem` of type `text/uri-list` alongside
  `text/plain` instead of `navigator.clipboard.writeText` alone. WebKit maps
  `text/uri-list` onto `public.url`, which is the type `hasURLs` inspects. This
  replaces `hasStrings` below iOS 16 and demotes
  `detectPatterns(.probableWebURL)` to a secondary probe on iOS 16+.

  The old pre-iOS-16 path was the problem: `hasStrings` is true for *any* text,
  so a copied phone number or password led to a read and a banner for content
  that was never ours. `hasURLs` is false for text that is not a URL, so every
  supported iOS version now behaves alike — which is what makes the default
  above safe without an iOS 16 floor.

  **The two halves have to ship together.** `hasURLs` on a plain-text item
  depends on UIPasteboard coercing the string back into a URL, and that is not
  dependable — an SDK probing with `hasURLs` against an interstitial that writes
  only plain text can miss the link, and `check` marks the install checked
  either way, so it is one lost install with no retry and nothing in any log.
  `detectPatterns(.probableWebURL)` is retained as a banner-free second probe
  precisely to cover that gap: the interstitial's `writeText` and `execCommand`
  fallbacks still produce plain text, as do links copied before the web change
  shipped. Below iOS 16 there is no such backstop.

  The read now also handles a `public.url` item whose payload is a string or
  data rather than a `URL`. Such an item reports `hasURLs == true` while both
  `url` and `string` answer nil, so it would previously have raised the banner
  and then dropped the link.

- **Android: `advertising_id` now requires an explicit dependency.** The SDK
  compiles against `play-services-ads-identifier` but no longer bundles it,
  because that library's manifest declares
  `com.google.android.gms.permission.AD_ID` and bundling it added that
  permission to every host app — a problem for apps under Play's Families
  policy. Add it yourself to keep reporting the advertising ID:

  ```groovy
  implementation 'com.google.android.gms:play-services-ads-identifier:18.2.0'
  ```

  Apps that do not add it report no `advertising_id`; nothing else changes, and
  attribution still resolves deterministically on the click id and install
  referrer.
- Renamed the reserved event parameter `_dl_client_monotonic_ms` to
  `_dl_client_elapsed_ms`. It now carries milliseconds since the SDK
  initialised rather than a raw monotonic clock reading — same ordering power
  for events from a device with a wrong wall clock, without reporting how long
  the device has been booted.

### Removed

- Five permissions no longer reach host apps. `androidx.work` was declared but
  never used while merging `WAKE_LOCK`, `ACCESS_NETWORK_STATE`,
  `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE` into every app; it is gone.
  `AD_ID` is gone per the note above. The SDK now declares only `INTERNET`.
- `device_name`, `boot_time`, `device_memory_mb` and `device_storage_mb` are no
  longer collected on either platform.

### Fixed

- **Android: a phone restored from backup no longer inherits the old phone's
  install.** `deeplinkly_prefs` participates in Auto Backup by default, so
  every latch the SDK writes came back describing an install that no longer
  existed. Worst of the three: `install_referrer_handled` returned true, so the
  referrer of the genuinely new install was **never read** — deferred
  attribution failing closed, with no log line and no retry. The other two were
  a permanently inherited `initial_attribution` and one `deeplinkly_device_id`
  shared by two physical devices. The SDK now stamps preferences with an
  install identity and clears install-scoped state when it does not match.

  The identity is derived from the SSAID and `firstInstallTime`, not
  `Build.FINGERPRINT` — it has to survive an OS update and not survive a
  restore, and FINGERPRINT gets that exactly backwards. Your privacy choices
  are preserved across a restore: attribution level, the tracking-disabled
  flag, and `custom_user_id` are never cleared.
- **iOS delivers one `onDeepLink` per tap when a link arrives twice.** The
  duplicate guard only covered arrivals that overlapped in time; a second
  arrival landing after the first resolve finished fired the callback again,
  and Dart forwards straight to a broadcast stream without deduping. A
  delivered link is now remembered for ten seconds — long enough for every
  mechanical double-dispatch (both delegate paths, a host app that also calls
  `handleUniversalLink` itself), short enough that a deliberate re-tap still
  works.
- **iOS no longer resolves your own routes as Deeplinkly links.** The first
  path segment of *any* opened URL was read as a link code, so
  `yourapp://settings/notifications` was resolved as code `notifications`,
  came back 404, and the failure path then delivered an `onDeepLink` carrying
  a URL that had nothing to do with Deeplinkly. It also leaked in-app
  navigation paths to the API as attempted codes. iOS now applies the same
  rule Android has: custom schemes are never read for a code, and http(s)
  URLs only when the host is in `DeeplinklyLinkDomains` (permissive when that
  key is unset). Set it if your app Universal Links any host besides its link
  domain.
- **iOS forwards click-time UTMs on `/resolve`.** Resolving by `code` makes the
  backend create the ClickEvent — the Universal Link opened the app directly,
  so the server never saw the click — and it reads UTMs and ad-click ids off
  the query string. iOS sent neither, so every UTM on a link that opened
  through a verified Universal Link was dropped. They now ride on the resolve
  URL, as they have on Android. A literal `+` in a value is percent-encoded
  rather than arriving as a space.
- **iOS retries a failed Universal Link resolve across launches.** Only the
  pasteboard path enqueued anything, so the failure branch recorded attempts
  against an entry that was never queued — a silent no-op. A Universal Link
  tapped offline got three attempts spanning 150 ms and was then abandoned to
  a params-only fallback for the life of the install, while Android retried
  the same link until it succeeded. Related: a transient failure no longer
  delivers a fallback *and* keeps retrying, which would have fired
  `onDeepLink` twice for one tap; the retry now owns the delivery, and the
  fallback goes out only when the resolve is rejected outright or out of
  attempts.
- **Android reports the moment a link was opened.** The timestamp was collected
  by both handlers and deliberately carried through the durable queue, then
  dropped one step before the wire: it was keyed `event_at`, which is in no
  signal catalogue, and the catalogue is fail-closed. It now uses the
  catalogued `android_reported_at`, mirroring iOS's `ios_reported_at`. A
  sample delivered by a retry days later is finally dated to the event rather
  than to the retry that carried it.
- The advertising ID and the Android ID (SSAID) are never sent in the same
  payload. Google Play's Advertising ID policy prohibits connecting the
  advertising ID to persistent device identifiers; when an advertising ID is
  present the SSAID is now dropped.
- `connection_type` on Android no longer silently fails on API 21–22:
  `getActiveNetwork` is API 23, and the call threw `NoSuchMethodError` into a
  catch block, so the field simply never appeared there. It now falls back to
  the legacy API.

### Breaking

* **Android's clipboard fallback is gone entirely.** It was only ever a
  fallback behind the Play Install Referrer, which is signed by Google, needs
  no permission and no user gesture, and works for every install — the
  clipboard read was worse on all three counts and is now removed rather than
  merely disabled. `com.deeplinkly.sdk.check_clipboard_on_install` is no longer
  read. Deferred deep linking on Android is unaffected; it has always run on the
  Install Referrer.

  The three pasteboard methods stay on the Dart API and stay callable from
  shared code — `setCheckPasteboardOnInstall`, `willShowPasteboardBanner` and
  `checkPasteboardNow` simply answer `false` on Android now.

### Added

* **`DeeplinklyPasteButton` — deferred deep linking with no paste banner.**
  A system `UIPasteControl` (iOS 16+) rendered inside your widget tree. Because
  the user taps it themselves, iOS treats the tap as the grant and shows no
  "Pasted from…" banner at all. The recovered link arrives on `deepLinkStream`
  exactly like any other; the widget's `onPasted` callback only reports whether
  the pasted content was one of your links.

  ```dart
  DeeplinklyPasteButton(
    onPasted: (handled) => setState(() => _showPasteButton = !handled),
    fallback: const SizedBox.shrink(),
  )
  ```

  Renders `fallback` on Android and on iOS below 16, so it is safe to place
  unconditionally. Accepts both URL- and plain-text-typed pasteboard items,
  which matters because `navigator.clipboard.writeText` in Safari produces text.

* **Attribution levels.** `setAttributionLevel` restricts how much the SDK may
  report, for consent flows that need a middle ground between "track" and
  "don't":

  | Level | Effect |
  |---|---|
  | `full` | Everything. The default, and the pre-1.9.0 behaviour |
  | `reduced` | Drops screen geometry, pixel ratio, core count, device model, and the Android advertising ID and Android ID. No fingerprint block on resolve |
  | `minimal` | Only the install id, app build, and the link being reported on. Nothing describing the device |
  | `none` | No enrichment sent at all. Links still resolve and still deliver |

  Set `DeeplinklyAttributionLevel` in `Info.plist` or the
  `com.deeplinkly.sdk.attribution_level` manifest meta-data to start restricted
  before any Dart runs — enrichment can be sent during plugin registration.
  `setTrackingEnabled(false)` still wins and behaves as `none`.

* **`willShowPasteboardBanner()`** answers whether reading the pasteboard right
  now would show the system banner — true only when the read is enabled, has not
  already happened, tracking is on, and there is plausibly a URL to read. It
  reads no content and shows no banner itself, so you can use it to put up a
  priming screen before the prompt rather than letting it arrive unexplained.
  Pair with `checkPasteboardNow()`. Always false on Android, which has no banner.

### Fixed

* **Android: one deep link now fires `onDeepLink` once.** Four paths could
  deliver the same click twice, all of them off the happy path, which is why
  they survived testing:

  - A transient resolve failure delivered a fallback payload immediately *and*
    left the link queued, so the queue processor delivered it again seconds
    later once the network recovered — first carrying only the URL's own query
    params, then the resolved ones. A fallback and a retry are now alternatives:
    the queue owns the delivery whenever a retry can still succeed, and the
    fallback is delivered only when the failure is terminal and nothing better
    is coming. On a fully offline launch the link now arrives once, a few
    seconds later, rather than twice.
  - The periodic queue drain tested the "already processing" flag without
    claiming it, so it could run the whole drain alongside `processNow` and
    resolve every queued link twice.
  - Every handler enqueues its pending resolve *before* attempting its own, and
    nothing stopped the periodic processor from resolving the same click in
    parallel — reachable on a slow first launch, on the deferred install path.
    Resolves are now claimed the way deliveries already were.
  - Same fallback-and-retry duplication as above on the clipboard path.

* **Android: the SDK no longer claims deep links that are not Deeplinkly's.**
  Any first path segment was read as a short code, so an app's own custom-scheme
  route (`yourapp://settings/notifications`) was resolved against the backend as
  code `notifications`. That returns 404, 404 is terminal, and the handler
  delivered a fallback — so opening an in-app screen fired `onDeepLink` with a
  URL that had nothing to do with Deeplinkly. Custom-scheme URLs without a
  `click_id` are now ignored, and the new optional
  `com.deeplinkly.sdk.link_domains` meta-data narrows the http(s) case for apps
  that App Link more than their link domain. Links that came through the
  redirect are unaffected — they carry a `click_id`.

* **Android: click-time UTMs survive the App Link bypass.** When a verified App
  Link opens the app directly the backend never sees the click, so the SDK
  resolves by code and the backend creates the ClickEvent then — reading UTMs
  and ad-click ids off the query string. The SDK sent none, so every
  `utm_*`/`gclid`/`fbclid`/`ttclid` on a link that opened this way was dropped,
  on what is the most common Android deep-link path for an installed app. They
  are now forwarded on both the initial resolve and the retry. Only those keys
  are sent; the rest of the link's query string stays on the device.

* **Android: `advertising_id` is collected on the deep-link path again.**
  Enrichment was gathered on the thread `handleIntent` was called on, which is
  the main thread, and `AdvertisingIdClient.getAdvertisingIdInfo` throws
  outright when called there. The throw was swallowed, so the id was silently
  never collected — it only ever appeared when the install-referrer or startup
  path happened to populate the cache first. Collection now runs on the IO
  dispatcher, which also takes PackageManager and `Settings.Secure` reads off
  intent dispatch.

* **Android: a recovered link keeps the source that queued it.** The retry
  processor labelled everything it resolved `deep_link`, so an install-referrer
  resolve that failed once and succeeded on retry was stored locally as an
  ordinary deep link. Backend attribution was never affected — the signed
  referrer in the payload always outranked it.

* **Android: the install-referrer callback catches its own failures.** It had a
  `finally` but no `catch`, so a `RemoteException` from a dying Play service
  unwound into Google's binder callback instead of being handled and retried on
  the next launch. Its "already processing" guard is also a real atomic claim
  now rather than a read followed by a write.

* **Android: the enrichment/event retry queue no longer loses entries.** It was
  stored as a `Set<String>`, so entries came back in arbitrary order, an
  overflow trim dropped a random one rather than the oldest, and two identical
  payloads collapsed into one — silently discarding a pending report. Now a
  JSON array with per-item ids, migrating the old key on first write. Its drain
  guard is atomic too, so two attaches can no longer re-send the same queued
  enrichment twice.

* **Android: assorted single-writer fixes.** The startup enrichment claimed its
  "already sent" flag inside the coroutine that sends, so two attribution saves
  landing together sent it twice. `logEvent`'s sequence counter was a
  non-atomic read-modify-write, so concurrent calls could be handed the same
  number. Queue removal no longer falls back to matching on `createdAt`, which
  dropped an unrelated entry queued in the same millisecond.

* **Android: a JSON null in stored attribution no longer reads as `"null"`.**
  `AttributionStore.get()` used `optString(key, "")`, which on Android answers
  the literal string for a JSON null — non-blank, and so true for every
  `isNullOrBlank` check the SDK and the host app make.

* **Android: no device fingerprint is sent on resolve.** `/resolve` stopped
  using device signals to match a click to an install some time ago and reads
  the key only to discard it, so building and sending one described the user's
  device for nothing.

* **iOS: the paste button now reports itself as `paste_control`.** Both
  pasteboard read paths shared one handler that hard-coded the source as
  `clipboard`, so every `DeeplinklyPasteButton` recovery was filed under the
  automatic read. The two mechanisms could not be told apart in reporting at
  all: the paste button appeared to recover nothing, and the automatic read's
  numbers were a blend of both. Nothing to change in your app.

* **Android: deep links opened from the OS now report `source: "deep_link"`.**
  The Android deep-link handler was the only one that never named its
  mechanism — the clipboard handler sends `clipboard`, the install-referrer
  handler carries the referrer itself — leaving the backend to infer it. An
  inferred source is treated as provisional and can be replaced by any later
  report, so a genuine deep link was the weakest claim the SDK made instead of
  one of the strongest. iOS has always sent this.

### Documentation

* **Android App Links setup was wrong and is now documented properly.** The
  manifest snippet put `android:autoVerify="true"` on a *custom scheme* filter,
  where it does nothing, and never showed an `https` filter at all. Following it
  left App Links unverified, so every link detoured through the browser and an
  `intent://` redirect — which in-app browsers (Instagram, Facebook, TikTok)
  frequently block, breaking the link even with the app installed. The README,
  `docs/FLUTTER_SDK.md` and the example app now declare both filters, and the
  docs cover fingerprint setup and `adb shell pm get-app-links` verification.

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