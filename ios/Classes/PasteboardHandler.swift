// PasteboardHandler.swift
import Flutter
import UIKit
import Foundation

enum PasteboardHandler {
    static func check(channel: FlutterMethodChannel, apiKey: String) {
        guard
            let text = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines),
            !text.isEmpty
        else { return }
        guard let url = URL(string: text) else { return }
        // If you maintain a domain list, validate here before handling
        DeepLinkHandler.handle(url: url, channel: channel, apiKey: apiKey)
        UIPasteboard.general.string = ""  // clear to avoid loops
    }
}
