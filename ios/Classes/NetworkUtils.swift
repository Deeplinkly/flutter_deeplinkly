// NetworkUtils.swift
import Foundation

enum NetworkError: Error, LocalizedError {
    case message(String)
    case http(status: Int, body: [String: Any])

    /// A 4xx will not start succeeding on retry - the API key is invalid, the
    /// account is suspended, or the payload is malformed. 408 and 429 are the
    /// exceptions: both are transient and worth backing off on.
    var isTerminal: Bool {
        if case let .http(status, _) = self {
            return (400...499).contains(status) && status != 408 && status != 429
        }
        return false
    }

    var errorDescription: String? {
        switch self {
        case .message(let m):
            return m
        case .http(let status, let body):
            let detail = body["message"] as? String ?? body["error"] as? String ?? ""
            return detail.isEmpty ? "HTTP \(status)" : "HTTP \(status): \(detail)"
        }
    }
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

        // IDs on every call (matches Django links.views enrich / resolve)
        if let custom = Prefs.customUserId() {
            req.setValue(custom, forHTTPHeaderField: "X-Deeplinkly-Custom-User-Id")
        }
        req.setValue(DeviceIdManager.getOrCreate(), forHTTPHeaderField: "X-Deeplinkly-User-Id")

        if let body = body {
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        URLSession.shared.dataTask(with: req) { data, response, err in
            if let err = err {
                completion(.failure(err))
                return
            }
            guard let http = response as? HTTPURLResponse else {
                completion(.failure(NetworkError.message("No HTTP response")))
                return
            }
            let status = http.statusCode
            guard (200...299).contains(status) else {
                // Keep the parsed body so callers can read the backend's ER_0xx
                // code and decide whether the failure is worth retrying.
                let parsed =
                    (try? JSONSerialization.jsonObject(with: data ?? Data())) as? [String: Any]
                    ?? [:]
                completion(.failure(NetworkError.http(status: status, body: parsed)))
                return
            }
            let raw = data ?? Data()
            let json: [String: Any]
            if raw.isEmpty {
                json = [:]
            } else {
                json = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] ?? [:]
            }
            completion(.success(json))
        }.resume()
    }

    static func resolveClick(
        clickId: String?,
        code: String?,
        fingerprint: [String: Any],
        apiKey: String,
        onSuccess: @escaping ([String: Any]) -> Void,
        // Carries the Error rather than a message: the caller needs the status
        // code to tell a retryable failure from a rejected one (isTerminal).
        onError: @escaping (Error) -> Void
    ) {
        var body: [String: Any] = ["fingerprint": fingerprint]
        if let c = clickId { body["click_id"] = c }
        if let c = code { body["code"] = c }

        request(DomainConfig.resolveClick, method: "POST", apiKey: apiKey, body: body) { result in
            switch result {
            case .success(let json):
                onSuccess(json)
            case .failure(let err):
                onError(err)
            }
        }
    }

    /// True when the backend did not recognise the click id we asked about.
    ///
    /// /resolve answers an unknown click_id with HTTP 200 and
    /// `{"click_id": null, "params": {}, "stale": true}` rather than a 404, so
    /// the flag is the only signal that nothing was actually resolved.
    static func isStale(json: [String: Any]) -> Bool {
        (json["stale"] as? Bool) == true || (json["stale"] as? NSNumber)?.boolValue == true
    }

    static func extractParams(json: [String: Any], clickId: String?) -> [String: Any] {
        // Falling back to the *requested* click id would report a stale click as
        // a live one, so on a stale response the id has to come from the body
        // (where it is null) and never from what we asked for.
        let resolvedId: Any =
            isStale(json: json)
            ? (json["click_id"] as? String ?? NSNull())
            : (clickId ?? (json["click_id"] as? String ?? NSNull()))

        var out: [String: Any] = ["click_id": resolvedId]
        if let params = json["params"] as? [String: Any] {
            out["params"] = params
        }
        if let prob = json["probability"] {
            out["probability"] = prob
        }
        return out
    }

    /// The payload delivered when /resolve never answered.
    ///
    /// Dart reads a deep link's values off `params` (see `extractParams`), so
    /// the flat map of query parameters the fallback used to send was invisible
    /// to any app written against a resolved link - the link arrived carrying
    /// nothing the app knew how to read. Fallbacks now keep the same
    /// {click_id, params} envelope, leaving Dart one shape to handle whether or
    /// not the backend answered.
    static func fallbackPayload(clickId: String?, localParams: [String: String]) -> [String: Any] {
        var params = localParams
        // click_id is the envelope's own key; repeating it inside params would
        // have Dart read the same value from two places.
        params.removeValue(forKey: "click_id")
        let id: Any = clickId ?? NSNull()
        return ["click_id": id, "params": params]
    }

    /// Attribution keys the backend surfaces inside the resolve response's "params".
    private static let attributionKeys = [
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "gclid", "fbclid", "ttclid",
    ]

    /// Builds the normalized attribution snapshot persisted by AttributionStore.
    ///
    /// The backend nests UTM and ad-click params inside "params" (click-time values
    /// merged over the link's own metadata) - see _click_attribution_params in
    /// links/views.py. Reading them off the top level of the map returned by
    /// `extractParams` always yielded nil, so every snapshot carried nothing but a
    /// source and a click_id. Every caller goes through here so that nesting is
    /// unwrapped in exactly one place.
    static func attributionSnapshot(
        resolved: [String: Any],
        source: String,
        fallbackClickId: String? = nil
    ) -> [String: String?] {
        let params = resolved["params"] as? [String: Any]
        var out: [String: String?] = [
            "source": source,
            "click_id": (resolved["click_id"] as? String) ?? fallbackClickId,
        ]
        for key in attributionKeys {
            // params values arrive from JSON, so they are not necessarily Strings.
            guard let raw = params?[key], !(raw is NSNull) else {
                out[key] = nil
                continue
            }
            let value = (raw as? String) ?? String(describing: raw)
            out[key] = value.isEmpty ? nil : value
        }
        return out
    }

    /// - Parameter completion: whether the payload reached the server (a
    ///   terminal rejection counts as delivered — it will never succeed).
    static func sendEnrichment(
        _ data: [String: Any?],
        apiKey: String,
        completion: ((Bool) -> Void)? = nil
    ) {
        guard !TrackingPreferences.isTrackingDisabled() else {
            completion?(false)
            return
        }
        let filtered = data.compactMapValues { $0 }
        request(DomainConfig.enrich, method: "POST", apiKey: apiKey, body: filtered) { result in
            switch result {
            case .success:
                completion?(true)
            case .failure(let e):
                if !isTerminal(e) {
                    RetryQueue.enqueue(type: "enrichment", payload: filtered)
                }
                completion?(isTerminal(e))
            }
        }
    }

    /// Queueing a request the server has already rejected outright just replays it
    /// on every launch forever - a suspended account (402) or revoked key (403)
    /// would never drain.
    static func isTerminal(_ error: Error) -> Bool {
        (error as? NetworkError)?.isTerminal == true
    }

    static func sendEnrichmentNow(payload: [String: Any], apiKey: String) throws {
        try sendNow(DomainConfig.enrich, payload: payload, apiKey: apiKey)
    }

    /// Rethrows the original error rather than flattening it into an NSError, so
    /// callers can still test it with `isTerminal`.
    private static func sendNow(_ url: String, payload: [String: Any], apiKey: String) throws {
        let sem = DispatchSemaphore(value: 0)
        var failure: Error?
        request(url, method: "POST", apiKey: apiKey, body: payload) { result in
            if case let .failure(e) = result { failure = e }
            sem.signal()
        }
        sem.wait()
        if let failure = failure { throw failure }
    }

    static func logEvent(
        eventName: String,
        parameters: [String: Any],
        apiKey: String,
        completion: @escaping (Bool) -> Void
    ) {
        guard !TrackingPreferences.isTrackingDisabled() else {
            completion(false)
            return
        }
        let body: [String: Any] = ["event_name": eventName, "parameters": parameters]
        request(DomainConfig.logEvent, method: "POST", apiKey: apiKey, body: body) { result in
            switch result {
            case .success:
                completion(true)
            case .failure(let e):
                if !isTerminal(e) {
                    RetryQueue.enqueue(type: "event", payload: body)
                }
                completion(false)
            }
        }
    }

    static func sendEventNow(payload: [String: Any], apiKey: String) throws {
        try sendNow(DomainConfig.logEvent, payload: payload, apiKey: apiKey)
    }

    static func reportError(apiKey: String, message: String, stack: String, clickId: String? = nil)
    {
        guard !TrackingPreferences.isTrackingDisabled() else { return }
        var payload: [String: Any] = ["message": message, "stack": stack]
        if let c = clickId { payload["click_id"] = c }
        request(DomainConfig.sdkError, method: "POST", apiKey: apiKey, body: payload) { result in
            if case let .failure(e) = result, !isTerminal(e) {
                RetryQueue.enqueue(type: "error", payload: payload)
            }
        }
    }

    static func sendErrorNow(payload: [String: Any], apiKey: String) throws {
        try sendNow(DomainConfig.sdkError, payload: payload, apiKey: apiKey)
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
                    // Surface the backend's own ER_0xx code where there is one -
                    // "ER_011" (billing paused) is actionable, "LINK_ERROR" is not.
                    var code = "LINK_ERROR"
                    var message = e.localizedDescription
                    if case let NetworkError.http(status, body) = e {
                        code = body["code"] as? String ?? "HTTP_\(status)"
                        message =
                            body["message"] as? String
                            ?? body["error"] as? String
                            ?? "Link generation failed (HTTP \(status))"
                    }
                    completion([
                        "success": false,
                        "error_code": code,
                        "error_message": message,
                    ])
                }
            }
        }
    }
}
