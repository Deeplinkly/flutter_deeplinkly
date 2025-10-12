import Foundation

enum RetryQueue {
    private static let key = "sdk_retry_queue"
    private static let maxCount = 50

    // Enqueue payload for retry
    static func enqueue(type: String, payload: [String: Any]) {
        var queue = (UserDefaults.standard.array(forKey: key) as? [String]) ?? []
        let item: [String: Any] = ["type": type, "payload": payload]

        if let data = try? JSONSerialization.data(withJSONObject: item, options: []),
           let jsonString = String(data: data, encoding: .utf8) {
            queue.append(jsonString)
            if queue.count > maxCount { queue.removeFirst() }
            UserDefaults.standard.set(queue, forKey: key)
        }
    }

    // Get all items
    static func items() -> [String] {
        return (UserDefaults.standard.array(forKey: key) as? [String]) ?? []
    }

    // Remove a specific item
    static func remove(_ s: String) {
        var queue = items()
        if let idx = queue.firstIndex(of: s) {
            queue.remove(at: idx)
            UserDefaults.standard.set(queue, forKey: key)
        }
    }

    // Retry all items in queue
    static func retryAll(apiKey: String) {
        guard !TrackingPreferences.isDisabled() else { return }

        for s in items() {
            do {
                guard let data = s.data(using: .utf8),
                      let obj = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
                      let type = obj["type"] as? String,
                      let payloadObj = obj["payload"] else {
                    continue
                }

                let payload: [String: Any]
                if let dict = payloadObj as? [String: Any] {
                    payload = dict
                } else if let payloadStr = payloadObj as? String,
                          let payloadData = payloadStr.data(using: .utf8),
                          let parsed = try JSONSerialization.jsonObject(with: payloadData, options: []) as? [String: Any] {
                    payload = parsed
                } else {
                    continue
                }

                switch type {
                case "enrichment":
                    try NetworkUtils.sendEnrichmentNow(payload: payload, apiKey: apiKey)
                case "error":
                    try NetworkUtils.sendErrorNow(payload: payload, apiKey: apiKey)
                default:
                    Logger.w("RetryQueue: Unknown type \(type)")
                }

                remove(s)
            } catch {
                Logger.e("RetryQueue retry failed", error)
            }
        }
    }
}
