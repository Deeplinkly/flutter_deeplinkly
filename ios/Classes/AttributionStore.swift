// AttributionStore.swift
import Foundation
enum AttributionStore {
  private static let key = "initial_attribution"
  static func saveOnce(map: [String: String?]) {
    let defaults = UserDefaults.standard
    if defaults.string(forKey: key) != nil { return }
    let filtered = map.compactMapValues { $0 }
    if let data = try? JSONSerialization.data(withJSONObject: filtered) {
      defaults.set(String(data: data, encoding: .utf8), forKey: key)
    }
  }
  static func get() -> [String: Any] {
    guard let s = UserDefaults.standard.string(forKey: key),
          let data = s.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
    return obj
  }
}