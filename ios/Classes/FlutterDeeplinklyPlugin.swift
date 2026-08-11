import Flutter
import UIKit

public class FlutterDeeplinklyPlugin: NSObject, FlutterPlugin {
    // MARK: - Properties
    private var channel: FlutterMethodChannel!
    private var apiKey: String = ""
    private var sdkEnabled = false
    private static var storedApiKey: String?

    private static var staticChannel: FlutterMethodChannel?
    private static var pendingUniversalLink: URL?

    // MARK: - Registration
    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = FlutterDeeplinklyPlugin()
        let ch = FlutterMethodChannel(
            name: "deeplinkly/channel",
            binaryMessenger: registrar.messenger()
        )
        instance.channel = ch
        staticChannel = ch
        registrar.addMethodCallDelegate(instance, channel: ch)
        // Without this the plugin never sees a Universal Link unless the host
        // app's AppDelegate forwards one by hand, which nothing documented it
        // as needing to do — so no deep link reached the SDK at all.
        registrar.addApplicationDelegate(instance)

        // Apps on the UIScene lifecycle (FlutterSceneDelegate in Info.plist)
        // never fire the UIApplicationDelegate callbacks above; scene events go
        // only to delegates registered here. Resolved dynamically so the plugin
        // still builds against Flutter versions predating addSceneDelegate:.
        if #available(iOS 13.0, *) {
            let selector = NSSelectorFromString("addSceneDelegate:")
            let target = registrar as AnyObject
            if target.responds(to: selector) {
                _ = target.perform(selector, with: instance)
            }
        }

        // The banner-free deferred path: a system paste button the host app can
        // place in its own UI. Registered unconditionally — the view reports
        // its own availability so Dart can fall back below iOS 16.
        registrar.register(
            PasteControlFactory(
                messenger: registrar.messenger(),
                channel: ch,
                apiKeyProvider: { storedApiKey ?? "" }
            ),
            withId: "deeplinkly/paste_button"
        )

        instance.bootstrap()

            // ✅ Flush any pending universal link that arrived before channel setup
            if let pending = pendingUniversalLink {
                pendingUniversalLink = nil
                DeepLinkHandler.handle(url: pending, channel: ch, apiKey: storedApiKey ?? "")
            }
    }

    // MARK: - Universal Link Bridge
    /// Entry point for a Universal Link.
    ///
    /// The plugin now registers as an application delegate and picks these up
    /// itself; this stays public because integration guides told host apps to
    /// call it from their own AppDelegate, and those apps must keep working.
    /// Calling it twice for one link is harmless — the resolve is idempotent
    /// and `AttributionStore.saveOnce` is write-once.
    public static func handleUniversalLink(_ url: URL) {
        guard let ch = staticChannel else {
            pendingUniversalLink = url
            return
        }

        // ✅ hand off to DeepLinkHandler (same as Android)
        guard let key = storedApiKey, !key.isEmpty else {
            pendingUniversalLink = url
            return
        }
        DeepLinkHandler.handle(url: url, channel: ch, apiKey: key)

    }

    // MARK: - FlutterApplicationLifeCycleDelegate

    public func application(
        _ application: UIApplication,
        continue userActivity: NSUserActivity,
        restorationHandler: @escaping ([Any]) -> Void
    ) -> Bool {
        guard userActivity.activityType == NSUserActivityTypeBrowsingWeb,
            let url = userActivity.webpageURL
        else { return false }
        FlutterDeeplinklyPlugin.handleUniversalLink(url)
        // Not "handled" in the exclusive sense — other plugins may want it too.
        return false
    }

    public func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        FlutterDeeplinklyPlugin.handleUniversalLink(url)
        return false
    }

    // MARK: - FlutterSceneLifeCycleDelegate
    //
    // The scene-lifecycle equivalents of the two methods above. Declared with
    // explicit ObjC selectors and dispatched via respondsToSelector by
    // FlutterPluginSceneLifeCycleDelegate, so no protocol conformance (and
    // therefore no hard dependency on a recent Flutter header) is needed.
    // Each returns false: a deep link is not ours exclusively, and other
    // plugins must still see it.

    /// Cold launch. A Universal Link that starts the app arrives here, not via
    /// scene(_:continueUserActivity:) — missing this loses exactly the case
    /// deferred deep linking exists for.
    @available(iOS 13.0, *)
    @objc(scene:willConnectToSession:options:)
    public func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions?
    ) -> Bool {
        guard let connectionOptions = connectionOptions else { return false }
        for activity in connectionOptions.userActivities
        where activity.activityType == NSUserActivityTypeBrowsingWeb {
            if let url = activity.webpageURL {
                FlutterDeeplinklyPlugin.handleUniversalLink(url)
            }
        }
        for context in connectionOptions.urlContexts {
            FlutterDeeplinklyPlugin.handleUniversalLink(context.url)
        }
        return false
    }

    /// Warm launch: the app was already running when the link was tapped.
    @available(iOS 13.0, *)
    @objc(scene:continueUserActivity:)
    public func scene(_ scene: UIScene, continueUserActivity userActivity: NSUserActivity) -> Bool {
        guard userActivity.activityType == NSUserActivityTypeBrowsingWeb,
            let url = userActivity.webpageURL
        else { return false }
        FlutterDeeplinklyPlugin.handleUniversalLink(url)
        return false
    }

    /// Custom URL scheme while running.
    @available(iOS 13.0, *)
    @objc(scene:openURLContexts:)
    public func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) -> Bool {
        for context in URLContexts {
            FlutterDeeplinklyPlugin.handleUniversalLink(context.url)
        }
        return false
    }

    // MARK: - Initialization
    private func bootstrap() {
        let key =
            (Bundle(for: FlutterDeeplinklyPlugin.self)
                .object(forInfoDictionaryKey: "DeeplinklyApiKey") as? String)
            ?? (Bundle.main.object(forInfoDictionaryKey: "DeeplinklyApiKey") as? String)

        guard let key = key, !key.isEmpty else {
            self.sdkEnabled = false
            return
        }

        self.apiKey = key
        self.sdkEnabled = true
        FlutterDeeplinklyPlugin.storedApiKey = key

        // Resolve the WebView user agent before anything wants to send it.
        // WKWebView may only be constructed on the main thread, while every
        // collection path runs off it, so this is the one hop that makes the
        // agent a cached static signal instead of an async collection problem.
        DeviceProfile.primeUserAgent()

        // The only app-open signal on iOS. Without it a returning user who
        // never cold-starts the app is invisible — StartupEnrichment fires once
        // per process, and nothing else observed the foreground.
        AppOpenReporter.start(apiKey: key)

        StartupEnrichment.schedule(apiKey: apiKey, channel: channel)

        // Capture locally: these closures outlive bootstrap() and have no
        // reason to keep the plugin instance alive.
        let channel = self.channel!
        let apiKey = self.apiKey

        // Pasteboard access has to happen on the main thread, and this is the
        // only deferred deep link channel iOS offers — see PasteboardHandler.
        DispatchQueue.main.async {
            PasteboardHandler.check(channel: channel, apiKey: apiKey)
        }

        // retryAll blocks on a semaphore per item (15s timeout, up to 50
        // items). Running it inline froze the UI during plugin registration.
        DispatchQueue.global(qos: .utility).async {
            RetryQueue.retryAll(apiKey: apiKey)
            DeepLinkHandler.drainPendingResolves(channel: channel, apiKey: apiKey)
        }
    }


    // MARK: - Flutter MethodChannel Handling
    @objc public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        if call.method == "getDeeplinklyId" {
            result(DeviceIdManager.getOrCreate())
            return
        }
        guard sdkEnabled else {
            result([
                "success": false,
                "error_code": "SDK_DISABLED",
                "error_message": "Deeplinkly SDK disabled (missing API key).",
            ])
            return
        }

        switch call.method {
        case "getPlatformVersion":
            result("iOS \(UIDevice.current.systemVersion)")

        case "flutterReady":
            // Dart has attached its onDeepLink handler. Anything the native
            // side produced before now — the pasteboard read in particular —
            // is buffered in SdkRuntime and flushes here.
            SdkRuntime.setFlutterReady(channel)
            result(true)

        case "onLifecycleChange":
            let state = (call.arguments as? [String: Any])?["state"] as? String ?? ""
            Logger.d("Lifecycle: \(state)")
            if state == "resumed" {
                SdkRuntime.setFlutterReady(channel)
            }
            result(true)

        case "setDebugMode":
            let enabled = (call.arguments as? [String: Any])?["enabled"] as? Bool ?? false
            Logger.setDebugMode(enabled)
            result(true)

        case "getInstallAttribution":
            result(AttributionStore.get())

        case "getInitialUniversalLink":
            if let url = FlutterDeeplinklyPlugin.pendingUniversalLink {
                FlutterDeeplinklyPlugin.pendingUniversalLink = nil
                result(["url": url.absoluteString])
            } else {
                result(nil)
            }

        case "disableTracking":
            let disabled = (call.arguments as? [String: Any])?["disabled"] as? Bool ?? false
            TrackingPreferences.setTrackingDisabled(disabled)
            result(true)

        case "setAttributionLevel":
            let raw = (call.arguments as? [String: Any])?["level"] as? String ?? ""
            guard let level = AttributionLevel(rawValue: raw.lowercased()) else {
                result(false)
                return
            }
            AttributionLevel.set(level)
            result(true)

        case "getAttributionLevel":
            result(AttributionLevel.current.rawValue)

        case "setCheckPasteboardOnInstall":
            let args = call.arguments as? [String: Any]
            let enabled = args?["enabled"] as? Bool ?? false
            // Enabling from Dart is inherently late — bootstrap has already run
            // — so unless the caller opts out, read straight away rather than
            // waiting for a next launch the pasteboard may not survive to.
            let checkNow = args?["check_now"] as? Bool ?? true
            PasteboardHandler.setCheckEnabled(
                enabled, channel: channel, apiKey: apiKey, runCheckNow: checkNow)
            result(true)

        case "willShowPasteboardBanner":
            PasteboardHandler.willShowBanner { willShow in result(willShow) }

        case "checkPasteboardNow":
            PasteboardHandler.check(channel: channel, apiKey: apiKey)
            result(true)

        case "setCustomUserId", "setUserId":
            let userId = (call.arguments as? [String: Any])?["user_id"] as? String
            UserIdManager.updateCustomUserId(newId: userId, apiKey: apiKey)
            result(true)

        case "logEvent":
            let eventName = ((call.arguments as? [String: Any])?["event_name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            var parameters = (call.arguments as? [String: Any])?["parameters"] as? [String: Any] ?? [:]
            guard !eventName.isEmpty else {
                result(false)
                return
            }
            let seq = UserDefaults.standard.integer(forKey: "dl_event_seq") + 1
            UserDefaults.standard.set(seq, forKey: "dl_event_seq")
            parameters["_dl_event_seq"] = String(seq)
            // Milliseconds since the SDK initialised, not a raw systemUptime
            // reading. Same ordering power for events from a device with a
            // wrong wall clock, without also reporting how long the device has
            // been booted.
            parameters["_dl_client_elapsed_ms"] = String(SdkInfo.elapsedSinceInit())
            parameters["_dl_client_wall_epoch_ms"] = String(Int(Date().timeIntervalSince1970 * 1000))
            parameters["_dl_tz_offset_min"] = String(TimeZone.current.secondsFromGMT() / 60)
            // Joins this event to the device sample taken in the same visit.
            // Free: _dl_-prefixed keys are reserved and do not count against
            // the caller's 25-parameter budget.
            parameters["_dl_session_id"] = SessionManager.currentSessionId()
            NetworkUtils.logEvent(eventName: eventName, parameters: parameters, apiKey: apiKey) { ok in
                DispatchQueue.main.async { result(ok) }
            }

        case "generateLink":
            guard let args = call.arguments as? [String: Any],
                let content = args["content"] as? [String: Any],
                let options = args["options"] as? [String: Any]
            else {
                result([
                    "success": false,
                    "error_code": "INVALID",
                    "error_message": "Expected content/options",
                ])
                return
            }

            let payload = content.merging(options, uniquingKeysWith: { $1 })
            NetworkUtils.generateLink(payload: payload, apiKey: apiKey) { response in
                DispatchQueue.main.async {
                    if let response = response as? [String: Any] {
                        result(response)
                    } else {
                        result([
                            "success": false,
                            "error_code": "INVALID_RESPONSE",
                            "error_message": "generateLink returned nil or non-map value",
                        ])
                    }
                }
            }

        default:
            result(FlutterMethodNotImplemented)
        }
    }
}
