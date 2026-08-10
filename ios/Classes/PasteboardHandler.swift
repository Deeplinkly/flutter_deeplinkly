// PasteboardHandler.swift
import Flutter
import Foundation
import UIKit

/// The deferred deep link path on iOS.
///
/// iOS has no equivalent of the Play Install Referrer API, so the only way a
/// link tapped in Safari can survive an App Store install is the pasteboard:
/// the interstitial served by `RedirectView.handle_ios` writes the link (with
/// its click id) when the visitor taps through to the store, and this reads it
/// back on first launch.
///
/// Mirrors Android's `handlers/ClipboardHandler.kt`.
enum PasteboardHandler {
    private static let checkedKey = "deeplinkly_pasteboard_checked"
    private static let domainsInfoKey = "DeeplinklyLinkDomains"
    private static let defaultDomain = "deeplinkly.com"

    static func check(channel: FlutterMethodChannel, apiKey: String) {
        guard !TrackingPreferences.isTrackingDisabled() else { return }

        // Deferred linking is a first-launch concern. Reading on every launch
        // would show the system paste banner every time and re-deliver a link
        // the app has already handled.
        guard !Prefs.bool(for: checkedKey) else {
            Logger.d("Pasteboard already checked; skipping.")
            return
        }

        // Reading .string is what triggers the system "Pasted from…" banner, so
        // establish there is something worth reading first. Both checks below
        // are metadata only and show no banner.
        if #available(iOS 16.0, *) {
            // The reliable, documented probe. Note we do NOT gate on hasURLs:
            // Safari writes the link as plain text, which hasURLs does not
            // consistently report as a URL — gating on it would silently
            // disable deferred deep linking altogether.
            UIPasteboard.general.detectPatterns(for: [.probableWebURL]) { result in
                let hasWebURL = (try? result.get())?.contains(.probableWebURL) ?? false
                DispatchQueue.main.async {
                    guard hasWebURL else {
                        Logger.d("Pasteboard holds no web URL; skipping.")
                        Prefs.set(true, for: checkedKey)
                        return
                    }
                    read(channel: channel, apiKey: apiKey)
                }
            }
            return
        }

        // iOS 12–15 has no pattern detection. hasStrings is the widest
        // banner-free signal available; the host allowlist in read() is what
        // actually decides whether the content is ours.
        guard UIPasteboard.general.hasStrings else {
            Logger.d("Nothing on pasteboard; skipping.")
            Prefs.set(true, for: checkedKey)
            return
        }
        read(channel: channel, apiKey: apiKey)
    }

    /// The banner-triggering read. Only reached once we know a URL is there.
    private static func read(channel: FlutterMethodChannel, apiKey: String) {
        // Mark before any parsing: a crash mid-handling must not turn into a
        // paste banner on every subsequent launch.
        Prefs.set(true, for: checkedKey)

        guard
            let text = UIPasteboard.general.string?
                .trimmingCharacters(in: .whitespacesAndNewlines),
            !text.isEmpty
        else { return }

        guard text.hasPrefix("https://") || text.hasPrefix("http://"),
            let url = URL(string: text),
            let host = url.host?.lowercased()
        else { return }

        // Whatever the user last copied is almost never our link. Without this
        // the SDK would ship arbitrary copied URLs to the API.
        guard isOwnDomain(host) else {
            Logger.d("Pasteboard URL is not a Deeplinkly domain; ignoring.")
            return
        }

        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let clickId = components?.queryItems?.first(where: { $0.name == "click_id" })?.value
        let code = url.pathComponents.dropFirst().first
        guard clickId != nil || code != nil else {
            Logger.d("No click_id or code on pasteboard URL; ignoring.")
            return
        }

        // Queue before clearing — the pasteboard is the only copy of this link,
        // so an offline first launch would otherwise lose the install for good.
        let pending = DeepLinkQueue.PendingResolve(
            clickId: clickId, code: code, uri: text, source: "clipboard"
        )
        DeepLinkQueue.enqueue(pending)

        // Only ours gets cleared, and only once it is safely queued.
        UIPasteboard.general.items = []

        DeepLinkHandler.handle(url: url, channel: channel, apiKey: apiKey, source: "clipboard")
    }

    /// Host allowlist, configured by the host app as a `DeeplinklyLinkDomains`
    /// array in Info.plist. Subdomains of a listed domain are accepted.
    private static func isOwnDomain(_ host: String) -> Bool {
        var domains =
            (Bundle.main.object(forInfoDictionaryKey: domainsInfoKey) as? [String])?
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            .filter { !$0.isEmpty } ?? []

        if domains.isEmpty {
            Logger.w(
                "\(domainsInfoKey) is not set in Info.plist; only \(defaultDomain) links "
                    + "will be recognised. Add your link domain to enable deferred deep linking."
            )
            domains = [defaultDomain]
        }

        return domains.contains { host == $0 || host.hasSuffix(".\($0)") }
    }
}
