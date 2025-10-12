import UIKit

enum EnrichmentUtils {
    static func collect() -> [String: String] {
        var out: [String: String] = [:]
        out["custom_user_id"] = Prefs.customUserId() ?? ""
        out["deeplinkly_device_id"] = DeviceIdManager.getOrCreate()

        let device = UIDevice.current
        let screen = UIScreen.main.bounds
        let scale = UIScreen.main.scale
        let locale = Locale.current
        let tz = TimeZone.current.identifier

        let language = Locale.preferredLanguages.first ?? locale.languageCode ?? "en"
        let region = locale.regionCode ?? "NA"

        // Explicitly cast every numeric value to Double → Int → String
        let screenWidth  = String(Int(Double(screen.width)  * Double(scale)))
        let screenHeight = String(Int(Double(screen.height) * Double(scale)))
        let pixelRatio   = String(format: "%.2f", scale)

        let dict: [String: String] = [
            "platform": "ios",
            "device_model": device.model,
            "os_version": device.systemVersion,
            "screen_width": screenWidth,
            "screen_height": screenHeight,
            "pixel_ratio": pixelRatio,
            "app_version": (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "",
            "app_build_number": (Bundle.main.infoDictionary?["CFBundleVersion"] as? String) ?? "",
            "locale": locale.identifier,
            "language": language,
            "region": region,
            "timezone": tz,
            "last_opened_at": ISO8601DateFormatter().string(from: Date())
        ]

        out.merge(dict) { _, new in new }
        return out
    }
}
