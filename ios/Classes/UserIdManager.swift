import Foundation

enum UserIdManager {
    static func updateCustomUserId(newId: String?, apiKey: String) {
        // Get the currently stored ID
        let previousId = Prefs.customUserId()

        // If it's the same, no change
        if previousId == newId { return }

        // Persist the new ID
        Prefs.setCustomUserId(newId)

        // Optional: log it
        Logger.d("UserIdManager: updated custom user ID → \(newId ?? "nil")")

        // If you want to trigger enrichment immediately after ID change:
        var enrichmentData = EnrichmentUtils.collect()
        enrichmentData["custom_user_id"] = newId
        EnrichmentSender.sendOnce(
            enrichmentData: enrichmentData,
            source: "custom_user_id",
            apiKey: apiKey
        )
    }
}
