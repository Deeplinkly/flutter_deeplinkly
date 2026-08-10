// DeepLinkHandler.swift
import Flutter
import Foundation
import UIKit

enum DeepLinkHandler {
    /// Matches Android's `resolveClickWithRetry(maxRetries = 2, initialDelayMs = 50)`.
    private static let maxAttempts = 3
    private static let initialRetryDelay: TimeInterval = 0.05

    /// Links currently being resolved.
    ///
    /// `PasteboardHandler` queues a link and resolves it immediately, while
    /// `drainPendingResolves` runs the queue on the same launch — without this
    /// the pasteboard link would be resolved twice, delivering `onDeepLink`
    /// twice for one install. A Universal Link arriving through both the
    /// application- and scene-delegate paths is the same problem.
    private static let inFlightLock = NSLock()
    private static var inFlight: Set<String> = []

    private static func beginIfIdle(_ identity: String) -> Bool {
        inFlightLock.lock()
        defer { inFlightLock.unlock() }
        return inFlight.insert(identity).inserted
    }

    private static func finish(_ identity: String) {
        inFlightLock.lock()
        inFlight.remove(identity)
        inFlightLock.unlock()
    }

    /// - Parameter source: how this link reached us. "deep_link" for a Universal
    ///   Link or custom scheme, "clipboard" for the deferred pasteboard path.
    ///   The backend reads it off the enrichment payload to stamp
    ///   `ClickEvent.attribution_source` — without it a deferred iOS install is
    ///   filed as an install_referrer, which is not a thing on this platform.
    static func handle(
        url: URL,
        channel: FlutterMethodChannel,
        apiKey: String,
        source: String = "deep_link"
    ) {
        Logger.d("handle(url: \(url.absoluteString), source: \(source))")
        let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let clickId = comps?.queryItems?.first(where: { $0.name == "click_id" })?.value
        let code = url.pathComponents.dropFirst().first

        guard clickId != nil || code != nil else { return }

        let pending = DeepLinkQueue.PendingResolve(
            clickId: clickId, code: code, uri: url.absoluteString, source: source
        )
        guard beginIfIdle(pending.identity) else {
            Logger.d("Already resolving \(pending.identity); skipping duplicate.")
            return
        }

        var enrichment = EnrichmentUtils.collect()
        enrichment["ios_reported_at"] = String(Date().timeIntervalSince1970 * 1000)
        enrichment["source"] = source
        if let c = clickId {
            enrichment["click_id"] = c
        } else if let c = code {
            enrichment["code"] = c
        }

        // Subset of enrichment used for fingerprint matching
        let fingerprintKeys: Set<String> = [
            "platform", "os_version", "screen_width", "screen_height",
            "pixel_ratio", "hardware_concurrency", "locale", "language", "timezone",
        ]
        let fingerprint: [String: Any] = enrichment
            .filter { fingerprintKeys.contains($0.key) }
            .reduce(into: [:]) { $0[$1.key] = $1.value }

        resolveWithRetry(
            clickId: clickId, code: code, fingerprint: fingerprint, apiKey: apiKey,
            attempt: 1
        ) { result in
            defer { finish(pending.identity) }
            switch result {
            case .success(let json):
                // An unknown click id comes back 200 with stale: true. Delivering
                // it would fire a deep link carrying no params at all, on every
                // cold start until the cached id was cleared.
                if NetworkUtils.isStale(json: json) {
                    Logger.w("Resolve returned a stale click; suppressing delivery.")
                    DeepLinkQueue.remove(pending)
                    return
                }

                let dartMap = NetworkUtils.extractParams(json: json, clickId: clickId)
                if let resolvedClick = dartMap["click_id"] as? String {
                    enrichment["click_id"] = resolvedClick
                }

                // Persist first-touch attribution (normalized keys)
                let normalized = NetworkUtils.attributionSnapshot(
                    resolved: dartMap, source: source, fallbackClickId: clickId
                )
                AttributionStore.saveOnce(map: normalized)

                // Notify Flutter (buffered if Dart is not listening yet). The
                // queue entry is only dropped once the app has really received
                // the link — being killed while it sat in the buffer would
                // otherwise lose it, and the pasteboard copy is long gone.
                SdkRuntime.postToFlutter(channel, method: "onDeepLink", args: dartMap) {
                    DeepLinkQueue.remove(pending)
                }
                EnrichmentSender.sendOnce(
                    enrichmentData: enrichment, source: source, apiKey: apiKey)

            case .failure(let err):
                Logger.e("resolveClick failed", err)

                if NetworkUtils.isTerminal(err) {
                    // A revoked key or a suspended account will not start
                    // working on the next launch.
                    DeepLinkQueue.remove(pending)
                } else {
                    DeepLinkQueue.recordFailure(pending)
                }

                // Fallback: pass the link's own query parameters through, in the
                // same {click_id, params} envelope a resolved link arrives in.
                // Limiting this to the UTM keys used to drop everything the link
                // was actually addressed to (screen, id, …) on the one path where
                // the app has no other copy of it.
                let localParams: [String: String] = (comps?.queryItems ?? [])
                    .reduce(into: [:]) { acc, item in
                        guard let value = item.value, !value.isEmpty else { return }
                        acc[item.name] = value
                    }
                let fallback = NetworkUtils.fallbackPayload(
                    clickId: clickId, localParams: localParams)
                AttributionStore.saveOnce(
                    map: NetworkUtils.attributionSnapshot(
                        resolved: fallback, source: source, fallbackClickId: clickId))
                SdkRuntime.postToFlutter(channel, method: "onDeepLink", args: fallback)
                NetworkUtils.reportError(
                    apiKey: apiKey, message: "resolve exception",
                    stack: err.localizedDescription)
            }
        }
    }

    /// Retries the pasteboard/deep-link resolve on transient failures only.
    private static func resolveWithRetry(
        clickId: String?,
        code: String?,
        fingerprint: [String: Any],
        apiKey: String,
        attempt: Int,
        completion: @escaping (Result<[String: Any], Error>) -> Void
    ) {
        NetworkUtils.resolveClick(
            clickId: clickId, code: code, fingerprint: fingerprint, apiKey: apiKey
        ) { json in
            completion(.success(json))
        } onError: { error in
            guard attempt < maxAttempts, !NetworkUtils.isTerminal(error) else {
                completion(.failure(error))
                return
            }
            let delay = initialRetryDelay * pow(2, Double(attempt - 1))
            Logger.d("Resolve attempt \(attempt) failed; retrying in \(delay)s")
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + delay) {
                resolveWithRetry(
                    clickId: clickId, code: code, fingerprint: fingerprint,
                    apiKey: apiKey, attempt: attempt + 1, completion: completion)
            }
        }
    }

    /// Retries links whose resolve never completed — an offline first launch
    /// after a deferred install being the case that matters, since the
    /// pasteboard copy is gone by then.
    static func drainPendingResolves(channel: FlutterMethodChannel, apiKey: String) {
        let pending = DeepLinkQueue.all()
        guard !pending.isEmpty else { return }
        Logger.d("Draining \(pending.count) pending resolve(s)")
        for item in pending {
            guard let url = URL(string: item.uri) else {
                DeepLinkQueue.remove(item)
                continue
            }
            handle(url: url, channel: channel, apiKey: apiKey, source: item.source)
        }
    }
}
