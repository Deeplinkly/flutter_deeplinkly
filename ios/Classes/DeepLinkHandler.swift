// DeepLinkHandler.swift
import Flutter
import UIKit
import Foundation
enum DeepLinkHandler {
    static func handle(url: URL, channel: FlutterMethodChannel, apiKey: String) {
        Logger.d("handle(url: \(url.absoluteString))")
        let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let clickId = comps?.queryItems?.first(where: { $0.name == "click_id" })?.value
        let code = url.pathComponents.dropFirst().first  // /<code>

        guard clickId != nil || code != nil else { return }

        var enrichment = EnrichmentUtils.collect()
        enrichment["ios_reported_at"] = String(Date().timeIntervalSince1970 * 1000)
        if let c = clickId {
            enrichment["click_id"] = c
        } else if let c = code {
            enrichment["code"] = c
        }

        let resolveUrl: String
        if let c = clickId {
            resolveUrl = "\(DomainConfig.resolveClick)?click_id=\(c)"
        } else {
            resolveUrl = "\(DomainConfig.resolveClick)?code=\(code!)"
        }

        NetworkUtils.resolveClick(resolveUrl, apiKey: apiKey) { json in
            let dartMap = NetworkUtils.extractParams(json: json, clickId: clickId)
            if let resolvedClick = dartMap["click_id"] as? String {
                enrichment["click_id"] = resolvedClick
            }

            // Persist first-touch attribution (normalized keys)
            let normalized: [String: String?] = [
                "source": "deep_link",
                "click_id": (dartMap["click_id"] as? String) ?? clickId,
                "utm_source": dartMap["utm_source"] as? String,
                "utm_medium": dartMap["utm_medium"] as? String,
                "utm_campaign": dartMap["utm_campaign"] as? String,
                "utm_term": dartMap["utm_term"] as? String,
                "utm_content": dartMap["utm_content"] as? String,
                "gclid": dartMap["gclid"] as? String,
                "fbclid": dartMap["fbclid"] as? String,
                "ttclid": dartMap["ttclid"] as? String,
            ]
            AttributionStore.saveOnce(map: normalized)

            // Notify Flutter
            DispatchQueue.main.async {
                channel.invokeMethod("onDeepLink", arguments: dartMap)
            }

            // Enrich once for deep_link
            EnrichmentSender.sendOnce(
                enrichmentData: enrichment, source: "deep_link", apiKey: apiKey)
        } onError: { err in
            Logger.e("resolveClick failed: \(err)")
            // Fallback: pass raw UTM params from URL
            var fallback: [String: Any?] = [
                "click_id": clickId
            ]
            [
                "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "gclid",
                "fbclid", "ttclid",
            ].forEach { key in
                fallback[key] = comps?.queryItems?.first(where: { $0.name == key })?.value
            }
            AttributionStore.saveOnce(map: fallback.mapValues { $0 as? String })
            DispatchQueue.main.async {
                channel.invokeMethod("onDeepLink", arguments: fallback)
            }
            NetworkUtils.reportError(apiKey: apiKey, message: "resolve exception", stack: err)
        }
    }
}
