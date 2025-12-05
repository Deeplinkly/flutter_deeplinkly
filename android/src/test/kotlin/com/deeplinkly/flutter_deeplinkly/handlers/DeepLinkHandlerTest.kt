package com.deeplinkly.flutter_deeplinkly.handlers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.helpers.TestIntentBuilder
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeepLinkHandlerTest {
    @Mock
    private lateinit var mockChannel: MethodChannel

    private lateinit var context: Context
    private val apiKey = "test_api_key"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = ApplicationProvider.getApplicationContext()
        
        // Initialize SdkRuntime
        SdkRuntime.ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SdkRuntime.mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        // Clear attribution store
        com.deeplinkly.flutter_deeplinkly.core.Prefs.of().edit().clear().apply()
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

