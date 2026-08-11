// MethodChannelDeepLinkListener.swift
import Deeplinkly
import Flutter
import Foundation

/// Bridges the SDK's delivery funnel onto the Flutter method channel.
///
/// The whole of the plugin's half of deep link delivery. `SdkRuntime` owns the
/// buffering, the readiness gate and the main-thread hop; this only forwards.
///
/// It stays in the plugin when the rest of these files move into a native iOS
/// SDK — it is the one piece that has to know Flutter exists, which is exactly
/// why the protocol was introduced.
final class MethodChannelDeepLinkListener: DeeplinklyDeepLinkListener {
    private let channel: FlutterMethodChannel

    init(channel: FlutterMethodChannel) {
        self.channel = channel
    }

    func onDeepLink(_ payload: [String: Any]) {
        // Already on the main thread: SdkRuntime guarantees it, and
        // invokeMethod requires it.
        channel.invokeMethod("onDeepLink", arguments: payload)
    }
}
