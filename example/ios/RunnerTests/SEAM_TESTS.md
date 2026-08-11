# iOS SDK unit tests — coverage and gaps

Written **before** the iOS extraction, for the reason the Android extraction
was safe and this one would not otherwise be: Android had 168 tests checking
every step, iOS had none. Deferred-attribution bugs fail silently and users do
not report them.

456 tests. See `docs/NATIVE_SDK_MIGRATION.md` for the extraction plan these
support.

## Running them

```bash
cd example
xcodebuild test -workspace ios/Runner.xcworkspace -scheme Runner \
  -destination 'platform=iOS Simulator,name=iPhone 17'
```

The tests live in the example app's `RunnerTests` target and reach the SDK
through `@testable import flutter_deeplinkly` — every type in `ios/Classes` is
`internal`, so there is no other way in. The target already existed as the
Flutter template stub; `inherit! :search_paths` in the Podfile is what makes
the plugin module visible to it.

**Adding a test file:** the project uses explicit `PBXFileReference`s, not a
synchronized folder group, so a new file is not compiled until it is
registered. Drop it in `RunnerTests/` and run:

```bash
ruby example/ios/add_test_files.rb
```

It adds what is missing and prunes what is gone.

## What is covered

| Suite | Unit | Notes |
|---|---|---|
| `SignalCatalogueTests` | `SignalCatalogue` | Fail-closed, level nesting, scope partition. Tests the *rules*, not the generated table. |
| `AttributionLevelTests` | `AttributionLevel` | Resolution order, the absolute tracking switch, `filter` over both value types. |
| `DeepLinkQueueTests` | `DeepLinkQueue` | Identity, dedupe, retry budget, cap, storage round trip, concurrency. |
| `RetryQueueTests` | `RetryQueue` | Enqueue/remove/cap, `refilter`, TTL sweep. Send dispatch not covered — see below. |
| `NetworkUtilsTests` | `NetworkUtils` | Response interpretation and payload shaping. Request issuing not covered. |
| `SdkRuntimeTests` | `SdkRuntime`, `MethodChannelDeepLinkListener` | The delivery funnel: buffering, flush ordering, `onDelivered`, the main-thread hop. |
| `DeepLinkDeliveryGuardTests` | `DeepLinkDeliveryGuard` | The three duplicate-arrival rules and the 10-second suppression window. |
| `SessionManagerTests` | `SessionManager` | Window arithmetic against an injected clock. |
| `AttributionStoreTests` | `AttributionStore` | First-touch latch, listener registration and removal. |
| `LinkDomainsTests` | `LinkDomains` | Configured-domain matching, lookalike rejection, custom-scheme exclusion. |
| `AppOpenReporterTests` | `AppOpenReporter` | The rate limit. |
| `DeviceProfileTests` | `DeviceProfile` | Contents, stamp, caching, first-seen latches. |
| `DynamicSignalsTests` | `DynamicSignals` | Contents, derivations, freshness. |
| `SignalCoverageTests` | collectors ↔ catalogue | Cross-check; see below. |
| `StorageTests` | `Keychain`, `DeviceIdManager`, `Prefs`, `TrackingPreferences` | Round trips, install-id stability. |
| `SdkInfoTests` | `SdkInfo`, `Logger`, `DomainConfig` | Version shape, log gating, frozen endpoint paths. |
| `PasteboardHandlerTests` | `PasteboardHandler`, `UserIdManager` | Opt-in and priming only; read paths not covered. |
| `NetworkRequestTests` | `NetworkUtils.request` | Headers, body/query shaping, status and transport handling. |
| `NetworkSendTests` | `sendEnrichment`, `logEvent`, `reportError`, `generateLink` | What gets sent, and what a failure does with it. |
| `RetryQueueDrainTests` | `RetryQueue.retryAll` | Per-type dispatch, `refilter`, the TTL in both directions. |
| `EnrichmentSenderTests` | `EnrichmentSender` | Payload assembly, the attribution gate, the dedupe latch. |
| `DeepLinkHandlerTests` | `DeepLinkHandler` | The resolve path end to end: URL in, `onDeepLink` out. |
| `DeeplinklyEventTests` | `DeeplinklyEvent` | The `logEvent` validation table, mirroring Android's. |
| `DeeplinklyTests` | `Deeplinkly` | The facade's *own* logic only — see below. |

`DeeplinklyTests` deliberately covers a narrow slice. The facade is a thin
delegating layer and everything behind it already has a suite, so re-testing the
delegation would only pin it twice. What is new there, and therefore what is
tested: the initialisation latch, the pre-init link buffer, the event-sequence
counter (including that concurrent events get distinct numbers, which is the bug
the old inline `UserDefaults.integer + 1` had), and that a rejected event never
reaches the network.

`SignalCoverageTests` is the one worth knowing about if you read nothing else.
`SignalCatalogue.allows` is fail-closed, so a signal a collector emits but
nobody catalogued is dropped **silently at every level including `.full`** — no
error, no log, just a field that never reaches the backend. Nothing else in the
suite would notice. It also checks the reverse (a catalogued key nothing
produces) and that each signal's scope matches which collector produces it.

## How the network is tested

`DomainConfig` points at the **production** backend and one customer is live on
an older SDK, so no test may reach it. `NetworkUtils.session` is the single seam
that makes this safe: `StubURLProtocol.install()` swaps in a session whose
configuration installs a `URLProtocol` stub, and `URLSession.shared` goes back
in `tearDown`.

```swift
StubURLProtocol.install()
StubURLProtocol.stub(DomainConfig.resolveClick, .ok(["click_id": "c1", "params": [:]]))
// …exercise the SDK…
let body = StubURLProtocol.waitForRequest(to: DomainConfig.resolveClick).first?.body
```

Three things about it are load-bearing:

- **`canInit` claims every request.** A path the test forgot to stub fails with
  a distinctive error rather than escaping to the real host. There is no
  configuration in which a test can talk to production.
- **The request body is read off `httpBodyStream`, not `httpBody`.** URLSession
  converts one to the other on the way in, so `httpBody` is always nil inside a
  `URLProtocol` — every body assertion would silently pass against nothing.
- **`.ok` / `.terminal` / `.transient` / `.offline`** name the four cases the SDK
  actually branches on, so a test reads as the scenario rather than as a status
  code.

`retryAll` blocks on a semaphore per item, so `RetryQueueDrainTests` drives it
from a background queue — calling it on the thread the response is delivered to
would deadlock.

## What is not covered, and why

Grouped by what would unblock it. These are the arguments for the refactors the
migration doc already calls for — the tests are what makes those refactors safe,
and these gaps are what the refactors would close.

### `PasteboardHandler`'s read paths

`check`, `read` and `readURLString` touch `UIPasteboard.general`, whose contents
a unit test cannot dependably control, so the automatic-read path is covered
only as far as its opt-in and priming gates. `readURLString`'s three descending
read forms — the third of which was found by probing a real pasteboard, not
reasoned about — remain untested.

**Unblocked by:** splitting the parse-and-validate step (does this text name one
of our links, and what is its identity?) from reading the pasteboard, so the
first can be tested on a plain string. That split is most of step 3's boundary
work anyway.

### `Bundle.main` configuration

`LinkDomains.configured()`, `AttributionLevel`'s Info.plist fallback and
`DynamicSignals.idfaEnabled` read `Bundle.main`, which in a host-app test bundle
is the Runner app. So the tests run against
`example/ios/Runner/Info.plist`, which sets
`DeeplinklyLinkDomains = [example.deeplinkly.com]` and nothing else.

Testable: the configured branch of `carriesShortCode` and `isPasteableDomain`,
`AttributionLevel`'s final default, IDFA staying off.

Not testable: the **unconfigured** branches — `carriesShortCode` being
permissive when no domains are set, and `isPasteableDomain` falling back to
`deeplinkly.com` with a warning. Both are the defaults an integrator who
configures nothing actually gets.

`LinkDomainsTests.testTestHostConfiguresTheExpectedDomain` guards the fixture,
so if the example app's Info.plist changes that test fails first and says why.

**Unblocked by:** injecting the domain list (a parameter with a
`Bundle.main`-reading default is enough).

### Platform behaviour

Untestable in a unit test by nature, and unchanged from the migration doc's
existing "Not verified" list: Universal Link OS routing, a genuine deferred
install, the real system paste banner, `WKWebView` user-agent priming (it needs
main-thread bootstrap), and ATT statuses other than whatever the simulator
reports.

## Conventions

- **`DeeplinklyTestSupport.reset()` in every `setUp` and `tearDown`.** Every
  unit is an `enum` with `static` members reading `UserDefaults.standard`
  directly, so state leaks between cases otherwise. Clearing is by explicit key,
  not `removePersistentDomain`, which would also wipe the host app's defaults.
  **Adding a persisted key to the SDK means adding it to `persistedKeys`** — or
  the first test that writes it poisons every test after it.
- **`FakeBinaryMessenger`** stands in for the engine. `FlutterMethodChannel` is
  concrete and cannot be usefully subclassed, but it accepts any messenger, so
  the fake goes one level down and decodes what was sent.
- **No sleeps for logic; poll for asynchrony.** `SessionManager`,
  `AppOpenReporter` and `DeepLinkDeliveryGuard` all take an injectable `now:` —
  use it rather than waiting. Where the SDK is genuinely asynchronous (anything
  behind a request), poll with `waitUntil` / `waitForRequest` instead of a fixed
  sleep.
- **Assert on the retry-queue *type*, not on the queue being empty.** Another
  suite's async work can land an unrelated item after the test that started it
  has finished; an `isEmpty` assertion would blame the wrong test. Suites that
  leave work in flight (`DeepLinkHandlerTests`) settle briefly in `tearDown`
  while their stubs are still installed.
- **`SdkRuntime`'s pending buffer survives `reset()`.** `clearListener` drops
  the listener but not the links buffered behind it, and a resolve that
  completes with nothing attached leaves one there for whichever test attaches
  next. So attach the listener *first*, `reset()` the recorder, and only then
  deliver — asserting on the recorder's full contents straight after attaching
  counts someone else's link. (This is the same hazard as the retry-queue rule
  above, one layer up; it is not cleared centrally because a test that wants to
  observe a flush needs the buffer intact.)
- **Test comments say why the behaviour matters**, not what the assertion does.
  Most of them are recording a bug that was already paid for once.

## Three things pinned that are not obviously deliberate

`NetworkUtils.attributionSnapshot` *removes* absent attribution keys rather than
storing them as present nils, because on a `[String: String?]` Swift's
`dict[key] = nil` deletes the entry. The dictionary literal that seeds `source`
and `click_id` does not go through the subscript, so those two survive as
present nils — hence an asymmetry that looks like a choice and is not.

It makes no difference to the only consumer (`AttributionStore.saveOnce`
compacts the map first), which is why it has never mattered. It is pinned by
`testAttributionSnapshotOmitsAbsentAttributionKeys` because "tidying" the loop
into `updateValue(nil, forKey:)` during the extraction would change the shape
without changing a line of visible logic.

### `EnrichmentSender` merges the caller's map last

`sendOnce`'s doc comment says "device signals passed here are overwritten".
They are not — `attributionData` is applied *after* the collected profile and
dynamic sample, so a caller's value wins.

It has never mattered: all four callers pass link identity only
(`DeepLinkHandler` passes source/click_id/ios_reported_at, `StartupEnrichment`
passes the stored attribution, `AppOpenReporter` and `UserIdManager` pass
nothing). `testCallerSuppliedValuesWinOverCollectedOnes` pins the behaviour that
actually runs, and will fail if the merge order is ever changed to match the
comment — at which point the comment is the thing to keep.

The claim the comment is really protecting *is* true and is tested separately:
nothing device-shaped is carried in from a queue, because the device half is
collected fresh at send time.

### `EnrichmentSender` never reports its own `source`

`sendOnce(attributionData:source:…)` uses `source` for the dedupe key and the
lifecycle exemption, but never writes it into the payload. It reaches the
backend only when a caller *also* puts it in `attributionData`.

`DeepLinkHandler` and `StartupEnrichment` do. `UserIdManager` and
`AppOpenReporter` pass an empty map, so their enrichments carry no `source` —
even though `source` is a catalogued minimal-tier signal the backend reads to
stamp `ClickEvent.attribution_source`.

`testTheSourceParameterDoesNotReachThePayload` pins it. Flagged rather than
fixed: whether a `custom_user_id` or `app_open` enrichment *should* be labelled
is a product question about a production backend, not one to answer by quietly
adding a field to the payload.
