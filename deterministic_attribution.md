# Deterministic attribution: how this SDK actually works

Audit of `flutter_deeplinkly` v1.9.2, Android + iOS, as of the current working
tree. Covers every path by which a click becomes an attributed install or an
`onDeepLink` delivery, what each device signal is for, and what is still wrong.

**Headline.** The SDK does **no probabilistic matching and no device
fingerprinting**, on either platform, at any attribution level. Matching is
deterministic throughout: a `click_id`, a short `code`, or a Play install
referrer. Device signals exist for *reporting* — they are POSTed to `/enrich`
and never used to derive an identifier that links a click to an install, and
they are never sent on `/resolve`.

The consequence is not neutral, and it is the single most important thing in
this document:

| | Direct deep linking | Deferred deep linking |
| --- | --- | --- |
| **Android** | Robust | Robust — Play Install Referrer covers ~every Play install |
| **iOS** | Robust | **Structurally limited** — pasteboard only, no fallback |

On iOS a visitor who does not tap through the interstitial (or whose clipboard
was overwritten, or whose app never renders the paste button) has **no deferred
attribution at all**. That limitation is explicit: Deeplinkly does not replace a
missing deterministic token with device correlation.

---

## 1. The five mechanisms

| Mechanism | Platform | Deterministic? | Where |
| --- | --- | --- | --- |
| Universal Link / App Link (`click_id`) | both | yes | `DeepLinkHandler` |
| App Link bypass (`code` in path) | both | yes | `DeepLinkHandler`, `carriesShortCode` |
| Custom scheme (`<scheme>://open?click_id=…`) | both | yes | `DeepLinkHandler` |
| **Play Install Referrer** | Android | yes | `InstallReferrerHandler` |
| **Pasteboard / `UIPasteControl`** | iOS | yes | `PasteboardHandler` |

Nothing else matches. `/enrich` is reporting; `/log-event` is analytics;
`/sdk-error` is telemetry. None of the three feeds a matcher.

---

## 2. Android flow

### 2.1 Direct deep link — warm and cold

```
Tap https://links.example.com/abc123?utm_source=x
  │
  ├─ App Link verified?  → OS routes VIEW intent straight to the activity
  │                        (no click_id — the backend never saw the click)
  └─ not verified        → browser → deeplinkly redirect → intent://open?click_id=…
                           (click_id present — the backend logged the click)
  │
FlutterDeeplinklyPlugin.attachActivity / newIntentListener
  │
DeepLinkHandler.handleIntent
  ├─ isReplay(intent)?  FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY  → drop
  │                     EXTRA_CONSUMED extra                 → drop
  ├─ click_id  ← intent extra, else URI query
  ├─ code      ← first path segment, ONLY if carriesShortCode(uri)
  │              (http/https AND host in com.deeplinkly.sdk.link_domains)
  ├─ neither → return
  ├─ mark EXTRA_CONSUMED (claim before any async work)
  └─ ioLaunch:
       DeepLinkQueue.claimResolve()      single-flight vs QueueProcessor
       GET /resolve?click_id=…&utm_*=…   ← no device signals, ever
         ├─ stale:true      → suppress delivery, drop from queue
         ├─ 2xx             → AttributionStore.saveOnce
         │                    DeepLinkQueue.enqueueDelivery
         │                    SdkRuntime.deliverDeepLink  → onDeepLink
         │                    EnrichmentSender.sendOnce(source="deep_link")
         ├─ transient fail  → enqueueResolve; QueueProcessor owns the delivery
         └─ terminal 4xx    → fallback {click_id, params} delivered once, stop
```

Two details that are load-bearing and easy to break:

- **`carriesShortCode`** (`DeepLinkHandler.kt:94`) is what stops
  `myapp://settings/notifications` from being resolved as code
  `"notifications"`, 404-ing, and firing `onDeepLink` for a purely in-app
  route. Custom schemes are excluded outright; http(s) is narrowed by the
  `com.deeplinkly.sdk.link_domains` manifest meta-data. **iOS has no
  equivalent — see finding #2.**
- **UTM forwarding on `/resolve`** (`DeeplinklyNetwork.resolveUrl`). Resolving
  by `code` makes the backend *create* the `ClickEvent`, reading UTMs off
  `request.GET`. Without them, every UTM on a verified-App-Link open is lost.
  **iOS does not forward them — see finding #3.**

### 2.2 Deferred deep link — the Play Install Referrer

This is the whole deferred story on Android, and it is a good one: signed by
Google, needs no permission, no user gesture, and works for every Play install.
The clipboard fallback that used to exist was removed in 1.9.0 as strictly worse
on all three counts.

```
First launch after a Play install
  │
FlutterDeeplinklyPlugin.attachActivity
  │
InstallReferrerHandler.checkInstallReferrer
  ├─ prefs["install_referrer_handled"]?           → skip        ⚠ finding #5
  ├─ isProcessing.compareAndSet(false, true)?     → skip
  └─ InstallReferrerClient.startConnection
       └─ onInstallReferrerSetupFinished(OK)
            rawReferrer = "click_id=…&utm_source=…&gclid=…"
            InstallReferrerTimings.record(details)
              ├─ referrer_click_at, install_begin_at   (click→install latency)
              ├─ google_play_instant, referrer_install_version
              └─ DeviceProfile.invalidate()  ← fold into the static profile
            AttributionStore.saveOnce(source="install_referrer", …)
            ├─ click_id present → enqueueResolve → claimResolve → GET /resolve
            │    ├─ stale:true    → mark handled, drop
            │    ├─ 2xx           → onDeepLink + EnrichmentSender("install_referrer")
            │    │                  mark handled
            │    ├─ terminal 4xx  → mark handled, do not retry
            │    └─ transient     → leave unhandled; retried next launch
            └─ no click_id      → mark handled (UTMs already persisted)
```

`install_referrer` is the strongest claim the SDK makes and outranks every other
source server-side. The raw referrer string ships in the enrichment payload
(`minimal` tier), so the backend can re-parse it independently of our parsing.

### 2.3 The reliability machinery

- `DeepLinkQueue` — durable resolve queue + delivery queue, `SharedPreferences`
  backed. Resolve entries carry **link identity only** (`QUEUEABLE_KEYS`); a
  device snapshot is never persisted, because an entry can sit for days.
- `QueueProcessor` — 2 s tick while non-empty, backing off to 30 s when idle.
  Single-flight via `isProcessing.compareAndSet` **and** per-item
  `claimResolve`, so a handler's own request and a processor tick cannot both
  resolve the same click.
- `SdkRuntime.deliverDeepLink` — marks in-flight, posts to the main thread,
  removes from the queue **only after** `invokeMethod` returns. A link stays
  queued until Dart has really taken it.
- `SdkRetryQueue` — `/enrich`, `/log-event`, `/sdk-error` retries, with TTL and
  re-filtering through the catalogue on read.
- Terminal vs transient: `DeeplinklyHttpException.isTerminal` = 4xx except
  408/429. Terminal never retries — a revoked key or suspended tenant would
  otherwise replay forever.

---

## 3. iOS flow

### 3.1 Direct deep link

```
Universal Link / custom scheme
  ├─ app delegate:  application(_:continue:restorationHandler:)
  │                 application(_:open:options:)
  └─ scene delegate: scene(_:willConnectTo:options:)      ← cold launch
                     scene(_:continueUserActivity:)
                     scene(_:openURLContexts:)
  │
FlutterDeeplinklyPlugin.handleUniversalLink
  ├─ channel or apiKey not ready → stash in pendingUniversalLink, flush at register()
  │
DeepLinkHandler.handle(url:channel:apiKey:source:)
  ├─ click_id ← query
  ├─ code     ← url.pathComponents.dropFirst().first    ⚠ NO domain check — finding #2
  ├─ beginIfIdle(identity)  — transient in-process set  ⚠ finding #6
  └─ resolveWithRetry (3 attempts; 50 ms then 100 ms of backoff)
       POST /resolve {click_id | code}   ⚠ no UTMs — finding #3
         ├─ stale:true → suppress
         ├─ success    → AttributionStore.saveOnce
         │               SdkRuntime.postToFlutter("onDeepLink")   (buffered if Dart cold)
         │               EnrichmentSender.sendOnce
         └─ failure    → fallback {click_id, params} delivered immediately
                         terminal → DeepLinkQueue.remove
                         else     → DeepLinkQueue.recordFailure   ⚠ no-op — finding #4
```

### 3.2 Deferred deep link — the pasteboard, and only the pasteboard

iOS has no Install Referrer equivalent. The interstitial served by
`RedirectView.handle_ios` writes the link (with its `click_id`) to the clipboard
when the visitor taps through to the App Store; first launch reads it back.

Two entry paths:

| Path | Banner? | Default | Source label |
| --- | --- | --- | --- |
| Automatic read on first launch | yes, "Pasted from…" | **off** | `clipboard` |
| `DeeplinklyPasteButton` (`UIPasteControl`, iOS 16+) | **no** | opt-in by placement | `paste_control` |

```
bootstrap()  (plugin registration — before any Dart runs)
  │
PasteboardHandler.check
  ├─ tracking disabled                    → return
  ├─ isCheckEnabled() == false (default)  → return, log how to enable
  ├─ already checked this install         → return
  ├─ hasProbableURL()  — banner-free probe
  │    iOS 16+  detectPatterns(.probableWebURL)   (deliberately NOT hasURLs:
  │             Safari writes plain text, hasURLs misses it)
  │    iOS 12–15  hasStrings
  └─ read()   ← this is the call that shows the banner
       mark checked BEFORE parsing (a crash must not re-banner every launch)
       │
       handle(text:source:)
         ├─ must be http(s)
         ├─ host must match DeeplinklyLinkDomains (exact or subdomain;
         │  defaults to deeplinkly.com with a warning) — this is what stops
         │  arbitrary copied URLs being shipped to the API
         ├─ needs a click_id or code
         ├─ DeepLinkQueue.enqueue   ← BEFORE clearing; the clipboard is the only copy
         ├─ automatic path only: UIPasteboard.general.items = []
         └─ DeepLinkHandler.handle(source: "clipboard" | "paste_control")
```

The two source labels are the axis the arms get compared on — "the paste control
recovers X% of tokens, the automatic read Y%" is unanswerable unless they are
labelled apart at resolve time.

`drainPendingResolves` retries anything the queue still holds on the next
launch, which is the offline-first-launch case. Note this only ever contains
*pasteboard* links (finding #4).

---

## 4. Device signals: what they are for

Assembled in exactly one place per platform — `EnrichmentSender` — as
**cached static profile + fresh dynamic sample**, filtered through the
attribution level, POSTed to `/enrich`. Also attached as a `device` sibling
block on `/log-event`.

**Never** sent on `/resolve`, on either platform.

### The static/dynamic split

`DeviceProfile` (static) is collected once and cached, keyed on a stamp:
SDK version · catalogue version · app version · app build · `Build.FINGERPRINT`
(Android) / `identifierForVendor` (iOS) · OS version.

The fingerprint/IDFV component is load-bearing and must not be "simplified"
away: Android Auto Backup and iOS encrypted backups restore preferences onto a
*different physical device*, and without it a restored install would report the
old phone's hardware for the life of the install.

`DynamicSignals` is collected fresh at send time and **never persisted in a
queue** — a resolve can sit queued for days, and replaying a stale advertising
ID, network type or clock as current is exactly the failure the split prevents.

### The catalogue is the contract

`tool/signals.json` (catalogue version 7, 73 signals) generates
`SignalCatalogue.kt`, `SignalCatalogue.swift`, the backend's
`signal_catalogue.py`, and `docs/SIGNALS.md`. `dart run tool/gen_signals.dart
--check` proves they agree; it passes on the current tree.

It is **fail-closed**: `AttributionLevel.filter` drops any key not in the
catalogue, at *every* level including `full`. You cannot ship a signal without
classifying it. This is the right default and it is also how finding #1 hides.

### Levels

| Level | What ships |
| --- | --- |
| `full` (default) | everything, incl. advertising ID / IDFA, Android ID / IDFV, App Set ID, screen geometry, carrier, local IP |
| `reduced` | drops every `full`-tier signal — all high-entropy hardware and every ad identifier |
| `minimal` | install id, app build, link identity. Nothing describing the device |
| `none` | no enrichment at all. **Links still resolve and still deliver** |

`setTrackingEnabled(false)` collapses to `none` regardless. Levels can be
pre-set natively (`DeeplinklyAttributionLevel` in Info.plist,
`com.deeplinkly.sdk.attribution_level` meta-data) because the first enrichment
can fire during plugin registration, before Dart could call `setAttributionLevel`.

`collected_at` and `attribution_level` survive `minimal` deliberately —
explaining *why* a payload is thin is the one thing that stays useful at every
level.

### Identifier policy

- **Android.** `play-services-ads-identifier` is `compileOnly`; the host app
  opts in by adding the dependency, because its AAR manifest declares `AD_ID`
  and that must not be imposed on Families-policy apps.
  `DynamicSignals.applyIdentifierPolicy` drops `android_id` whenever an
  `advertising_id` is present — Play policy forbids connecting the ad ID to
  persistent device identifiers. Applied at *assembly* time, not collection
  time, because the profile is cached while the ad ID is re-read every send.
  `ACCESS_NETWORK_STATE` is not declared; `connection_type` is reported only
  if the host app already holds it.
- **iOS.** IDFA is off unless the host sets `DeeplinklyEnableIDFA`, and is read
  only when ATT status is already `authorized`. **The SDK never calls
  `requestTrackingAuthorization`** — reading the status is not tracking and
  triggers no prompt. `PrivacyInfo.xcprivacy` declares `NSPrivacyTracking
  false` for the default configuration; apps that enable IDFA must merge
  `Resources/IDFA/PrivacyInfo.xcprivacy`.
- **Device id.** Android: `SharedPreferences` UUID. iOS: **Keychain** UUID, so
  it survives app deletion. This asymmetry is deliberate but has a backup-side
  consequence — finding #5.

### What is *not* collected

No IMEI, no MAC, no serial, no contacts, no location, no BSSID/SSID, no
cross-app data. Nothing at all is collected in the browser or the interstitial —
every signal is collected in-app, after install.

---

## 5. The deterministic matching boundary

`/resolve` is called only with a `click_id` or short `code`. It never receives a
device profile, and `/enrich` is reporting rather than a matching endpoint. The
obsolete `probability` response field and client parsing were removed. There is
no device-correlation fallback or matcher roadmap; missing deterministic
identity means no attribution.

---

## 6. Findings

Ordered by impact. Every one was verified by reading the code, not inferred.

**Status:** findings 1–6 are **fixed** in the current tree (details inline
below). Findings 7, 8 and 9 are open; 8 and 9 were turned up by auditing the
backend afterwards.

### 1. `event_at` is dropped before it ever leaves the device — FIXED

Android sets `event_at` — the moment the link was actually opened — in
`DeepLinkHandler.kt:165` and `InstallReferrerHandler.kt:77`, and
`DeepLinkQueue.kt:93` goes out of its way to whitelist it through the durable
queue (`SignalCatalogue.keysFor(IDENTITY) - "custom_user_id" + "event_at"`) with
a comment explaining that a retry three days later must still be dated to the
event.

`event_at` is **not in `tool/signals.json`**. The catalogue is fail-closed, so
`level.filter(payload)` in `EnrichmentSender.kt:65` drops it at every level
including `full`. The entire mechanism is dead at the wire.

Meanwhile `android_reported_at` *is* in the catalogue (`signals.json:97`,
`reduced`/`dynamic`) and **nothing anywhere sets it**. iOS's mirror,
`ios_reported_at`, is both catalogued and set (`DeepLinkHandler.swift:64`).

**Fixed** by renaming to `android_reported_at` at both call sites and in
`QUEUEABLE_KEYS`. No catalogue bump: the backend already accepts the key at
`reduced`/`dynamic` (`links/signal_catalogue.py:43`) and the SDK and backend
`signals.json` are byte-identical at v7. Covered by a new test that asserts
both halves — that the key survives the queue *and* that the catalogue permits
it, since `event_at` passed the first and failed the second.

Note the value lands only in `DeviceSignalSample.signals` (JSONB) and is
excluded from `TENANT_USER_FIELD_SPECS`, so there is no column to query it by.
If you want to report on click→report latency, that needs a backend change.

### 2. iOS resolves any URL's first path segment as a Deeplinkly code — FIXED

`DeepLinkHandler.swift:47` is `let code = url.pathComponents.dropFirst().first`
with no domain or scheme guard. Android fixed exactly this bug and documented it
at length in `carriesShortCode` (`DeepLinkHandler.kt:69–99`).

So on iOS, `myapp://settings/notifications` — a purely in-app route — resolves
`"notifications"` as a code, gets a terminal 404, and the failure branch
**delivers a fallback `onDeepLink`** carrying a URL that has nothing to do with
Deeplinkly. Any host app with its own custom-scheme routing, or with Universal
Links on its marketing domain alongside its link domain, sees spurious deep
links. It also leaks in-app navigation paths to the API as attempted codes.

`PasteboardHandler` already has the allowlist (`DeeplinklyLinkDomains`,
`isOwnDomain`) — the universal-link/custom-scheme path just doesn't use it.

**Fixed** by lifting the allowlist into a new `ios/Classes/LinkDomains.swift`
with two entry points, because the two callers genuinely want different
defaults:

- `carriesShortCode(url)` — the Android rule, used by `DeepLinkHandler`.
  http(s) only, host must match, **permissive when no domains are configured**
  (the URL already reached us through the OS; rejecting a real link is worse
  than a spurious resolve).
- `isPasteableDomain(host)` — used by `PasteboardHandler`. **Strict when
  unconfigured**, falling back to `deeplinkly.com` with a warning, because that
  content is whatever the user last copied from anywhere.

`docs/FLUTTER_SDK.md` now presents "Which links the SDK claims" as a
cross-platform rule rather than an Android one.

### 3. iOS drops click-time UTMs on the resolve-by-code path — FIXED

`NetworkUtils.resolveClick` POSTs a body of `{click_id | code}` and nothing
else. Android's `resolveUrl` deliberately appends
`utm_source/medium/campaign/term/content`, `gclid`, `fbclid`, `ttclid`, with the
comment that resolving by code makes the backend *create* the `ClickEvent` and
read those off `request.GET`.

An iOS Universal Link that opens the app directly therefore creates a
`ClickEvent` with no campaign attribution whatsoever. Compounded by the
GET/POST asymmetry: if the backend reads `request.GET`, an iOS POST body cannot
supply them even if they were added to it.

**Fixed**, and the backend settled the ambiguity. `resolve_click_or_register`
(`links/views.py:668`) accepts GET *and* POST, and reads `click_id`/`code` from
the JSON body falling back to the query string — but `_get_utm` and
`_get_tracking_param` (`links/views.py:1476-1488`) read **`request.GET` only**.
Putting the UTMs in the POST body would have looked right and dropped every one
of them. The view's own comment says so at `links/views.py:681-684`.

So iOS keeps its POST and now appends the attribution keys to the **query
string** of the resolve URL (`NetworkUtils.resolveURL`). **No backend change was
needed.** Verified: SDK and backend `signals.json` are byte-identical,
`gen_signals.dart --check --backend=…` reports all three catalogues in sync at
73 signals.

One wire detail worth knowing: `URLComponents` does not encode `+` in a query
value, and Django's `QueryDict` decodes a bare `+` as a space — so
`utm_campaign=a+b` would have arrived as `"a b"`. The builder re-encodes it to
`%2B`. Android was never affected: `URLEncoder` form-encodes, so a literal `+`
already became `%2B`. Behaviour was checked against a real `URLComponents`
across empty/unicode/`&`/`=`/space/`+` inputs.

### 4. iOS Universal Links get no cross-launch retry — FIXED

`DeepLinkQueue.enqueue` is called from exactly one place: `PasteboardHandler`.
`DeepLinkHandler.handle` constructs a `PendingResolve` and calls
`DeepLinkQueue.remove(pending)` / `recordFailure(pending)` on it, but for a
universal link that item was never enqueued, so `recordFailure` finds no index
and returns `false` — a silent no-op.

Net effect: a Universal Link tapped with no connectivity gets 3 in-process
attempts spanning 150 ms of backoff, then delivers a params-only fallback, and
is never upgraded. Android enqueues the resolve and lets `QueueProcessor` retry it for
the life of the install. The asymmetry is not documented anywhere.

**Fixed** by enqueueing in `DeepLinkHandler.handle` before the resolve starts.
Dedupe is on `identity`, so the pasteboard's own enqueue — which must stay put,
because it has to happen before the clipboard is cleared — collapses into it.

This required a second change that was not in the original finding. Adding a
retry made the existing failure branch wrong: it delivered a fallback
`onDeepLink` on *every* failure, so a queued link would have fired one on every
failed launch until it succeeded. That is precisely the "a fallback delivery and
a queued retry are alternatives, not companions" bug Android documents having
fixed. iOS now matches: `recordFailure` returns whether the entry is still
queued, and the fallback goes out only when the resolve is terminal or out of
attempts.

### 5. Android Auto Backup can silently kill deferred linking on a restored install — FIXED

There is no `fullBackupContent`, `dataExtractionRules`, or `allowBackup`
declaration anywhere in the repo (verified by search), so `deeplinkly_prefs`
participates in Auto Backup by default. Restoring onto a new device brings back:

- `install_referrer_handled = true` → `InstallReferrerHandler.kt:36` returns
  early and the genuinely new install's referrer is **never read**. This is the
  deferred-attribution path failing closed and silent.
- `initial_attribution` → `AttributionStore.saveOnce` is write-once, so the new
  install permanently inherits the old device's first-touch attribution.
- `deeplinkly_device_id` → two physical devices reporting the same install id.

`DeviceProfile` already solves this class of problem correctly by putting
`Build.FINGERPRINT` in the stamp. The referrer latch, the attribution store and
the device id have no such guard.

Severity depends on how many of your tenants' users restore from backup, which
is not nothing on Android.

**Fixed** with the stamp approach (self-contained, no host-app cooperation), in
a new `core/InstallIdentity.kt` invoked from `Prefs.of()` as it builds the
singleton — the only hook that is guaranteed to run before any SDK code reads a
value. Clearing the referrer latch *after* `InstallReferrerHandler` had already
read it would accomplish nothing.

**The stamp is not `Build.FINGERPRINT`**, and that turned out to be the whole
design question. FINGERPRINT changes on an OS update, and the two uses have
opposite tolerances for a false positive: a spurious re-collect of the device
profile costs one PackageManager lookup, whereas a spurious "new install" would
regenerate `deeplinkly_device_id` and re-read the install referrer on *every
OTA* — the same install reported as two, the same click resolved twice. The
signal has to survive an OS update and not survive a restore. FINGERPRINT gets
that backwards; the SSAID and `firstInstallTime` both get it right, so the
identity is a hash of the two. Fails open when neither can be read.

Two things the fix had to get right beyond the finding:

- **Consent is preserved.** `dl_attribution_level`, `tracking_disabled` and
  `custom_user_id` survive a restore. Clearing them would silently put a user
  who chose `reduced` and disabled tracking back to `full` and enabled on their
  new phone — consent they never gave twice. Everything else is cleared, and
  clear-by-default is deliberate: an unclassified key is install state far more
  often than it is consent, and the dangerous direction here is preserving
  something stale.
- **`DeeplinklyUtils` was opening the same prefs file directly**, bypassing the
  new check. `getOrCreateDeviceId` is one of the earliest reads in the process,
  so the one accessor that skipped the check was also the one most likely to
  hand back a previous install's device id before it could be cleared. It now
  delegates to `Prefs`.

Twelve tests cover it, including the two false-positive cases that would be
worse than the original bug: an OS update must not change the identity, and an
install upgrading to the guard must be adopted rather than wiped.

### 6. iOS duplicate-delivery guard is weaker than Android's — FIXED

iOS dedupe is a transient in-process `Set<String>` (`DeepLinkHandler.swift:19`),
released as soon as the resolve completes. Android has three layers: the intent
`EXTRA_CONSUMED` extra, `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, and the durable
`DeepLinkQueue` resolve claim.

If one link reaches both the app-delegate and scene-delegate paths, or the host
app also calls the still-public `handleUniversalLink` (which integration guides
told them to do), and the second arrival lands *after* the first resolve
returns, `onDeepLink` fires twice. Dart does not dedupe —
`flutter_deeplinkly.dart:97` forwards straight to the broadcast stream.

**Fixed** with a short-lived delivered-identities map alongside `inFlight`,
checked in `beginIfIdle` and stamped at both delivery points (a fallback counts
— from the host app's point of view it is still one `onDeepLink` for one tap).

The window is ten seconds. It absorbs mechanical double-dispatch, which lands
within milliseconds, and expires long before a deliberate re-tap of the same
link — which should still deliver, and does.

One interaction worth recording, because it is why the window is keyed on
*delivery* rather than on completion: a transient resolve failure must **not**
be suppressed, or the cross-launch retry added in finding #4 would be blocked
by this guard on its next attempt. Verified across all seven cases —
concurrent arrival, post-completion re-arrival, re-tap past the window, an
unrelated link, the same click via a different source, retry after a transient
failure, and map pruning.

### 7. Minor asymmetries — worth a decision, not necessarily a change

- **Enrichment dedupe.** iOS latches on `<source>_enriched_<identity>` and
  clears those latches when the profile stamp changes. Android has no latch at
  all, so a re-resolve of the same click re-POSTs `/enrich`. Bounded in
  practice, but the two platforms are not doing the same thing.
- **Startup wait.** Android waits up to 60 s for attribution before sending
  startup enrichment (with a 2 s floor); iOS waits 30 s. No reason for the gap.
- **`/resolve` verb.** Android GET, iOS POST. Both work today; only one should
  be canonical.
- **Retry counts don't match, and the comment says they do.**
  `DeepLinkHandler.swift:7` claims `maxAttempts = 3` "matches Android's
  `resolveClickWithRetry(maxRetries = 2)`". Android's `repeat(maxRetries)` runs
  2 attempts; iOS runs 3.
- **`getInitialUniversalLink`** is implemented natively on iOS and called by
  nothing in Dart. Dead method channel handler.

### 8. `probability` is documented API that the backend never sends — confirmed

Turned up by auditing the backend against the SDK. There is **no `probability`
anywhere in the Python source** — not in `resolve_click_or_register`, not in
`_click_attribution_params`, nowhere. It survives only in the backend's own docs
(`CURRENT_FEATURES.md`, `fingerprinting.md`) as a removed feature.

Both SDKs parse it and forward it to Dart (`DeeplinklyNetwork.kt:213`,
`NetworkUtils.swift:136`), and it is advertised to integrators as a real field:

- `docs/FLUTTER_SDK.md:350` — `'probability': 0.92, // deferred-match confidence`
- `README.md:213` — "Every link arrives as `{click_id, params, probability?}`"

An integrator who writes `if (data['probability'] > 0.8)` is writing against a
key that is always absent. Worse, the docs describe it as *deferred-match
confidence*, which implies the probabilistic matching §5 establishes does not
exist — so the one piece of user-facing documentation that contradicts the "no
probabilistic matching" position is also the one describing a dead field.

Harmless at runtime (both platforms guard the null), but it is misleading
documentation and dead parse code.

**Fix (pick one):** drop the field from both SDKs and both docs, which is
honest and cheap; or leave the parse in as forward-compatible plumbing and
delete only the doc references, which is defensible if a `/match` endpoint is
actually on the roadmap. Do not leave the docs as they are either way.

> **✅ Fixed — 2026-08-12.** Took the first option: `probability` parsing removed
> from `DeeplinklyDeepLink.kt`/`DeeplinklyNetwork.kt` (Android) and
> `NetworkUtils.swift`/`DeeplinklyDeepLinkListener.swift` (iOS), and every doc
> reference (`README.md`, `docs/FLUTTER_SDK.md`, `docs/NATIVE_SDK_MIGRATION.md`,
> `NEXT.md`, `CHANGELOG.md`, and the equivalent READMEs/docs in
> `android_deeplinkly`/`ios_deeplinkly`) updated to the real `{click_id, params}`
> envelope. Tests that used `probability` as an example key for "arbitrary keys
> survive the codec/forwarding" assertions were rewritten without it rather than
> deleted.

### 9. Two response-shape inconsistencies in the backend — confirmed, backend-side

Not SDK bugs, but the SDK is what would break on them:

- Resolve-**by-click_id** returns `tenant_user_id`; resolve-**by-code**
  (`links/views.py:821-824`) does not. Same endpoint, two shapes.
- Only the by-click_id path can return `stale`. Neither SDK depends on either
  today, which is luck rather than design.

---

## 7. Coverage and its limits

**Android deferred:** the Play Install Referrer is available for essentially
every Play install, needs no permission or gesture, and the SDK retries it
across launches on transient failure. With finding #5 fixed, a restored phone
now reads its own referrer rather than inheriting the old one's. This is as
good as the platform allows.

**iOS deferred:** every recovered install requires the visitor to tap through
the interstitial (which writes the clipboard) *and* the clipboard to survive to
first launch. That ceiling is the platform's, not ours.

The automatic read is **on by default from iOS 16** as of this release, so the
"integrated the SDK, deferred linking silently does nothing" outcome — which
was the default until now — no longer happens. Below iOS 16 it stays off: the
banner-free probe degrades to `hasStrings` there, so any copied text would
prompt. `DeeplinklyPasteButton` is the zero-banner path and is unaffected by
the setting; the two are complementary, since the automatic read catches users
who would never find a button and the button catches users whose integrator
turned the read off.

Worth being clear-eyed about the cost: the banner is not limited to your links.
On iOS 16+ any probable web URL on the clipboard triggers a read, and the SDK
discards non-matching hosts only *after* the banner has shown. A user who
copied a news article before opening the app gets a prompt for nothing.

If the resulting coverage is not good enough, that remains a documented product
limitation. Device correlation is not the fallback. Branch is a useful reference
point here and does not have a better answer: see §10.

---

## 8. What is already right

Worth stating, because these are the parts that should not be "simplified" in a
future pass:

- **No device data on `/resolve`**, both platforms. The endpoint never read it.
- **Fail-closed signal catalogue** generated from one source into Kotlin, Swift
  and Python, with a CI `--check`. An unclassified signal cannot ship.
- **Static/dynamic split** with nothing device-shaped ever persisted in a queue.
- **Single-flight claims everywhere** — `claimResolve`, `isProcessing`
  compare-and-set, `AtomicBoolean` referrer guard, `sentThisProcess` CAS.
- **Terminal vs transient HTTP** consistently applied, so a revoked key or
  suspended tenant stops instead of replaying forever.
- **`stale: true` handling** — an unknown click comes back 200, and both
  platforms suppress delivery rather than firing an empty deep link on every
  cold start.
- **Delivery removed only after the channel accepts it**, with buffering for a
  Dart side that has not attached yet.
- **Permission hygiene** — no `AD_ID`, no `ACCESS_NETWORK_STATE`, no
  `androidx.work` and its four permissions; IDFA opt-in; no ATT prompt.
- **Replay guards** on Android for relaunch-from-history and config changes.

Test status at time of audit: `flutter test` 10/10 passing; Android Gradle unit
tests `BUILD SUCCESSFUL`, 0 failures; `gen_signals.dart --check` in sync.

---

## 9. Remaining work

Findings 1–6 are done. No open item is a correctness bug: what is left is
documentation that misleads, and inconsistencies that are currently harmless by
luck rather than by design.

1. ~~**Finding #8 (`probability`).**~~ ✅ **Done — 2026-08-12.** Removed, not
   roadmapped; see the note under Finding #8 above.
2. **Finding #9 + §7 asymmetries** (`tenant_user_id` shape, enrichment dedupe,
   startup-wait timeout, retry counts, `getInitialUniversalLink`). Tidying.
3. **The §7 product limitation** — iOS deferred coverage depends on a
   deterministic token. A device-based `/match` fallback is deliberately out of
   scope.

A note for whoever picks these up: findings 2, 3 and 4 were all iOS bugs, and
none was caught by a test, because there is no test target for the plugin's
Swift — `example/ios/RunnerTests` tests the example app. The fixes were
verified by building the example app and by exercising the extracted logic
directly. That gap is being left open deliberately for now.

---

## 10. How Branch does it

Read from source, not from their marketing: **Branch iOS SDK 3.13.0** (the
version `flutter_branch_sdk` 8.10.0 pins) and **Branch Android SDK 5.20.3**.
Worth recording because it independently validates the two decisions this
document is mostly about.

### They also have the banner, and they say so in a comment

`BNCPasteboard.m:41-52`:

```objc
- (nullable NSURL *)checkForBranchLink {
    if ([self isUrlOnPasteboard]) {
        // triggers the end user toast message
        NSURL *tmp = UIPasteboard.generalPasteboard.URL;
```

Same probe-then-read shape as ours, and they ship `willShowPasteboardToast`
(`Branch.m:1026`) — our `willShowPasteboardBanner` under a different name.
Their README calls the feature NativeLink™ and claims "100% matching on iOS
through Installs", conditional on the clipboard surviving, exactly like ours.

### They removed fingerprinting too

`BranchOpenRequest.m:89`:

```objc
// fallback to deprecated name. Fingerprinting was removed long ago, hence the name change.
```

`device_fingerprint_id` → `randomizedDeviceToken`, a *server-issued* opaque id
rather than a client-computed signature. Android agrees: `bnc_device_fingerprint_id`
survives only as a read-only legacy pref migrated forward
(`PrefHelper.java:478-489`), `browser_fingerprint_id` and the old strong-match
flow are gone entirely, and the single occurrence of "probabilistic" in the
whole Android tree is a comment (`Branch.java:2186`).

So the market leader is on deterministic matching only. §5's position is not a
handicap we accepted; it is where the category landed.

**Caveat.** Branch still ships `local_ip`, `user_agent`, `screen_width/height/dpi`,
`model`, `brand`, `os_version`, `locale` on the install request
(`BNCRequestFactory.m:706-730`; Android `DeviceInfo.java:53-122` adds
`hardware_id`, GAID, carrier, CPU, build). The client no longer *names* a
fingerprint or receives one, but the tuple that would let a server correlate is
still on the wire. What the backend does with it is not knowable from the
client.

### Where we differ

| | Branch | Us |
|---|---|---|
| Automatic read default | ON, opt-out `branch_disable_nativelink` | ON, opt-out `DeeplinklyCheckPasteboardOnInstall` |
| Version floor | iOS 15 (their Flutter plugin) | none |
| Probe | `hasURLs` | `hasURLs` |
| Reads | `.URL` | `.url`, then `.string`, then the raw `public.url` item |
| Paste control | implemented (`BranchPasteControl.m`) but **not exposed to Flutter** | `DeeplinklyPasteButton` |
| Android clipboard | never read (writes only, in the share sheet) | never read |
| Install referrer | 5 sources in parallel, **hard-blocks the install request with an untimed wait lock** | Play only, non-blocking, retried across launches |

**The probe — since adopted.** Branch's `hasURLs` works because their
interstitial writes a URL-typed item. Ours used `writeText`, which is why
`hasProbableURL` rejected `hasURLs` and fell back to `hasStrings` below iOS 16.
The interstitial now writes a `ClipboardItem` of type `text/uri-list`, so the
SDK reads with `hasURLs` too.

Measured on a real pasteboard rather than assumed, which changed two beliefs:

| pasteboard contents | `hasURLs` | `url` | `string` |
| --- | --- | --- | --- |
| empty | false | nil | nil |
| plain text, not a URL | false | nil | nil |
| text that *is* a URL | **true** | ✓ | ✓ |
| `.url =` (URL object) | true | ✓ | ✓ |
| raw `public.url` item | **true** | **nil** | **nil** |

Row 3 means UIPasteboard coerces, so `hasURLs` is *not* strictly narrower than
`probableWebURL` on iOS 16+ — a user who copied any https link still gets the
banner. The real win is row 2 against the old pre-16 `hasStrings` path, which
fired on any text whatsoever. It also means the web and SDK rollouts need no
ordering: links written by the old interstitial are still found.

Row 5 was a live bug in the first version of this change: the probe says yes,
the banner fires, and both accessors answer nil, so the link is dropped.
`readURLString` now falls back to the raw `public.url` item.

**The referrer wait — not adopted.** Branch blocks its install request until
the referrer resolves, with no timeout at all (`Branch.java:1535-1568`; the
documented `setPlayStoreReferrerCheckTimeout` knob no longer exists, only a
stale javadoc). We do not block. Theirs guarantees the install and the referrer
are one event server-side; ours cannot stall a launch. Ours is the safer
default, but it does mean the backend has to stitch two reports together.
