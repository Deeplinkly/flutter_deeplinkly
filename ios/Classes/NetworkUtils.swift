// NetworkUtils.swift
import Foundation

enum NetworkError: Error {
    case message(String)
}

enum NetworkUtils {
    private static func request(
        _ url: String,
        method: String = "GET",
        apiKey: String,
        body: [String: Any]? = nil,
        completion: @escaping (Result<[String: Any], Error>) -> Void
    ) {
        guard let u = URL(string: url) else {
            completion(.failure(NetworkError.message("Bad URL")))
            return
        }

        var req = URLRequest(url: u, timeoutInterval: 15)
        req.httpMethod = method
        req.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")

        // IDs on every call
        if let custom = Prefs.customUserId() {
            req.setValue(custom, forHTTPHeaderField: "X-Custom-User-Id")
        }
        req.setValue(DeviceIdManager.getOrCreate(), forHTTPHeaderField: "X-Deeplinkly-User-Id")

        if let body = body {
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        URLSession.shared.dataTask(with: req) { data, _, err in
            if let err = err {
                completion(.failure(err))
                return
            }
            guard let data = data else {
                completion(.failure(NetworkError.message("No data")))
                return
            }
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            completion(.success(json))
        }.resume()
    }

    static func resolveClick(
        _ url: String,
        apiKey: String,
        onSuccess: @escaping ([String: Any]) -> Void,
        onError: @escaping (String) -> Void
    ) {
        request(url, apiKey: apiKey) { result in
            switch result {
            case .success(let json):
                onSuccess(json)
            case .failure(let err):
                onError(err.localizedDescription)
            }
        }
    }

    static func extractParams(json: [String: Any], clickId: String?) -> [String: Any] {
        var out: [String: Any] = ["click_id": clickId ?? (json["click_id"] as? String ?? NSNull())]
        if let params = json["params"] as? [String: Any] {
            for (k, v) in params { out[k] = v }
        }
        return out
    }

    static func sendEnrichment(_ data: [String: Any?], apiKey: String) {
        guard !TrackingPreferences.isDisabled() else { return }
        let filtered = data.compactMapValues { $0 }
        request(DomainConfig.enrich, method: "POST", apiKey: apiKey, body: filtered) { result in
            if case .failure = result {
                RetryQueue.enqueue(type: "enrichment", payload: filtered)
            }
        }
    }

    static func sendEnrichmentNow(payload: [String: Any], apiKey: String) throws {
        let sem = DispatchSemaphore(value: 0)
        var err: String?
        request(DomainConfig.enrich, method: "POST", apiKey: apiKey, body: payload) { result in
            if case let .failure(e) = result { err = e.localizedDescription }
            sem.signal()
        }
        sem.wait()
        if let e = err {
            throw NSError(domain: "enrich", code: -1, userInfo: [NSLocalizedDescriptionKey: e])
        }
    }

    static func reportError(apiKey: String, message: String, stack: String, clickId: String? = nil)
    {
        guard !TrackingPreferences.isDisabled() else { return }
        var payload: [String: Any] = ["message": message, "stack": stack]
        if let c = clickId { payload["click_id"] = c }
        request(DomainConfig.sdkError, method: "POST", apiKey: apiKey, body: payload) { result in
            if case .failure(_) = result {
                RetryQueue.enqueue(type: "error", payload: payload)
            }
        }
    }

    static func sendErrorNow(payload: [String: Any], apiKey: String) throws {
        let sem = DispatchSemaphore(value: 0)
        var err: String?
        request(DomainConfig.sdkError, method: "POST", apiKey: apiKey, body: payload) { result in
            if case let .failure(e) = result { err = e.localizedDescription }
            sem.signal()
        }
        sem.wait()
        if let e = err {
            throw NSError(domain: "error", code: -1, userInfo: [NSLocalizedDescriptionKey: e])
        }
    }

    static func generateLink(
        payload: [String: Any],
        apiKey: String,
        completion: @escaping ([String: Any]) -> Void
    ) {
        request(DomainConfig.generateLink, method: "POST", apiKey: apiKey, body: payload) {
            result in
            DispatchQueue.main.async {
                switch result {
                case .success(let json):
                    if let url = json["url"] as? String {
                        completion([
                            "success": true,
                            "url": url,
                        ])
                    } else {
                        completion([
                            "success": false,
                            "error_code": "NO_URL",
                            "error_message": "No 'url' field in response",
                        ])
                    }
                case .failure(let e):
                    completion([
                        "success": false,
                        "error_code": "LINK_ERROR",
                        "error_message": e.localizedDescription,
                    ])
                }
            }
        }
    }
}
