// TrackingPreferences.swift
import Foundation
enum TrackingPreferences {
    private static let key = "tracking_disabled"
    static func isDisabled() -> Bool { UserDefaults.standard.bool(forKey: key) }
    static func setTrackingDisabled(_ disabled: Bool) {
        UserDefaults.standard.set(disabled, forKey: key)
    }
}