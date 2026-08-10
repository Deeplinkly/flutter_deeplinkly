package com.deeplinkly.flutter_deeplinkly.queue

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
            enrichmentData = mapOf("platform" to "android"),
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
}
