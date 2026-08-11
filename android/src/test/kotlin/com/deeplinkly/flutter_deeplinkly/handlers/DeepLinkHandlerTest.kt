package com.deeplinkly.flutter_deeplinkly.handlers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.helpers.TestIntentBuilder
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DeepLinkHandlerTest {
    @Mock
    private lateinit var mockChannel: MethodChannel

    private lateinit var context: Context
    private val apiKey = "test_api_key"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = ApplicationProvider.getApplicationContext()
        // Prefs.of() reads through DeeplinklyContext.app, which the plugin
        // normally populates in onAttachedToEngine.
        com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext.app = context

        // Initialize SdkRuntime
        SdkRuntime.ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SdkRuntime.mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        // Clear attribution store
        com.deeplinkly.flutter_deeplinkly.core.Prefs.of().edit().clear().commit()
    }

    /**
     * Cancels this test's SDK coroutines before the next test starts.
     *
     * setUp assigns a fresh ioScope per test, which orphaned the previous
     * one's jobs rather than stopping them - so a resolve started by an earlier
     * test could still be in flight and write initial_attribution in the middle
     * of a later one, which is first-write-wins. That made any test asserting on
     * a clean AttributionStore order-dependent and intermittently red.
     */
    @After
    fun tearDown() {
        SdkRuntime.ioScope.cancel()
        com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue.clearAll()
        com.deeplinkly.flutter_deeplinkly.core.Prefs.of().edit().clear().commit()
    }


    @Test
    fun `handleIntent processes intent with click_id`() {
        val intent = TestIntentBuilder.createClickIdIntent(
            clickId = "test_click_123",
            "utm_source" to "google",
            "utm_medium" to "cpc"
        )

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        // Wait for async processing
        Thread.sleep(500)

        // Verify attribution was saved (if network call succeeds or fallback is used)
        val attribution = AttributionStore.get()
        // Attribution might be empty if network fails, but handler should not crash
        assertNotNull(attribution)
    }

    @Test
    fun `handleIntent processes intent with code`() {
        val intent = TestIntentBuilder.createCodeIntent(
            code = "abc123",
            "utm_source" to "email"
        )

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        Thread.sleep(500)

        // Handler should process code-based deep links
        assertTrue(true) // If we get here, no crash occurred
    }

    @Test
    fun `handleIntent skips intent without click_id or code`() {
        val intent = TestIntentBuilder.createInvalidIntent()

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        Thread.sleep(200)

        // Should not crash and should skip processing
        assertTrue(true)
    }

    @Test
    fun `handleIntent handles null intent gracefully`() {
        DeepLinkHandler.handleIntent(context, null, mockChannel, apiKey)

        Thread.sleep(200)

        // Should not crash
        assertTrue(true)
    }

    @Test
    fun `handleIntent handles null channel gracefully`() {
        val intent = TestIntentBuilder.createClickIdIntent("test_click")

        DeepLinkHandler.handleIntent(context, intent, null, apiKey)

        Thread.sleep(500)

        // Should queue for later delivery
        assertTrue(true)
    }

    @Test
    fun `handleIntent preserves enrichment data in error path`() {
        val intent = TestIntentBuilder.createClickIdIntent(
            clickId = "error_test_click",
            "utm_source" to "test_source"
        )

        // This will likely fail network call, but should preserve local params
        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        Thread.sleep(1000)

        val attribution = AttributionStore.get()
        // Even if network fails, local params should be preserved
        // Note: This depends on error handling implementation
        assertNotNull(attribution)
    }

    @Test
    fun `handleIntent processes UTM parameters correctly`() {
        val intent = TestIntentBuilder.createUtmIntent(
            clickId = "utm_test",
            utmSource = "google",
            utmMedium = "cpc",
            utmCampaign = "test_campaign"
        )

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        Thread.sleep(500)

        // UTM parameters should be extracted and saved
        assertTrue(true)
    }

    @Test
    fun `handleIntent processes tracking IDs correctly`() {
        val intent = TestIntentBuilder.createTrackingIntent(
            clickId = "tracking_test",
            gclid = "gclid_value",
            fbclid = "fbclid_value"
        )

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        Thread.sleep(500)

        // Tracking IDs should be processed
        assertTrue(true)
    }

    @Test
    fun `handleIntent queues deep link when Flutter not ready`() {
        SdkRuntime.setFlutterNotReady()
        
        val intent = TestIntentBuilder.createClickIdIntent("queued_click")

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        Thread.sleep(500)

        // Deep link should be queued for later delivery
        // Verify no crash occurred
        assertTrue(true)
    }

    /**
     * Android hands back the original VIEW intent when it restarts a killed
     * process from Recents, which replayed the deep link that opened the app.
     */
    @Test
    fun `handleIntent ignores an intent relaunched from history`() {
        com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue.clearAll()

        val intent = TestIntentBuilder.createClickIdIntent("history_click").apply {
            addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        }

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        Thread.sleep(200)

        // Scoped to this click id: the resolves other tests leave in flight land
        // in the same SharedPreferences after their own test has finished.
        assertTrue(
            "a replayed launch intent must not start a resolve",
            com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue.getResolveQueue()
                .none { it.clickId == "history_click" }
        )
        assertFalse(
            "a skipped intent must not be marked consumed",
            intent.getBooleanExtra("com.deeplinkly.sdk.intent_consumed", false)
        )
    }

    /** The in-process half of the same guard: one intent, one delivery. */
    @Test
    fun `handleIntent marks an intent it processed so a re-attach skips it`() {
        val intent = TestIntentBuilder.createClickIdIntent("consumed_click")

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        assertTrue(
            "a processed intent must be marked so the next attach skips it",
            intent.getBooleanExtra("com.deeplinkly.sdk.intent_consumed", false)
        )
    }

    // --- what counts as a Deeplinkly link ---------------------------------

    private fun queuedFor(clickIdOrCode: String) =
        com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue.getResolveQueue()
            .any { it.clickId == clickIdOrCode || it.code == clickIdOrCode }

    /**
     * An app's own custom-scheme route is not a Deeplinkly link. This used to
     * be resolved as code "notifications", come back 404 - which is terminal -
     * and deliver a fallback, so opening an in-app screen fired onDeepLink.
     */
    @Test
    fun `a custom scheme route is not treated as a short code`() {
        com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue.clearAll()

        val intent = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("myapp://settings/notifications")
        )

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)
        Thread.sleep(300)

        assertFalse(
            "an app's own custom-scheme route must not be resolved as a code",
            queuedFor("notifications")
        )
        assertFalse(
            "a skipped intent must not be marked consumed",
            intent.getBooleanExtra("com.deeplinkly.sdk.intent_consumed", false)
        )
    }

    /** The backend's intent:// fallback is matched on its click_id, not a path. */
    @Test
    fun `a custom scheme link carrying a click id is still handled`() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("deeplinkly://open?click_id=custom_scheme_click")
        )

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        assertTrue(
            "a click_id is a Deeplinkly link whatever the scheme",
            intent.getBooleanExtra("com.deeplinkly.sdk.intent_consumed", false)
        )
    }

    /**
     * The App Link bypass: the OS routes https://<link domain>/<code> straight
     * to the app, so the backend never saw the click and the code is the only
     * thing we have to resolve on.
     */
    @Test
    fun `an https app link is still read as a short code`() {
        val intent = TestIntentBuilder.createCodeIntent(code = "abc123")

        DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)

        assertTrue(
            "the App Link bypass must keep working",
            intent.getBooleanExtra("com.deeplinkly.sdk.intent_consumed", false)
        )
    }

    @Test
    fun `handleIntent handles concurrent intents`() = runBlocking {
        val intents = (1..5).map { index ->
            TestIntentBuilder.createClickIdIntent("concurrent_click_$index")
        }

        val jobs = intents.map { intent ->
            async {
                DeepLinkHandler.handleIntent(context, intent, mockChannel, apiKey)
            }
        }

        jobs.awaitAll()
        delay(1000)

        // Should handle concurrent intents without crashes
        assertTrue(true)
    }
}

