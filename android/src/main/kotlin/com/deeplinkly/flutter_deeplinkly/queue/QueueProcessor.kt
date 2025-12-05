package com.deeplinkly.flutter_deeplinkly.queue

import android.content.Context
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DomainConfig
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
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
            if (!DeepLinkQueue.shouldRetry(pending)) {
                val delay = DeepLinkQueue.getNextRetryDelay(pending)
                if (delay > 0) {
                    Logger.d("Skipping resolve (waiting ${delay}ms): clickId=${pending.clickId}, code=${pending.code}")
                    return@forEach
                }
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
                
                val (_, json) = NetworkUtils.resolveClickWithRetry(resolveUrl, apiKey, maxRetries = 3)
                val resolvedData = NetworkUtils.extractParamsFromJson(json, pending.clickId)
                
                // Merge enrichment data
                val enrichmentData = pending.enrichmentData.toMutableMap()
                (resolvedData["click_id"] as? String)?.let { enrichmentData["click_id"] = it }
                
                // Save attribution
                val normalized = linkedMapOf<String, String?>(
                    "source" to "deep_link",
                    "click_id" to ((resolvedData["click_id"] as? String) ?: pending.clickId),
                    "utm_source" to (resolvedData["utm_source"] as? String),
                    "utm_medium" to (resolvedData["utm_medium"] as? String),
                    "utm_campaign" to (resolvedData["utm_campaign"] as? String),
                    "utm_term" to (resolvedData["utm_term"] as? String),
                    "utm_content" to (resolvedData["utm_content"] as? String),
                    "gclid" to (resolvedData["gclid"] as? String),
                    "fbclid" to (resolvedData["fbclid"] as? String),
                    "ttclid" to (resolvedData["ttclid"] as? String)
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
                
                // If max retries reached, still queue for delivery with fallback data
                if (updated.attemptCount >= 5) {
                    Logger.w("Max retries reached, using fallback data")
                    val fallbackData = linkedMapOf<String, Any?>(
                        "click_id" to pending.clickId,
                        "utm_source" to pending.localParams["utm_source"],
                        "utm_medium" to pending.localParams["utm_medium"],
                        "utm_campaign" to pending.localParams["utm_campaign"],
                        "utm_term" to pending.localParams["utm_term"],
                        "utm_content" to pending.localParams["utm_content"],
                        "gclid" to pending.localParams["gclid"],
                        "fbclid" to pending.localParams["fbclid"],
                        "ttclid" to pending.localParams["ttclid"]
                    )
                    
                    DeepLinkQueue.enqueueDelivery(
                        DeepLinkQueue.PendingDelivery(
                            resolvedData = fallbackData,
                            enrichmentData = pending.enrichmentData,
                            source = "deep_link_fallback"
                        )
                    )
                    
                    AttributionStore.saveOnce(fallbackData.mapValues { it.value as? String })
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

