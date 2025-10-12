// FlutterDeeplinklyPlugin.swift
import Flutter
import UIKit

public class FlutterDeeplinklyPlugin: NSObject, FlutterPlugin {
    private var channel: FlutterMethodChannel!
    private var apiKey: String = ""
    private var sdkEnabled = false

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = FlutterDeeplinklyPlugin()
        instance.channel = FlutterMethodChannel(
            name: "deeplinkly/channel",
            binaryMessenger: registrar.messenger()
        )
        registrar.addMethodCallDelegate(instance, channel: instance.channel)
        registrar.addApplicationDelegate(instance)

        // ✅ Initialize immediately so `sdkEnabled` is true before any method calls
        instance.bootstrap()
    }

    // Initialize SDK pieces that don’t need UIApplication
    private func bootstrap() {
        // Read API key from plugin bundle or main bundle
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
        StartupEnrichment.schedule(apiKey: apiKey, channel: channel)
        PasteboardHandler.check(channel: channel, apiKey: apiKey)
        RetryQueue.retryAll(apiKey: apiKey)
    }

    // Keep this if you want; it will run on future launches (or not at all on first).
    // No harm either way since `bootstrap()` already initialized.
    public func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Optional: nothing needed here anymore
        return true
    }

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
                  let options = args["options"] as? [String: Any] else {
                result([
                    "success": false,
                    "error_code": "INVALID",
                    "error_message": "Expected content/options",
                ])
                return
            }

            let payload = content.merging(options, uniquingKeysWith: { $1 })
            print("payload:", payload)

            NetworkUtils.generateLink(payload: payload, apiKey: apiKey) { response in
                DispatchQueue.main.async {
                    if let response = response as? [String: Any] {
                        print("iOS → Flutter response:", response)
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
