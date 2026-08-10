// EnrichmentSender.swift
import Foundation

enum EnrichmentSender {
    /// - Parameter force: send even without attribution evidence. Used by
    ///   `StartupEnrichment` when its wait times out — an install with no link
    ///   behind it is still an install.
    static func sendOnce(
        enrichmentData: [String: String?],
        source: String,
        apiKey: String,
        force: Bool = false
    ) {
        guard !TrackingPreferences.isTrackingDisabled() else { return }

        // The flag used to be keyed on source alone and never cleared, which
        // made every source once-per-install *forever*: the second and every
        // later deep link never enriched, and setUserId (source
        // "custom_user_id") only ever linked the first login on the device.
        // Keying on what is being reported lets a genuinely new event through
        // while still collapsing duplicates.
        let key = dedupeKey(for: enrichmentData, source: source)
        if Prefs.bool(for: key) { return }

        let data = enrichmentData

        // Only send if we have attribution hints. "code" belongs here too —
        // Android counts it, and a code-only deferred link was silently dropped.
        let keys = [
            "click_id", "code", "utm_source", "utm_medium", "utm_campaign",
            "gclid", "fbclid", "ttclid",
        ]
        let hasAttr = keys.contains { (data[$0] ?? nil)?.isEmpty == false }
        guard hasAttr || force else {
            Logger.d("Skipping enrichment: no attribution")
            return
        }

        // Latch only once the payload is actually delivered. Setting it up
        // front marked a permanently failing enrichment as sent.
        NetworkUtils.sendEnrichment(data, apiKey: apiKey) { delivered in
            if delivered { Prefs.set(true, for: key) }
        }
    }

    /// Identity of this enrichment: the source plus whatever attribution it
    /// carries. Two calls that would report the same thing collapse to one.
    private static func dedupeKey(for data: [String: String?], source: String) -> String {
        let identityKeys = ["click_id", "code", "custom_user_id"]
        let identity =
            identityKeys
            .compactMap { key -> String? in
                guard let value = data[key] ?? nil, !value.isEmpty else { return nil }
                return "\(key)=\(value)"
            }
            .joined(separator: "&")
        // Not hashValue: Swift seeds String hashing per process, so the key
        // would differ on every launch and dedupe nothing.
        return identity.isEmpty
            ? "\(source)_enriched"
            : "\(source)_enriched_\(identity)"
    }
}
