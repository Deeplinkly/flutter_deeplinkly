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