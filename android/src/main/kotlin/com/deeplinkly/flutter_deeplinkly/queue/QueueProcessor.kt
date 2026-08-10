package com.deeplinkly.flutter_deeplinkly.queue

import android.content.Context
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DomainConfig
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
    private var processingJob: Job? = null
    private var periodicJob: Job? = null
    private const val PERIODIC_INTERVAL_MS = 2000L // Process every 2 seconds
    
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
                    delay(PERIODIC_INTERVAL_MS)
                    if (!isProcessing.get()) {
                        processResolveQueue(apiKey)
                        withContext(Dispatchers.Main) {
                            processDeliveryQueue(channel)
                        }
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
        processingJob?.cancel()
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

            try {
                val resolveUrl = if (pending.clickId != null) {
                    "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?click_id=${pending.clickId}"
                } else if (pending.code != null) {
                    "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?code=${pending.code}"
                } else {
                    Logger.w("Pending resolve has no clickId or code, removing")
                    DeepLinkQueue.removeResolve(pending)
                    return@forEach
                }
                
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
                
                // Merge enrichment data
                val enrichmentData = pending.enrichmentData.toMutableMap()
                (resolvedData["click_id"] as? String)?.let { enrichmentData["click_id"] = it }
                
                // Save attribution
                val normalized = DeeplinklyNetwork.attributionSnapshot(
                    resolvedData, source = "deep_link", fallbackClickId = pending.clickId
                )
                AttributionStore.saveOnce(normalized)
                
                // Queue for Flutter delivery
                DeepLinkQueue.enqueueDelivery(
                    DeepLinkQueue.PendingDelivery(
                        resolvedData = resolvedData,
                        enrichmentData = enrichmentData,
                        source = "deep_link"
                    )
                )
                
                // Remove from resolve queue
                DeepLinkQueue.removeResolve(pending)
                
                // Send enrichment
                try {
                    EnrichmentSender.sendOnce(
                        com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext.app,
                        enrichmentData,
                        "deep_link",
                        apiKey
                    )
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
                            enrichmentData = pending.enrichmentData,
                            source = "deep_link_fallback"
                        )
                    )
                    
                    AttributionStore.saveOnce(
                        DeeplinklyNetwork.attributionSnapshot(
                            fallbackData, source = "deep_link", fallbackClickId = pending.clickId
                        )
                    )
                    DeepLinkQueue.removeResolve(updated)
                }
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
        
        val queue = DeepLinkQueue.getDeliveryQueue()
        if (queue.isEmpty()) {
            return@withContext
        }
        
        Logger.d("Processing ${queue.size} pending deliveries")
        
        queue.forEach { pending ->
            try {
                SdkRuntime.postToFlutter(channel, "onDeepLink", pending.resolvedData)
                DeepLinkQueue.removeDelivery(pending)
                Logger.d("Delivered deep link to Flutter: source=${pending.source}")
            } catch (e: Exception) {
                Logger.e("Failed to deliver deep link to Flutter", e)
                // Keep in queue for retry
            }
        }
    }
    
    /**
     * Process queues immediately (called when Flutter becomes ready)
     */
    fun processNow(channel: MethodChannel?, apiKey: String) {
        if (isProcessing.get()) {
            return // Already processing
        }
        
        isProcessing.set(true)
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

