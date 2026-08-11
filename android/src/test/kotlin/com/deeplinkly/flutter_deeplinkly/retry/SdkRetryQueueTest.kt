package com.deeplinkly.flutter_deeplinkly.retry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.privacy.AttributionLevel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The retry queue stores fully assembled, already filtered payloads. Both of
 * the leaks covered here come from that: the stored copy outlives the state it
 * was built against.
 */
@RunWith(RobolectricTestRunner::class)
class SdkRetryQueueTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeeplinklyContext.app = context
        Prefs.of().edit().clear().apply()
    }

    private fun fullPayload() = JSONObject().apply {
        put("deeplinkly_device_id", "device-1")
        put("click_id", "click-1")
        put("locale", "en-GB")
        put("android_id", "ssaid-1")
        put("advertising_id", "gaid-1")
        put("screen_width", "1080")
    }

    /**
     * The level-downgrade leak. A payload built at FULL, queued, and then
     * replayed after the user drops to MINIMAL would otherwise be sent in full
     * — the consent change would apply to every future payload but not to the
     * one already in storage.
     */
    @Test
    fun `a queued payload is refiltered against the level in force at send time`() {
        val queued = fullPayload()

        AttributionLevel.set(AttributionLevel.MINIMAL)
        val refiltered = SdkRetryQueue.refilter(queued)

        assertEquals(
            setOf("deeplinkly_device_id", "click_id"),
            refiltered.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `refiltering at full keeps everything classified`() {
        AttributionLevel.set(AttributionLevel.FULL)
        val refiltered = SdkRetryQueue.refilter(fullPayload())

        assertEquals(6, refiltered.length())
        assertEquals("gaid-1", refiltered.getString("advertising_id"))
    }

    @Test
    fun `refiltering drops a key the catalogue does not know`() {
        AttributionLevel.set(AttributionLevel.FULL)
        val payload = JSONObject().apply {
            put("click_id", "click-1")
            put("some_future_signal", "leaked")
        }

        val refiltered = SdkRetryQueue.refilter(payload)

        assertFalse(refiltered.has("some_future_signal"))
        assertTrue(refiltered.has("click_id"))
    }

    /**
     * The age leak. The attempt cap does not bound age — an item only burns an
     * attempt when a retry is actually tried — so a device offline for a month
     * comes back and reports month-old device state as current.
     */
    @Test
    fun `an item older than the ttl is expired`() {
        val eightDays = 8L * 24 * 60 * 60 * 1000
        val old = SdkRetryQueue.RetryItem(
            payload = fullPayload(),
            type = "enrichment",
            createdAt = System.currentTimeMillis() - eightDays,
        )

        assertTrue(SdkRetryQueue.isExpired(old))
    }

    @Test
    fun `an item inside the ttl is not expired`() {
        val sixDays = 6L * 24 * 60 * 60 * 1000
        val recent = SdkRetryQueue.RetryItem(
            payload = fullPayload(),
            type = "enrichment",
            createdAt = System.currentTimeMillis() - sixDays,
        )

        assertFalse(SdkRetryQueue.isExpired(recent))
    }
}
