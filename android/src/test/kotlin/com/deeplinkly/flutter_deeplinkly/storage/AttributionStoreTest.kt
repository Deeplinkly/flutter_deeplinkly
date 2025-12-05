package com.deeplinkly.flutter_deeplinkly.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AttributionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any existing attribution
        val prefs = Prefs.of()
        prefs.edit().remove("initial_attribution").apply()
    }

    @Test
    fun `saveOnce saves attribution when empty`() {
        val attribution = mapOf(
            "source" to "deep_link",
            "click_id" to "test_click_123",
            "utm_source" to "google"
        )

        AttributionStore.saveOnce(attribution.mapValues { it.value })

        val saved = AttributionStore.get()
        assertEquals("deep_link", saved["source"])
        assertEquals("test_click_123", saved["click_id"])
        assertEquals("google", saved["utm_source"])
    }

    @Test
    fun `saveOnce does not overwrite existing attribution`() {
        val firstAttribution = mapOf(
            "source" to "deep_link",
            "click_id" to "first_click"
        )
        
        val secondAttribution = mapOf(
            "source" to "install_referrer",
            "click_id" to "second_click"
        )

        AttributionStore.saveOnce(firstAttribution.mapValues { it.value })
        AttributionStore.saveOnce(secondAttribution.mapValues { it.value })

        val saved = AttributionStore.get()
        // First attribution should be preserved
        assertEquals("first_click", saved["click_id"])
        assertEquals("deep_link", saved["source"])
    }

    @Test
    fun `saveAndMerge merges with existing attribution`() {
        val firstAttribution = mapOf(
            "source" to "deep_link",
            "click_id" to "first_click",
            "utm_source" to "google"
        )
        
        val secondAttribution = mapOf(
            "utm_medium" to "cpc",
            "utm_campaign" to "test"
        )

        AttributionStore.saveOnce(firstAttribution.mapValues { it.value })
        AttributionStore.saveAndMerge(secondAttribution.mapValues { it.value })

        val saved = AttributionStore.get()
        assertEquals("first_click", saved["click_id"])
        assertEquals("google", saved["utm_source"])
        assertEquals("cpc", saved["utm_medium"])
        assertEquals("test", saved["utm_campaign"])
    }

    @Test
    fun `saveAndMerge overwrites existing values with new non-null values`() {
        val firstAttribution = mapOf(
            "utm_source" to "google",
            "utm_medium" to "organic"
        )
        
        val secondAttribution = mapOf(
            "utm_source" to "facebook",
            "utm_medium" to "social"
        )

        AttributionStore.saveOnce(firstAttribution.mapValues { it.value })
        AttributionStore.saveAndMerge(secondAttribution.mapValues { it.value })

        val saved = AttributionStore.get()
        assertEquals("facebook", saved["utm_source"])
        assertEquals("social", saved["utm_medium"])
    }

    @Test
    fun `get returns empty map when no attribution exists`() {
        val attribution = AttributionStore.get()
        assertTrue(attribution.isEmpty())
    }

    @Test
    fun `saveOnce filters out null values`() {
        val attribution = mapOf(
            "click_id" to "test_123",
            "utm_source" to null,
            "utm_medium" to "cpc"
        )

        AttributionStore.saveOnce(attribution)

        val saved = AttributionStore.get()
        assertFalse(saved.containsKey("utm_source"))
        assertEquals("test_123", saved["click_id"])
        assertEquals("cpc", saved["utm_medium"])
    }

    @Test
    fun `saveOnce is thread-safe`() = runBlocking {
        val threads = 10
        val latch = CountDownLatch(threads)
        var successCount = 0

        repeat(threads) { index ->
            Thread {
                try {
                    val attribution = mapOf(
                        "source" to "thread_$index",
                        "click_id" to "click_$index"
                    )
                    AttributionStore.saveOnce(attribution.mapValues { it.value })
                    successCount++
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        latch.await(5, TimeUnit.SECONDS)

        val saved = AttributionStore.get()
        // Only one should succeed (saveOnce semantics)
        assertTrue(saved.isNotEmpty())
        // Verify no crashes occurred
        assertEquals(threads, successCount)
    }

    @Test
    fun `listeners are notified on saveOnce`() {
        var notified = false
        var receivedAttribution: Map<String, String>? = null

        val listener: (Map<String, String>) -> Unit = { attribution ->
            notified = true
            receivedAttribution = attribution
        }

        AttributionStore.addListener(listener)

        val attribution = mapOf(
            "source" to "deep_link",
            "click_id" to "test_123"
        )
        AttributionStore.saveOnce(attribution.mapValues { it.value })

        // Wait a bit for listener to be called
        Thread.sleep(100)

        assertTrue(notified)
        assertNotNull(receivedAttribution)
        assertEquals("test_123", receivedAttribution!!["click_id"])

        AttributionStore.removeListener(listener)
    }

    @Test
    fun `listeners are notified on saveAndMerge`() {
        var notified = false

        val listener: (Map<String, String>) -> Unit = { _ ->
            notified = true
        }

        AttributionStore.addListener(listener)

        val firstAttribution = mapOf("click_id" to "first")
        AttributionStore.saveOnce(firstAttribution.mapValues { it.value })

        notified = false

        val secondAttribution = mapOf("utm_source" to "google")
        AttributionStore.saveAndMerge(secondAttribution.mapValues { it.value })

        Thread.sleep(100)

        assertTrue(notified)

        AttributionStore.removeListener(listener)
    }
}

