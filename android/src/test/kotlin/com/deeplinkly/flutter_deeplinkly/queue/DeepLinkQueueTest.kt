package com.deeplinkly.flutter_deeplinkly.queue

import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.privacy.AttributionLevel
import com.deeplinkly.flutter_deeplinkly.privacy.SignalCatalogue
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkQueueTest {

    @Test
    fun `a queued delivery keeps params as a codec-encodable map`() {
        val delivery = DeepLinkQueue.PendingDelivery(
            resolvedData = mapOf(
                "click_id" to "abc123",
                "params" to mapOf("screen" to "microdramas", "episode_index" to 50)
            ),
            attributionData = mapOf("platform" to "android"),
            source = "deep_link"
        )

        // Round-trips through the string form SharedPreferences actually stores.
        val restored = DeepLinkQueue.PendingDelivery.fromJson(
            JSONObject(delivery.toJson().toString())
        )

        assertEquals("abc123", restored.resolvedData["click_id"])
        val params = restored.resolvedData["params"]
        assertTrue(
            "params came back as ${params?.javaClass}; the method channel can only encode a Map",
            params is Map<*, *>
        )
        params as Map<*, *>
        assertEquals("microdramas", params["screen"])
        assertEquals(50, params["episode_index"])
    }

    /**
     * The shape an install referrer queues: a click id, and nothing else. Both
     * absent fields used to throw NullPointerException out of fromJson, and
     * getResolveQueue() swallowed it - so a deferred deep link whose first
     * resolve failed was dropped instead of retried on the next launch.
     */
    @Test
    fun `a resolve with no code and no uri survives the queue`() {
        val pending = DeepLinkQueue.PendingResolve(
            clickId = "abc123",
            code = null,
            uri = null,
            localParams = mapOf("utm_source" to "google"),
            attributionData = mapOf("utm_source" to "google")
        )

        val restored = DeepLinkQueue.PendingResolve.fromJson(
            JSONObject(pending.toJson().toString())
        )

        assertEquals("abc123", restored.clickId)
        assertNull(restored.code)
        assertNull(restored.uri)
        assertEquals("google", restored.localParams["utm_source"])
        assertEquals("google", restored.attributionData["utm_source"])
    }

    /**
     * A queued item can sit here for days. A device snapshot taken when the
     * link was opened would be replayed on send as if it were current, so the
     * queue carries link identity only and EnrichmentSender collects the device
     * half fresh.
     */
    @Test
    fun `a queued resolve carries no device signals`() {
        val pending = DeepLinkQueue.PendingResolve(
            clickId = "abc123",
            code = null,
            uri = null,
            localParams = emptyMap(),
            attributionData = mapOf(
                "click_id" to "abc123",
                "utm_source" to "google",
                // All device signals, all of which must be refused.
                "platform" to "android",
                "advertising_id" to "gaid-1",
                "android_id" to "ssaid-1",
                "screen_width" to "1080",
                "locale" to "en-GB",
            ),
        )

        val restored = DeepLinkQueue.PendingResolve.fromJson(
            JSONObject(pending.toJson().toString())
        )

        assertEquals(
            setOf("click_id", "utm_source"),
            restored.attributionData.keys,
        )
    }

    /**
     * The one non-identity key the queue carries, and the reason it is worth a
     * test of its own: it is whitelisted by name rather than by scope, so
     * nothing structural stops it being dropped or renamed.
     *
     * It used to be spelled `event_at`, which appears in no catalogue on either
     * platform. It survived this filter and was then dropped by
     * EnrichmentSender's fail-closed one, so the timestamp was collected,
     * persisted, restored, and discarded one step before the wire. Asserting
     * the catalogue knows the key is the half that would have caught it.
     */
    @Test
    fun `a queued resolve carries the event timestamp under its catalogued name`() {
        val pending = DeepLinkQueue.PendingResolve(
            clickId = "abc123",
            code = null,
            uri = null,
            localParams = emptyMap(),
            attributionData = mapOf(
                "click_id" to "abc123",
                "android_reported_at" to "1700000000000",
                // The old spelling must not be resurrected alongside it.
                "event_at" to "1700000000000",
            ),
        )

        val restored = DeepLinkQueue.PendingResolve.fromJson(
            JSONObject(pending.toJson().toString())
        )

        assertEquals(
            setOf("click_id", "android_reported_at"),
            restored.attributionData.keys,
        )
        assertEquals("1700000000000", restored.attributionData["android_reported_at"])

        // Surviving the queue is worth nothing if the catalogue then drops it:
        // that combination is exactly what made `event_at` dead on the wire.
        assertTrue(
            "android_reported_at must be catalogued or EnrichmentSender drops it",
            SignalCatalogue.allows("android_reported_at", AttributionLevel.FULL),
        )
        assertFalse(
            "event_at is in no catalogue; it must not come back",
            SignalCatalogue.allows("event_at", AttributionLevel.FULL),
        )
    }

    /**
     * The migration path for entries an older SDK wrote with the full device
     * description in them: their device keys are dropped on read rather than
     * replayed months later as current state.
     */
    @Test
    fun `device keys in a legacy queue entry are dropped on read`() {
        val legacy = JSONObject(
            """
            {
              "click_id": "abc123",
              "code": null,
              "uri": null,
              "local_params": {},
              "enrichment_data": {
                "click_id": "abc123",
                "source": "deep_link",
                "platform": "android",
                "advertising_id": "gaid-from-2024",
                "device_model": "Pixel 6",
                "hardware_fingerprint": "deadbeef"
              },
              "source": "deep_link",
              "attempt_count": 1,
              "created_at": 1700000000000
            }
            """.trimIndent()
        )

        val restored = DeepLinkQueue.PendingResolve.fromJson(legacy)

        assertEquals(setOf("click_id", "source"), restored.attributionData.keys)
        assertEquals("abc123", restored.attributionData["click_id"])
    }

    /**
     * Two deliveries enqueued inside the same millisecond used to be
     * indistinguishable - removal matched on createdAt and source - so
     * delivering one dropped the other unsent.
     */
    @Test
    fun `removing one delivery leaves its same-millisecond twin queued`() {
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        DeepLinkQueue.clearAll()

        val createdAt = 1_700_000_000_000L
        val first = DeepLinkQueue.PendingDelivery(
            resolvedData = mapOf("click_id" to "first"),
            attributionData = emptyMap(),
            source = "deep_link",
            createdAt = createdAt
        )
        val second = DeepLinkQueue.PendingDelivery(
            resolvedData = mapOf("click_id" to "second"),
            attributionData = emptyMap(),
            source = "deep_link",
            createdAt = createdAt
        )
        DeepLinkQueue.enqueueDelivery(first)
        DeepLinkQueue.enqueueDelivery(second)

        DeepLinkQueue.removeDelivery(first)

        val remaining = DeepLinkQueue.getDeliveryQueue()
        assertEquals(1, remaining.size)
        assertEquals("second", remaining.single().resolvedData["click_id"])
    }

    /** Deep links reach Dart in the order they arrived. */
    @Test
    fun `the delivery queue keeps its order`() {
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        DeepLinkQueue.clearAll()

        val ids = (1..10).map { "click_$it" }
        ids.forEach { clickId ->
            DeepLinkQueue.enqueueDelivery(
                DeepLinkQueue.PendingDelivery(
                    resolvedData = mapOf("click_id" to clickId),
                    attributionData = emptyMap(),
                    source = "deep_link"
                )
            )
        }

        assertEquals(
            ids,
            DeepLinkQueue.getDeliveryQueue().map { it.resolvedData["click_id"] }
        )
    }

    /** The mirror case: a link resolved by code carries no click id. */
    @Test
    fun `a resolve with no click id survives the queue`() {
        val pending = DeepLinkQueue.PendingResolve(
            clickId = null,
            code = "abc",
            uri = "https://x.deeplinkly.com/abc",
            localParams = emptyMap(),
            attributionData = emptyMap()
        )

        val restored = DeepLinkQueue.PendingResolve.fromJson(
            JSONObject(pending.toJson().toString())
        )

        assertNull(restored.clickId)
        assertEquals("abc", restored.code)
        assertEquals("https://x.deeplinkly.com/abc", restored.uri)
    }

    // --- resolve claims ---------------------------------------------------

    private fun resolveFor(clickId: String?, code: String? = null) =
        DeepLinkQueue.PendingResolve(
            clickId = clickId,
            code = code,
            uri = null,
            localParams = emptyMap(),
            attributionData = emptyMap()
        )

    /**
     * Every handler enqueues its PendingResolve before attempting its own
     * resolve, so without a claim the periodic processor resolved the same
     * click alongside the handler and enqueued a second delivery for it.
     */
    @Test
    fun `a claimed resolve cannot be claimed again`() {
        DeepLinkQueue.clearAll()
        val pending = resolveFor("click-1")

        assertTrue(DeepLinkQueue.claimResolve(pending))
        assertFalse(DeepLinkQueue.claimResolve(pending))

        DeepLinkQueue.releaseResolve(pending)
        assertTrue(DeepLinkQueue.claimResolve(pending))
    }

    /** A claim identifies the click, not the object the caller happens to hold. */
    @Test
    fun `a claim is keyed on identity, not instance`() {
        DeepLinkQueue.clearAll()

        assertTrue(DeepLinkQueue.claimResolve(resolveFor("click-1")))
        // The processor reads its own copy back out of SharedPreferences.
        assertFalse(DeepLinkQueue.claimResolve(resolveFor("click-1")))
    }

    /**
     * QueueProcessor used to label everything it recovered "deep_link", so an
     * install-referrer resolve that failed once and was retried came back
     * stored as an ordinary deep link.
     */
    @Test
    fun `a resolve carries its origin source through the queue`() {
        val pending = DeepLinkQueue.PendingResolve(
            clickId = "referrer_click",
            code = null,
            uri = null,
            localParams = emptyMap(),
            attributionData = emptyMap(),
            source = "install_referrer"
        )

        val restored = DeepLinkQueue.PendingResolve.fromJson(
            JSONObject(pending.toJson().toString())
        )

        assertEquals("install_referrer", restored.source)
        assertEquals(pending.id, restored.id)
    }

    /** Entries written before `source` existed still read back sensibly. */
    @Test
    fun `a legacy resolve without a source defaults to deep_link`() {
        val legacy = JSONObject("""{"click_id":"old","created_at":123}""")
        val restored = DeepLinkQueue.PendingResolve.fromJson(legacy)

        assertEquals("deep_link", restored.source)
        // Derived, not random: a fresh UUID per read would never match the copy
        // still in storage, so the item could be resolved but never removed.
        assertEquals(restored.id, DeepLinkQueue.PendingResolve.fromJson(legacy).id)
    }

    /**
     * Removal used to fall back to matching on createdAt, so removing one
     * resolve dropped an unrelated one queued in the same millisecond.
     */
    @Test
    fun `removing one resolve leaves its same-millisecond twin queued`() {
        DeepLinkQueue.clearAll()
        val now = System.currentTimeMillis()
        val first = DeepLinkQueue.PendingResolve(
            clickId = "click-a", code = null, uri = null,
            localParams = emptyMap(), attributionData = emptyMap(), createdAt = now
        )
        val second = first.copy(clickId = "click-b", id = java.util.UUID.randomUUID().toString())

        DeepLinkQueue.enqueueResolve(first)
        DeepLinkQueue.enqueueResolve(second)
        DeepLinkQueue.removeResolve(first)

        assertEquals(
            listOf("click-b"),
            DeepLinkQueue.getResolveQueue().map { it.clickId }
        )
    }

    @Test
    fun `resolves for different clicks do not block each other`() {
        DeepLinkQueue.clearAll()

        assertTrue(DeepLinkQueue.claimResolve(resolveFor("click-1")))
        assertTrue(DeepLinkQueue.claimResolve(resolveFor("click-2")))
        assertTrue(DeepLinkQueue.claimResolve(resolveFor(null, code = "abc")))
        // ...but a code-only resolve still collides with itself.
        assertFalse(DeepLinkQueue.claimResolve(resolveFor(null, code = "abc")))
    }
}
