import Deeplinkly
import Flutter
import UIKit

/// The Flutter bridge.
///
/// Translates method-channel calls onto the `Deeplinkly` facade and owns
/// nothing, mirroring `flutter_deeplinkly/android/`'s bridge. Everything it
/// used to reach into — 24 static entry points across 16 types — is behind the
/// facade now, which is what lets the SDK move to its own repo as a file move
/// rather than a mass visibility edit.
///
/// The three files that import Flutter are this one, `PasteControlFactory` (a
/// platform view, which cannot move) and `MethodChannelDeepLinkListener`.
public class FlutterDeeplinklyPlugin: NSObject, FlutterPlugin {
    private var channel: FlutterMethodChannel!

    // MARK: - Registration
    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = FlutterDeeplinklyPlugin()
        let ch = FlutterMethodChannel(
            name: "deeplinkly/channel",
            binaryMessenger: registrar.messenger()
        )
        instance.channel = ch
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
            PasteControlFactory(messenger: registrar.messenger()),
            withId: "deeplinkly/paste_button"
        )

        // Idempotent, and flushes any link that reached handleUniversalLink
        // before now.
        Deeplinkly.initialize()
    }

    // MARK: - Universal Link Bridge
    /// Entry point for a Universal Link.
    ///
    /// The plugin registers as an application delegate and picks these up
    /// itself; this stays public because integration guides told host apps to
    /// call it from their own AppDelegate, and those apps must keep working.
    /// Calling it twice for one link is harmless — the resolve is idempotent
    /// and `AttributionStore.saveOnce` is write-once.
    public static func handleUniversalLink(_ url: URL) {
        Deeplinkly.handleLink(url)
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
        Deeplinkly.handleLink(url)
        // Not "handled" in the exclusive sense — other plugins may want it too.
        return false
    }

    public func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        Deeplinkly.handleLink(url)
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
                Deeplinkly.handleLink(url)
            }
        }
        for context in connectionOptions.urlContexts {
            Deeplinkly.handleLink(context.url)
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
        Deeplinkly.handleLink(url)
        return false
    }

    /// Custom URL scheme while running.
    @available(iOS 13.0, *)
    @objc(scene:openURLContexts:)
    public func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) -> Bool {
        for context in URLContexts {
            Deeplinkly.handleLink(context.url)
        }
        return false
    }

    /// Points the SDK's delivery funnel at this plugin's channel and flushes
    /// anything buffered while nothing was listening.
    ///
    /// Idempotent, and called from both `flutterReady` and the `resumed`
    /// lifecycle event: an engine that detached and came back needs the
    /// listener re-pointed, and a flush with an empty buffer sends nothing.
    private func attachDeepLinkListener() {
        // SdkRuntime holds the listener for the process lifetime, so there is
        // nothing to retain here.
        Deeplinkly.setDeepLinkListener(MethodChannelDeepLinkListener(channel: channel))
    }

    // MARK: - Flutter MethodChannel Handling
    @objc public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        // Answered before the enabled check: the install id is generated
        // locally and needs no API key, on both platforms.
        if call.method == "getDeeplinklyId" {
            result(Deeplinkly.getDeeplinklyId())
            return
        }
        guard Deeplinkly.isEnabled else {
            result([
                "success": false,
                "error_code": "SDK_DISABLED",
                "error_message": "Deeplinkly SDK disabled (missing API key).",
            ])
            return
        }

        let args = call.arguments as? [String: Any]

        switch call.method {
        case "getPlatformVersion":
            result("iOS \(UIDevice.current.systemVersion)")

        case "flutterReady":
            // Dart has attached its onDeepLink handler. Anything the native
            // side produced before now — the pasteboard read in particular —
            // is buffered in SdkRuntime and flushes here.
            attachDeepLinkListener()
            result(true)

        case "onLifecycleChange":
            let state = args?["state"] as? String ?? ""
            if state == "resumed" {
                // Re-attached rather than assumed: an engine that detached and
                // came back cleared the listener on the way out.
                attachDeepLinkListener()
                // Secondary trigger for the open ping, matching Android's
                // bridge. AppOpenReporter's didBecomeActive observer is the
                // primary one and normally beats this; both route through the
                // same rate limit, so a double trigger is a no-op rather than a
                // duplicate send.
                Deeplinkly.onForeground()
            }
            result(true)

        case "setDebugMode":
            Deeplinkly.setDebugMode(args?["enabled"] as? Bool ?? false)
            result(true)

        case "getInstallAttribution":
            result(Deeplinkly.getInstallAttribution())

        case "getInitialUniversalLink":
            if let url = Deeplinkly.takePendingLink() {
                result(["url": url.absoluteString])
            } else {
                result(nil)
            }

        case "disableTracking":
            Deeplinkly.setTrackingEnabled(!(args?["disabled"] as? Bool ?? false))
            result(true)

        case "setAttributionLevel":
            let raw = (args?["level"] as? String ?? "").lowercased()
            guard let level = AttributionLevel(rawValue: raw) else {
                result(false)
                return
            }
            result(Deeplinkly.setAttributionLevel(level))

        case "getAttributionLevel":
            result(Deeplinkly.getAttributionLevel().rawValue)

        case "setCheckPasteboardOnInstall":
            // Enabling from Dart is inherently late — initialisation has
            // already run — so unless the caller opts out, read straight away
            // rather than waiting for a next launch the pasteboard may not
            // survive to.
            Deeplinkly.setCheckPasteboardOnInstall(
                args?["enabled"] as? Bool ?? false,
                checkNow: args?["check_now"] as? Bool ?? true
            )
            result(true)

        case "willShowPasteboardBanner":
            Deeplinkly.willShowPasteboardBanner { willShow in result(willShow) }

        case "checkPasteboardNow":
            Deeplinkly.checkPasteboardNow()
            result(true)

        case "setCustomUserId", "setUserId":
            Deeplinkly.setUserId(args?["user_id"] as? String)
            result(true)

        case "logEvent":
            Deeplinkly.logEvent(
                args?["event_name"] as? String ?? "",
                parameters: args?["parameters"] as? [String: Any] ?? [:]
            ) { ok in result(ok) }

        case "generateLink":
            guard let content = args?["content"] as? [String: Any],
                let options = args?["options"] as? [String: Any]
            else {
                result([
                    "success": false,
                    "error_code": "INVALID",
                    "error_message": "Expected content/options",
                ])
                return
            }
            // Merged rather than parsed into models and rebuilt, so the map
            // Dart built crosses to the backend untouched.
            let payload = content.merging(options, uniquingKeysWith: { $1 })
            Deeplinkly.generateLink(payload: payload) { response in result(response) }

        default:
            result(FlutterMethodNotImplemented)
        }
    }
}
