package com.deeplinkly.flutter_deeplinkly.queue

import android.content.Context
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.flutter_deeplinkly.network.isTerminalHttp
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Processes queued deep links with high reliability:
 * 1. Retries unresolved deep links from backend
 * 2. Delivers resolved deep links to Flutter when ready
 */
object QueueProcessor {
    private val isProcessing = AtomicBoolean(false)
    private var periodicJob: Job? = null

    /** How often to look while there is anything queued. */
    private const val PERIODIC_INTERVAL_MS = 2000L

    /**
     * How often to look while both queues are empty.
     *
     * The loop used to tick every 2s for the life of the process whether or not
     * there was anything to do, which for the overwhelmingly common case - no
     * pending links at all - is ~1800 pointless wakeups an hour. Backing off
     * rather than stopping keeps the recovery path simple: nothing has to
     * remember to restart the loop when an item is queued.
     */
    private const val IDLE_INTERVAL_MS = 30_000L

    /**
     * Start processing queues (non-blocking)
     */
    fun startProcessing(channel: MethodChannel?, apiKey: String) {
        // Process immediately
        processNow(channel, apiKey)
        
        // Start periodic processing for high reliability
        if (periodicJob?.isActive == true) {
            return // Already running
        }
        
        periodicJob = SdkRuntime.ioLaunch {
            while (isActive) {
                try {
                    delay(if (DeepLinkQueue.isIdle()) IDLE_INTERVAL_MS else PERIODIC_INTERVAL_MS)
                    // Claim the flag, don't just read it. A bare get() leaves
                    // the exact gap processNow's compareAndSet was added to
                    // close: a tick that passes the check just before
                    // processNow claims it runs the whole drain concurrently
                    // with it, resolving every queued link twice and
                    // delivering each one twice.
                    if (!isProcessing.compareAndSet(false, true)) {
                        continue
                    }
                    try {
                        processResolveQueue(apiKey)
                        withContext(Dispatchers.Main) {
                            processDeliveryQueue(channel)
                        }
                    } finally {
                        isProcessing.set(false)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e("Periodic queue processing error", e)
                    }
                }
            }
        }
    }
    
    /**
     * Stop processing queues
     */
    fun stopProcessing() {
        periodicJob?.cancel()
        isProcessing.set(false)
        Logger.d("Queue processor stopped")
    }
    
    /**
     * Process pending resolves with retry logic
     */
    private suspend fun processResolveQueue(apiKey: String) = withContext(Dispatchers.IO) {
        val queue = DeepLinkQueue.getResolveQueue()
        if (queue.isEmpty()) {
            Logger.d("No pending resolves to process")
            return@withContext
        }
        
        Logger.d("Processing ${queue.size} pending resolves")
        
        queue.forEach { pending ->
            // Exhausted and backing-off are different states. The previous guard
            // conflated them: once the backoff window elapsed, getNextRetryDelay
            // decayed to 0 and an item that had already burned its budget fell
            // through and was retried anyway.
            if (DeepLinkQueue.isExhausted(pending)) {
                Logger.w("Dropping resolve after max attempts: clickId=${pending.clickId}, code=${pending.code}")
                DeepLinkQueue.removeResolve(pending)
                return@forEach
            }
            if (!DeepLinkQueue.shouldRetry(pending)) {
                val delay = DeepLinkQueue.getNextRetryDelay(pending)
                Logger.d("Skipping resolve (waiting ${delay}ms): clickId=${pending.clickId}, code=${pending.code}")
                return@forEach
            }

            if (pending.clickId == null && pending.code == null) {
                Logger.w("Pending resolve has no clickId or code, removing")
                DeepLinkQueue.removeResolve(pending)
                return@forEach
            }

            // Skip anything a handler is already resolving; it will remove the
            // entry itself on success. Without this the handler's own request
            // and this one both resolve the click and both enqueue a delivery.
            if (!DeepLinkQueue.claimResolve(pending)) {
                Logger.d("Resolve already in flight, skipping: clickId=${pending.clickId}, code=${pending.code}")
                return@forEach
            }

            try {
                // localParams carries the click-time UTMs off the original
                // link. They matter most here on the resolve-by-code path,
                // where the backend creates the ClickEvent and reads them
                // straight off the query string.
                val resolveUrl = DeeplinklyNetwork.resolveUrl(
                    pending.clickId, pending.code, pending.localParams
                )

                Logger.d("Resolving: $resolveUrl (attempt ${pending.attemptCount + 1})")

                val (_, json) = DeeplinklyNetwork.resolveClickWithRetry(resolveUrl, apiKey, maxRetries = 3)

                // An unknown click id comes back 200 with stale: true. Retrying
                // it would keep the item in the queue until it exhausted its
                // budget and then deliver a link the backend has disowned.
                if (DeeplinklyNetwork.isStale(json)) {
                    Logger.w("Queued resolve returned a stale click; dropping: clickId=${pending.clickId}")
                    DeepLinkQueue.removeResolve(pending)
                    return@forEach
                }

                val resolvedData = DeeplinklyNetwork.extractParamsFromJson(json, pending.clickId)
                
                // Carry the link identity forward, updated with whatever the
                // resolve actually returned. No device signals ride along —
                // this item may have been queued days ago, and the sender
                // collects the device half fresh.
                val attributionData = pending.attributionData.toMutableMap()
                (resolvedData["click_id"] as? String)?.let { attributionData["click_id"] = it }
                
                // Save attribution
                val normalized = DeeplinklyNetwork.attributionSnapshot(
                    resolvedData, source = pending.source, fallbackClickId = pending.clickId
                )
                AttributionStore.saveOnce(normalized)
                
                // Queue for Flutter delivery
                DeepLinkQueue.enqueueDelivery(
                    DeepLinkQueue.PendingDelivery(
                        resolvedData = resolvedData,
                        attributionData = attributionData,
                        source = pending.source
                    )
                )

                // Remove from resolve queue
                DeepLinkQueue.removeResolve(pending)

                // Send enrichment
                try {
                    EnrichmentSender.sendOnce(attributionData, pending.source, apiKey)
                } catch (e: Exception) {
                    Logger.e("Failed to send enrichment after resolve", e)
                }
                
                Logger.d("Successfully resolved: clickId=${pending.clickId}, code=${pending.code}")
                
            } catch (e: Exception) {
                Logger.e("Failed to resolve: clickId=${pending.clickId}, code=${pending.code}", e)
                
                // Update attempt count
                val updated = pending.copy(
                    attemptCount = pending.attemptCount + 1,
                    lastAttemptTime = System.currentTimeMillis()
                )
                DeepLinkQueue.updateResolveAttempt(updated)

                // Give up immediately on a response the server will keep rejecting
                // (revoked key, suspended account, unknown click) instead of
                // spending the remaining attempts on it.
                val terminal = e.isTerminalHttp()
                if (terminal) {
                    Logger.w("Resolve rejected (terminal), using fallback data: ${e.message}")
                }

                // If max retries reached, still queue for delivery with fallback data
                if (terminal || DeepLinkQueue.isExhausted(updated)) {
                    if (!terminal) Logger.w("Max retries reached, using fallback data")
                    // Same {click_id, params} envelope as a resolved link.
                    val fallbackData = DeeplinklyNetwork.fallbackPayload(
                        pending.clickId, pending.localParams
                    )

                    DeepLinkQueue.enqueueDelivery(
                        DeepLinkQueue.PendingDelivery(
                            resolvedData = fallbackData,
                            attributionData = pending.attributionData,
                            source = "${pending.source}_fallback"
                        )
                    )
                    
                    AttributionStore.saveOnce(
                        DeeplinklyNetwork.attributionSnapshot(
                            fallbackData, source = pending.source, fallbackClickId = pending.clickId
                        )
                    )
                    DeepLinkQueue.removeResolve(updated)
                }
            } finally {
                DeepLinkQueue.releaseResolve(pending)
            }
        }
    }

    /**
     * Process pending deliveries to Flutter
     */
    private suspend fun processDeliveryQueue(channel: MethodChannel?) = withContext(Dispatchers.Main) {
        if (!SdkRuntime.isFlutterReady() || channel == null) {
            Logger.d("Flutter not ready, skipping delivery queue")
            return@withContext
        }
        
        // Anything already on its way to Flutter is skipped rather than sent a
        // second time; deliverDeepLink releases the claim either way, so a
        // failed attempt is picked up again on the next pass.
        val queue = DeepLinkQueue.getDeliverableQueue()
        if (queue.isEmpty()) {
            return@withContext
        }

        Logger.d("Processing ${queue.size} pending deliveries")

        // Removal is deliverDeepLink's job, and it happens only once the channel
        // has taken the link.
        queue.forEach { pending -> SdkRuntime.deliverDeepLink(pending) }
    }
    
    /**
     * Process queues immediately (called when Flutter becomes ready)
     */
    fun processNow(channel: MethodChannel?, apiKey: String) {
        // One atomic claim, not a get() followed by a set(). Dart announces
        // itself twice at startup - flutterReady and the resumed lifecycle
        // event - and startProcessing adds a third call at activity attach, all
        // of them landing on the IO dispatcher at once. Two callers passing the
        // read before either wrote drained the same resolve queue in parallel,
        // which resolved every pending link twice and delivered each one twice.
        if (!isProcessing.compareAndSet(false, true)) {
            return // Already processing
        }

        SdkRuntime.ioLaunch {
            try {
                processResolveQueue(apiKey)
                withContext(Dispatchers.Main) {
                    processDeliveryQueue(channel)
                }
            } catch (e: Exception) {
                Logger.e("Error in processNow", e)
            } finally {
                isProcessing.set(false)
            }
        }
    }
}

