package com.deeplinkly.flutter_deeplinkly.core

import android.os.Handler
import android.os.Looper
import com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue
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
     * Hands a queued deep link to Flutter, removing it only once the channel
     * has accepted it.
     *
     * The caller enqueues first and calls this second, so the queue is the one
     * record of the link from the moment it is resolved until Dart has it. What
     * this replaced tried to express the same thing by enqueueing, posting, and
     * then removing the entry 100ms later on a timer, which went wrong in three
     * ways: the periodic processor could tick inside that window and deliver the
     * link twice; the removal was keyed on click_id, which a link resolved by
     * code does not have, so for those nothing was ever removed and the second
     * delivery was guaranteed; and a failed invoke re-queued a synthesized copy
     * labelled "deep_link" whatever the original source was, stripped of its
     * enrichment and no longer matching the removal its own caller would attempt.
     *
     * Delivery is claimed before the post and released after, so a processor
     * tick that lands mid-flight skips the item instead of sending it again.
     */
    fun deliverDeepLink(pending: DeepLinkQueue.PendingDelivery) {
        if (!::mainHandler.isInitialized) {
            mainHandler = Handler(Looper.getMainLooper())
        }
        DeepLinkQueue.markInFlight(pending.id)
        mainHandler.post {
            try {
                val channel = flutterChannel
                if (isFlutterReady.get() && channel != null) {
                    channel.invokeMethod("onDeepLink", pending.resolvedData)
                    DeepLinkQueue.removeDelivery(pending)
                    Logger.d("Delivered deep link to Flutter: source=${pending.source}")
                } else {
                    // Left in the queue exactly as it is; the processor picks it
                    // up once Flutter announces itself.
                    Logger.w("Flutter not ready, leaving deep link queued")
                }
            } catch (e: Exception) {
                Logger.e("invokeMethod failed, leaving deep link queued: ${e.message}", e)
            } finally {
                DeepLinkQueue.clearInFlight(pending.id)
            }
        }
    }
}
