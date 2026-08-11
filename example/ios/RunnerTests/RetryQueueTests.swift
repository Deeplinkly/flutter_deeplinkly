import XCTest

@testable import flutter_deeplinkly

/// Durable storage for payloads that failed to send.
///
/// Note the storage key: `sdk_retry_queue`, where Android uses
/// `dl_pending_retries`. That is the one real cross-platform divergence, and
/// canonicalising on the Android name is a migration the iOS extraction owns —
/// see docs/NATIVE_SDK_MIGRATION.md. `testStorageKeyIsStable` will fail when
/// that happens, which is the intended prompt to write the migration rather
/// than to just rename the constant.
final class RetryQueueTests: XCTestCase {

    override func setUp() {
        super.setUp()
        DeeplinklyTestSupport.reset()
    }

    override func tearDown() {
        DeeplinklyTestSupport.reset()
        super.tearDown()
    }

    private func decoded(_ raw: String) -> [String: Any] {
        guard let data = raw.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return [:] }
        return object
    }

    // MARK: - enqueue

    func testEnqueueStoresTypeAndPayload() {
        RetryQueue.enqueue(type: "enrichment", payload: ["click_id": "c1"])

        let items = RetryQueue.items()
        XCTAssertEqual(items.count, 1)
        let item = decoded(items[0])
        XCTAssertEqual(item["type"] as? String, "enrichment")
        XCTAssertEqual((item["payload"] as? [String: Any])?["click_id"] as? String, "c1")
    }

    /// The timestamp is what the TTL is measured against. Without it a device
    /// offline for a month replays month-old device state as current.
    func testEnqueueStampsQueuedAt() {
        let before = Date().timeIntervalSince1970
        RetryQueue.enqueue(type: "event", payload: [:])
        let after = Date().timeIntervalSince1970

        let queuedAt = decoded(RetryQueue.items()[0])["queued_at"] as? TimeInterval
        XCTAssertNotNil(queuedAt)
        XCTAssertGreaterThanOrEqual(queuedAt ?? 0, before)
        XCTAssertLessThanOrEqual(queuedAt ?? 0, after)
    }

    /// Unlike `DeepLinkQueue`, this queue does not dedupe — two failed sends of
    /// the same payload are two things to retry.
    func testEnqueueDoesNotDedupe() {
        RetryQueue.enqueue(type: "event", payload: ["name": "purchase"])
        RetryQueue.enqueue(type: "event", payload: ["name": "purchase"])
        XCTAssertEqual(RetryQueue.items().count, 2)
    }

    func testQueueIsCappedDroppingOldest() {
        for index in 0..<55 {
            RetryQueue.enqueue(type: "event", payload: ["index": index])
        }

        let items = RetryQueue.items()
        XCTAssertEqual(items.count, 50)
        XCTAssertEqual((decoded(items[0])["payload"] as? [String: Any])?["index"] as? Int, 5)
        XCTAssertEqual((decoded(items[49])["payload"] as? [String: Any])?["index"] as? Int, 54)
    }

    func testNestedPayloadsRoundTrip() {
        RetryQueue.enqueue(
            type: "event",
            payload: ["event_name": "purchase", "device": ["platform": "ios"], "count": 3])

        let payload = decoded(RetryQueue.items()[0])["payload"] as? [String: Any]
        XCTAssertEqual(payload?["event_name"] as? String, "purchase")
        XCTAssertEqual((payload?["device"] as? [String: Any])?["platform"] as? String, "ios")
        XCTAssertEqual(payload?["count"] as? Int, 3)
    }

    // MARK: - remove

    func testRemoveDeletesTheMatchingItem() {
        RetryQueue.enqueue(type: "event", payload: ["index": 1])
        RetryQueue.enqueue(type: "event", payload: ["index": 2])

        let first = RetryQueue.items()[0]
        RetryQueue.remove(first)

        XCTAssertEqual(RetryQueue.items().count, 1)
        XCTAssertFalse(RetryQueue.items().contains(first))
    }

    func testRemovingAnAbsentItemIsHarmless() {
        RetryQueue.enqueue(type: "event", payload: [:])
        RetryQueue.remove("not in the queue")
        XCTAssertEqual(RetryQueue.items().count, 1)
    }

    /// Removal is by exact string and deletes one occurrence, so two identical
    /// payloads do not collapse when one of them drains.
    func testRemoveDeletesOnlyOneOfTwoIdenticalItems() {
        RetryQueue.enqueue(type: "event", payload: ["name": "purchase"])
        let items = RetryQueue.items()
        // Two enqueues a moment apart differ only in queued_at; force the
        // genuinely-identical case the dedupe-free queue can produce.
        UserDefaults.standard.set([items[0], items[0]], forKey: "sdk_retry_queue")

        RetryQueue.remove(items[0])
        XCTAssertEqual(RetryQueue.items().count, 1)
    }

    func testItemsOnEmptyStorageIsEmpty() {
        XCTAssertTrue(RetryQueue.items().isEmpty)
    }

    // MARK: - refilter

    /// Retry items are stored fully assembled and already filtered, so without
    /// this a level downgrade between queueing and sending is never honoured
    /// for anything already in the queue.
    func testRefilterAppliesTheLevelInForceNow() {
        let payload: [String: Any] = [
            "click_id": "c1", "utm_source": "news", "screen_width": "1170",
        ]

        AttributionLevel.set(.full)
        XCTAssertEqual(RetryQueue.refilter(payload).count, 3)

        AttributionLevel.set(.reduced)
        XCTAssertEqual(Set(RetryQueue.refilter(payload).keys), ["click_id", "utm_source"])

        AttributionLevel.set(.minimal)
        XCTAssertEqual(Set(RetryQueue.refilter(payload).keys), ["click_id"])

        AttributionLevel.set(.none)
        XCTAssertTrue(RetryQueue.refilter(payload).isEmpty)
    }

    func testRefilterHonoursTrackingDisabled() {
        AttributionLevel.set(.full)
        TrackingPreferences.setTrackingDisabled(true)
        XCTAssertTrue(RetryQueue.refilter(["click_id": "c1"]).isEmpty)
    }

    /// Fail-closed reaches the retry path too: an item stored by an older SDK
    /// carrying a key this build does not catalogue is stripped rather than
    /// replayed.
    func testRefilterDropsUncataloguedKeys() {
        AttributionLevel.set(.full)
        let out = RetryQueue.refilter(["click_id": "c1", "retired_signal": "x"])
        XCTAssertEqual(Set(out.keys), ["click_id"])
    }

    // MARK: - retryAll

    /// The tracking switch short-circuits before anything leaves the device,
    /// and leaves the queue untouched so it can drain if tracking is re-enabled.
    func testRetryAllIsSuppressedWhileTrackingIsDisabled() {
        RetryQueue.enqueue(type: "enrichment", payload: ["click_id": "c1"])
        TrackingPreferences.setTrackingDisabled(true)

        RetryQueue.retryAll(apiKey: "test-key")

        XCTAssertEqual(RetryQueue.items().count, 1, "the queue was drained while opted out")
    }

    /// Every `retryAll` case exercised below is one that provably issues no
    /// request. The send-dispatch paths are deliberately not covered: they call
    /// `sendNow`, which blocks on a semaphore around a live request to
    /// `DomainConfig`'s production host, and the backend is production. Making
    /// them testable needs an injectable base URL or `URLSession` — see
    /// `SEAM_TESTS.md`.
    ///
    /// An entry that is not JSON, or that carries no `type`, is skipped and
    /// left in place for a later launch to reconsider.
    func testMalformedItemsAreSkippedAndKept() {
        UserDefaults.standard.set(
            ["not json at all", "{\"payload\":{}}"], forKey: "sdk_retry_queue")

        RetryQueue.retryAll(apiKey: "test-key")

        XCTAssertEqual(RetryQueue.items().count, 2)
    }

    /// An entry naming a type this build does not know is dropped rather than
    /// reconsidered forever — the switch's default falls through to the same
    /// `remove` a successful send uses.
    func testUnknownTypeIsDroppedRatherThanRetriedForever() {
        UserDefaults.standard.set(
            ["{\"type\":\"mystery\",\"payload\":{}}"], forKey: "sdk_retry_queue")

        RetryQueue.retryAll(apiKey: "test-key")

        XCTAssertTrue(RetryQueue.items().isEmpty)
    }

    /// Items past the 7-day TTL are dropped before the switch, so an expired
    /// payload is never sent whatever the network would have said. Without
    /// this, a device offline for a month reports month-old device state as
    /// current when it reconnects.
    func testItemsPastTheTtlAreDroppedWithoutSending() {
        storeItem(type: "enrichment", ageInDays: 8)

        RetryQueue.retryAll(apiKey: "test-key")

        XCTAssertTrue(RetryQueue.items().isEmpty, "an expired item survived the TTL sweep")
    }

    /// The boundary the other way — an item inside the window surviving the
    /// sweep — is **not** covered, and cannot be without injection: an item the
    /// TTL keeps falls straight through to a real send, and one given an
    /// unknown type to avoid that is removed by the default branch instead. The
    /// queue cannot distinguish the two removals. `SEAM_TESTS.md` records this.
    private func storeItem(type: String, ageInDays: Double) {
        let item: [String: Any] = [
            "type": type,
            "payload": ["click_id": "c1"],
            "queued_at": Date().timeIntervalSince1970 - (ageInDays * 24 * 60 * 60),
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: item),
            let encoded = String(data: data, encoding: .utf8)
        else { return XCTFail("could not encode fixture") }
        UserDefaults.standard.set([encoded], forKey: "sdk_retry_queue")
    }

    // MARK: - Storage contract

    /// Persisted state on live installs. Renaming without a migration abandons
    /// every queued payload.
    func testStorageKeyIsStable() {
        RetryQueue.enqueue(type: "event", payload: [:])
        XCTAssertNotNil(
            UserDefaults.standard.array(forKey: "sdk_retry_queue"),
            "the retry queue moved off sdk_retry_queue without a migration")
    }

    /// The three types `retryAll` dispatches on. A payload stored under a type
    /// the switch does not know is silently never sent.
    func testEveryEnqueuedTypeIsOneRetryAllHandles() {
        for type in ["enrichment", "error", "event"] {
            RetryQueue.enqueue(type: type, payload: [:])
        }
        let types = RetryQueue.items().compactMap { decoded($0)["type"] as? String }
        XCTAssertEqual(Set(types), ["enrichment", "error", "event"])
    }
}
