package com.deeplinkly.flutter_deeplinkly.core

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
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

// Handler/Looper are Android framework classes; they need a Robolectric runtime.
@RunWith(RobolectricTestRunner::class)
class SdkRuntimeTest {
    @Mock
    private lateinit var mockChannel: MethodChannel

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // The queue reads through DeeplinklyContext.app, which the plugin
        // normally populates in onAttachedToEngine.
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        SdkRuntime.ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SdkRuntime.mainHandler = Handler(Looper.getMainLooper())
        SdkRuntime.setFlutterNotReady()
    }

    @Test
    fun `setFlutterReady marks Flutter as ready`() {
        SdkRuntime.setFlutterReady(mockChannel)
        
        assertTrue(SdkRuntime.isFlutterReady())
    }

    @Test
    fun `setFlutterNotReady marks Flutter as not ready`() {
        SdkRuntime.setFlutterReady(mockChannel)
        SdkRuntime.setFlutterNotReady()
        
        assertFalse(SdkRuntime.isFlutterReady())
    }

    @Test
    fun `isFlutterReady returns false when channel is null`() {
        SdkRuntime.setFlutterNotReady()
        
        assertFalse(SdkRuntime.isFlutterReady())
    }

    @Test
    fun `deliverDeepLink invokes onDeepLink and clears the entry when Flutter is ready`() {
        DeepLinkQueue.clearAll()
        SdkRuntime.setFlutterReady(mockChannel)

        val pending = delivery(mapOf("click_id" to "test_123"))
        DeepLinkQueue.enqueueDelivery(pending)

        SdkRuntime.deliverDeepLink(pending)

        // deliverDeepLink hands the call to the main Handler. Robolectric's main
        // looper is PAUSED by default, so sleeping never drains it - the queued
        // runnable has to be idled explicitly.
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(mockChannel).invokeMethod("onDeepLink", pending.resolvedData)
        assertTrue(
            "a delivered link must not stay queued for the processor to send again",
            DeepLinkQueue.getDeliveryQueue().none { it.id == pending.id }
        )
        assertFalse(DeepLinkQueue.isInFlight(pending.id))
    }

    @Test
    fun `deliverDeepLink keeps the entry queued when Flutter is not ready`() {
        DeepLinkQueue.clearAll()
        SdkRuntime.setFlutterNotReady()

        val pending = delivery(mapOf("click_id" to "test_123"))
        DeepLinkQueue.enqueueDelivery(pending)

        SdkRuntime.deliverDeepLink(pending)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

        verify(mockChannel, never()).invokeMethod(anyString(), any())
        assertTrue(
            "an undelivered link must survive for the processor to retry",
            DeepLinkQueue.getDeliveryQueue().any { it.id == pending.id }
        )
        assertFalse(DeepLinkQueue.isInFlight(pending.id))
    }

    /**
     * The claim is what stops the periodic processor from sending a link that is
     * already on its way to Flutter.
     */
    @Test
    fun `a delivery in flight is not offered to the processor`() {
        DeepLinkQueue.clearAll()
        SdkRuntime.setFlutterReady(mockChannel)

        val pending = delivery(mapOf("click_id" to "in_flight"))
        DeepLinkQueue.enqueueDelivery(pending)

        // Posted but not yet run: the looper is still paused.
        SdkRuntime.deliverDeepLink(pending)

        assertTrue(DeepLinkQueue.isInFlight(pending.id))
        assertTrue(
            "the processor must not pick up a delivery already in flight",
            DeepLinkQueue.getDeliverableQueue().none { it.id == pending.id }
        )

        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(DeepLinkQueue.isInFlight(pending.id))
    }

    private fun delivery(data: Map<String, Any?>) = DeepLinkQueue.PendingDelivery(
        resolvedData = data,
        attributionData = emptyMap(),
        source = "deep_link"
    )

    @Test
    fun `ioLaunch executes coroutine on IO dispatcher`() = runBlocking {
        var executed = false
        
        SdkRuntime.ioLaunch {
            executed = true
        }
        
        delay(100)
        assertTrue(executed)
    }

    @Test
    fun `deliverDeepLink keeps the entry queued when the channel throws`() {
        DeepLinkQueue.clearAll()
        SdkRuntime.setFlutterReady(mockChannel)

        // Create a channel that will throw
        doThrow(RuntimeException("Test exception")).`when`(mockChannel).invokeMethod(anyString(), any())

        val pending = delivery(mapOf("click_id" to "test"))
        DeepLinkQueue.enqueueDelivery(pending)

        SdkRuntime.deliverDeepLink(pending)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Should not crash, and the link is still there to retry
        assertTrue(
            DeepLinkQueue.getDeliveryQueue().any { it.id == pending.id }
        )
        assertFalse(DeepLinkQueue.isInFlight(pending.id))
    }
}

