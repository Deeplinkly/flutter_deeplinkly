// StartupEnrichment.swift
import Flutter
import UIKit
import Foundation

enum StartupEnrichment {
  private static var sentThisProcess = false

  static func schedule(apiKey: String, channel: FlutterMethodChannel) {
    guard !sentThisProcess else { return }
    DispatchQueue.global(qos: .utility).async {
      let ready = waitUntilAttribution(timeout: 30)
      guard ready else {
        Logger.d("StartupEnrichment: no attribution in timeout")
        return
      }
      var base = EnrichmentUtils.collect()
      let attr = AttributionStore.get()
      for (k, v) in attr { base[k] = v as? String }
      EnrichmentSender.sendOnce(enrichmentData: base, source: "app_start", apiKey: apiKey)
      sentThisProcess = true
      Logger.d("StartupEnrichment: sent.")
    }
  }

  private static func waitUntilAttribution(timeout: Int) -> Bool {
    let end = Date().addingTimeInterval(TimeInterval(timeout))
    while Date() < end {
      let attr = AttributionStore.get()
      let hasUtm = !(attr["utm_source"] as? String ?? "").isEmpty
      let hasIds = !((attr["gclid"] as? String ?? "").isEmpty
                     && (attr["fbclid"] as? String ?? "").isEmpty
                     && (attr["ttclid"] as? String ?? "").isEmpty)
      if hasUtm || hasIds { return true }
      Thread.sleep(forTimeInterval: 1)
    }
    return false
  }
}