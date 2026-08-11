package com.deeplinkly.flutter_deeplinkly.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AttributionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Prefs.of() reads through DeeplinklyContext.app, which the plugin
        // normally populates in onAttachedToEngine.
        com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext.app = context
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
        // Atomic, because this is counted from all ten threads. A plain `var`
        // here was itself the data race the test exists to rule out, and it
        // duly lost increments — reliably enough to fail once Prefs started
        // taking a lock on first use, which releases the threads in a burst
        // rather than letting them straggle. The failure was always available;
        // it just needed the threads to line up.
        val successCount = AtomicInteger(0)

        repeat(threads) { index ->
            Thread {
                try {
                    val attribution = mapOf(
                        "source" to "thread_$index",
                        "click_id" to "click_$index"
                    )
                    AttributionStore.saveOnce(attribution.mapValues { it.value })
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue("threads did not finish within 5s", latch.await(5, TimeUnit.SECONDS))

        val saved = AttributionStore.get()
        // Only one should succeed (saveOnce semantics)
        assertTrue(saved.isNotEmpty())
        // Verify no crashes occurred
        assertEquals(threads, successCount.get())
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

    /**
     * optString(key, "") answers the literal string "null" for a JSON null on
     * Android, so a null that reached storage came back as attribution reading
     * utm_source == "null" - non-blank, and therefore true for every
     * isNullOrBlank check the SDK and the host app make.
     */
    @Test
    fun `a json null is absent rather than the string null`() {
        com.deeplinkly.flutter_deeplinkly.core.Prefs.of().edit()
            .putString("initial_attribution", """{"source":"deep_link","utm_source":null}""")
            .commit()

        val attribution = AttributionStore.get()

        assertEquals("deep_link", attribution["source"])
        assertNull("a JSON null must not surface as \"null\"", attribution["utm_source"])
    }
}
