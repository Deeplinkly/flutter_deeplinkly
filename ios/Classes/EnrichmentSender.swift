// EnrichmentSender.swift
import Foundation

enum EnrichmentSender {
  static func sendOnce(enrichmentData: [String: String?], source: String, apiKey: String) {
    guard !TrackingPreferences.isDisabled() else { return }
    let key = "\(source)_enriched"
    if Prefs.bool(for: key) == true { return }

    // If IDFA was previously cached, inject it
    var data = enrichmentData
    if data["advertising_id"] == nil, let cached = Prefs.string(for: "advertising_id") {
      data["advertising_id"] = cached
    }

    // Only send if we have attribution hints
    let keys = ["click_id","utm_source","utm_medium","utm_campaign","gclid","fbclid","ttclid"]
    let hasAttr = keys.contains { (data[$0] ?? nil)?.isEmpty == false }
    guard hasAttr else { Logger.d("Skipping enrichment: no attribution"); return }

    NetworkUtils.sendEnrichment(data, apiKey: apiKey)
    Prefs.set(true, for: key)
  }
}