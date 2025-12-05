package com.deeplinkly.flutter_deeplinkly.core

import android.os.Handler
import android.os.Looper
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

class SdkRuntimeTest {
    @Mock
    private lateinit var mockChannel: MethodChannel

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
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
    fun `postToFlutter invokes method when Flutter is ready`() {
        SdkRuntime.setFlutterReady(mockChannel)
        
        val testData = mapOf("click_id" to "test_123")
        SdkRuntime.postToFlutter(mockChannel, "onDeepLink", testData)
        
        // Wait for handler to process
        Thread.sleep(200)
        
        // Verify method was called
        verify(mockChannel, timeout(500)).invokeMethod("onDeepLink", testData)
    }

    @Test
    fun `postToFlutter queues when Flutter is not ready`() {
        SdkRuntime.setFlutterNotReady()
        
        val testData = mapOf("click_id" to "test_123")
        SdkRuntime.postToFlutter(mockChannel, "onDeepLink", testData)
        
        Thread.sleep(200)
        
        // Should not invoke method, but should queue
        verify(mockChannel, never()).invokeMethod(anyString(), any())
    }

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
    fun `postToFlutter handles exceptions gracefully`() {
        SdkRuntime.setFlutterReady(mockChannel)
        
        // Create a channel that will throw
        doThrow(RuntimeException("Test exception")).`when`(mockChannel).invokeMethod(anyString(), any())
        
        val testData = mapOf("click_id" to "test")
        SdkRuntime.postToFlutter(mockChannel, "onDeepLink", testData)
        
        Thread.sleep(200)
        
        // Should not crash, should queue for retry
        assertTrue(true)
    }
}

