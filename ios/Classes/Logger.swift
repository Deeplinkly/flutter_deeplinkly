import Foundation

enum Logger {
    private static let tag = "Deeplinkly"

    static func d(_ msg: String) {
        print("✅ [\(tag)] \(msg)")
    }

    static func w(_ msg: String) {
        print("⚠️ [\(tag)] \(msg)")
    }

    static func e(_ msg: String, _ error: Error? = nil) {
        if let error = error {
            print("❌ [\(tag)] \(msg) – \(error.localizedDescription)")
        } else {
            print("❌ [\(tag)] \(msg)")
        }
    }
}
