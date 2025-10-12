// AdvertisingId.swift
import AdSupport
import AppTrackingTransparency

enum AdvertisingId {
  static func fetch(completion: @escaping (String?) -> Void) {
    guard #available(iOS 14, *) else {
      let idfa = ASIdentifierManager.shared().isAdvertisingTrackingEnabled ? ASIdentifierManager.shared().advertisingIdentifier.uuidString : nil
      completion(idfa)
      return
    }
    ATTrackingManager.requestTrackingAuthorization { status in
      if status == .authorized {
        let idfa = ASIdentifierManager.shared().advertisingIdentifier.uuidString
        Prefs.set(idfa, for: "advertising_id")
        completion(idfa)
      } else {
        completion(nil) // fallback to IDFV or Keychain UUID elsewhere if you want
      }
    }
  }
}
