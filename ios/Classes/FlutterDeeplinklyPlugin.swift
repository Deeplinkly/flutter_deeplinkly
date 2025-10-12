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
        instance.bootstrap()

            // ✅ Flush any pending universal link that arrived before channel setup
            if let pending = pendingUniversalLink {
                pendingUniversalLink = nil
                DeepLinkHandler.handle(url: pending, channel: ch, apiKey: storedApiKey ?? "")
            }
    }

    // MARK: - Universal Link Bridge
    /// Called from AppDelegate when a Universal Link is opened.
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

        StartupEnrichment.schedule(apiKey: apiKey, channel: channel)
//        PasteboardHandler.check(channel: channel, apiKey: apiKey)
        RetryQueue.retryAll(apiKey: apiKey)
    }


    // MARK: - Flutter MethodChannel Handling
    @objc public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
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

        case "setCustomUserId":
            let userId = (call.arguments as? [String: Any])?["user_id"] as? String
            Prefs.setCustomUserId(userId)
            UserIdManager.updateCustomUserId(newId: userId, apiKey: apiKey)
            result(true)

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
