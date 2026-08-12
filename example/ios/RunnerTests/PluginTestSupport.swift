import Flutter
import Foundation

@testable import Deeplinkly

final class DiscardingDeepLinkListener: DeeplinklyDeepLinkListener {
    func onDeepLink(_ payload: [String: Any]) {}
}

/// Clears app-hosted persistence tests without touching the real Keychain.
enum HostStorageTestSupport {
    private static let persistedKeys = [
        "custom_user_id", "tracking_disabled", "deeplinkly_test_scratch",
        "deeplinkly_test_never_written",
    ]

    static func reset() {
        for key in persistedKeys {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}

/// A binary messenger that records method calls instead of crossing an engine.
final class FakeBinaryMessenger: NSObject, FlutterBinaryMessenger {
    struct Sent {
        let channel: String
        let method: String
        let arguments: Any?
    }

    private(set) var sent: [Sent] = []
    var methods: [String] { sent.map { $0.method } }

    private func record(_ channel: String, _ message: Data?) {
        guard let message = message else { return }
        let call = FlutterStandardMethodCodec.sharedInstance().decodeMethodCall(message)
        sent.append(Sent(channel: channel, method: call.method, arguments: call.arguments))
    }

    func send(onChannel channel: String, message: Data?) {
        record(channel, message)
    }

    func send(onChannel channel: String, message: Data?, binaryReply callback: FlutterBinaryReply?)
    {
        record(channel, message)
        callback?(nil)
    }

    func setMessageHandlerOnChannel(
        _ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler?
    ) -> FlutterBinaryMessengerConnection {
        0
    }

    func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {}
}
