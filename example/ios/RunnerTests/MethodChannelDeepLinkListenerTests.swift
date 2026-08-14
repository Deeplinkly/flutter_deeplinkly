import Flutter
import XCTest

@testable import Deeplinkly
@testable import flutter_deeplinkly

/// The plugin's end of the delivery funnel: the one piece that still speaks
/// to a Flutter method channel after the native SDK extraction.
final class MethodChannelDeepLinkListenerTests: XCTestCase {

    private var messenger: FakeBinaryMessenger!
    private var channel: FlutterMethodChannel!

    override func setUp() {
        super.setUp()
        // Flush any native buffer without sending it into this test's channel.
        SdkRuntime.setListener(DiscardingDeepLinkListener())
        SdkRuntime.clearListener()
        messenger = FakeBinaryMessenger()
        channel = FlutterMethodChannel(name: "deeplinkly/test", binaryMessenger: messenger)
    }

    override func tearDown() {
        SdkRuntime.clearListener()
        super.tearDown()
    }

    func testForwardsAsAnOnDeepLinkInvocation() {
        MethodChannelDeepLinkListener(channel: channel).onDeepLink(["click_id": "c1"])

        XCTAssertEqual(messenger.methods, ["onDeepLink"])
        XCTAssertEqual(
            (messenger.sent.first?.arguments as? [String: Any])?["click_id"] as? String, "c1")
    }

    /// The envelope survives the standard codec — nested `params` included.
    func testPayloadSurvivesTheChannelCodec() {
        MethodChannelDeepLinkListener(channel: channel).onDeepLink([
            "click_id": "c1",
            "params": ["utm_source": "news", "count": 3],
        ])

        let args = messenger.sent.first?.arguments as? [String: Any]
        let params = args?["params"] as? [String: Any]
        XCTAssertEqual(params?["utm_source"] as? String, "news")
        XCTAssertEqual(params?["count"] as? Int, 3)
    }

    /// A resolve that answered with nothing still delivers `click_id: null` in
    /// the envelope rather than omitting it.
    func testNullClickIdSurvivesTheCodec() {
        MethodChannelDeepLinkListener(channel: channel).onDeepLink([
            "click_id": NSNull(), "params": [:],
        ])

        let args = messenger.sent.first?.arguments as? [String: Any]
        XCTAssertTrue(args?.keys.contains("click_id") ?? false)
        XCTAssertTrue(args?["click_id"] is NSNull)
    }

    /// Wiring the adapter into the native SDK funnel is the integration seam.
    func testDeliversThroughTheFunnel() {
        SdkRuntime.setListener(MethodChannelDeepLinkListener(channel: channel))
        SdkRuntime.deliverDeepLink(["click_id": "c1"])

        XCTAssertEqual(messenger.methods, ["onDeepLink"])
    }
}
