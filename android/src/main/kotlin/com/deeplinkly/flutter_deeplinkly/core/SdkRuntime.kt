package com.deeplinkly.flutter_deeplinkly.core

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

object SdkRuntime {
    lateinit var ioScope: CoroutineScope
    lateinit var mainHandler: Handler
    private val isFlutterReady = AtomicBoolean(false)
    private var flutterChannel: MethodChannel? = null

    fun ioLaunch(block: suspend CoroutineScope.() -> Unit) =
        (if (::ioScope.isInitialized) ioScope else CoroutineScope(Dispatchers.IO)).launch(block = block)

    /**
     * Mark Flutter as ready to receive deep links
     */
    fun setFlutterReady(channel: MethodChannel) {
        flutterChannel = channel
        isFlutterReady.set(true)
        Logger.d("Flutter marked as ready")
    }

    /**
     * Mark Flutter as not ready (e.g., on detach)
     */
    fun setFlutterNotReady() {
        isFlutterReady.set(false)
        flutterChannel = null
        Logger.d("Flutter marked as not ready")
    }

    /**
     * Check if Flutter is ready to receive deep links
     */
    fun isFlutterReady(): Boolean = isFlutterReady.get() && flutterChannel != null

    /**
     * Post to Flutter with queue fallback if not ready
     */
    fun postToFlutter(channel: MethodChannel, method: String, args: Any?) {
        if (!::mainHandler.isInitialized) {
            mainHandler = Handler(Looper.getMainLooper())
        }
        mainHandler.post {
            try {
                if (isFlutterReady.get() && flutterChannel != null) {
                    flutterChannel!!.invokeMethod(method, args)
                    Logger.d("Posted to Flutter: $method")
                } else {
                    // Queue for later delivery
                    Logger.w("Flutter not ready, will queue: $method")
                    com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue.enqueueDelivery(
                        com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue.PendingDelivery(
                            resolvedData = (args as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value } ?: emptyMap(),
                            enrichmentData = emptyMap(),
                            source = "deep_link"
                        )
                    )
                }
            } catch (e: Exception) {
                Logger.e("invoke failed: ${e.message}", e)
                // Queue for retry
                try {
                    com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue.enqueueDelivery(
                        com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue.PendingDelivery(
                            resolvedData = (args as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value } ?: emptyMap(),
                            enrichmentData = emptyMap(),
                            source = "deep_link"
                        )
                    )
                } catch (queueError: Exception) {
                    Logger.e("Failed to queue delivery", queueError)
                }
            }
        }
    }
}
