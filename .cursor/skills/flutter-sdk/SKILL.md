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
6. Add Android intent filter + `DEEPLINKLY_API_KEY` metadata.
7. Add iOS URL scheme + `DEEPLINKLY_API_KEY` in `Info.plist`.
8. Add identity bind call `setUserId()` post-login.
9. Add attribution pull via `getInstallAttribution()` and pass to analytics context.
10. Add event wrapper around `logEvent()` with pre-validation.
11. Add link creation helper via `generateLink()`.
12. Add optional debug logging toggle for non-production builds.

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

## Done criteria

- Integration works on Android and iOS.
- Deep/deferred links route correctly on cold and warm starts.
- Attribution and identity mapping are live.
- Event tracking and link generation are functional.
- Integration docs/snippets are updated for future maintainers.
