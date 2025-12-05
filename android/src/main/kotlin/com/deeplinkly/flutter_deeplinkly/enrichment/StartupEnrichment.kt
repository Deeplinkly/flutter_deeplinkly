package com.deeplinkly.flutter_deeplinkly.enrichment

import android.os.SystemClock
import com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object StartupEnrichment {
    @Volatile private var sentThisProcess = false
    private val isWaiting = AtomicBoolean(false)
    private val startTime = AtomicLong(0)
    private var attributionListener: ((Map<String, String>) -> Unit)? = null
    private var enrichmentJob: Job? = null
    private const val DEFAULT_TIMEOUT_MS = 60_000L // Increased to 60 seconds
    private const val MIN_WAIT_TIME_MS = 2_000L // Minimum wait time before sending

    /** Call once from the plugin after you've set DeeplinklyContext.app and have apiKey. */
    fun schedule(apiKey: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (sentThisProcess) {
            Logger.d("StartupEnrichment: already sent in this process.")
            return
        }
        
        startTime.set(SystemClock.elapsedRealtime())
        isWaiting.set(true)
        
        // Check if attribution is already available
        val currentAttr = AttributionStore.get()
        if (hasAttributionData(currentAttr)) {
            Logger.d("StartupEnrichment: attribution already available, sending immediately.")
            sendEnrichment(apiKey, currentAttr)
            return
        }
        
        // Set up event-based listener
        attributionListener = { attribution ->
            if (isWaiting.get() && hasAttributionData(attribution)) {
                Logger.d("StartupEnrichment: attribution received via event, sending.")
                sendEnrichment(apiKey, attribution)
            }
        }
        AttributionStore.addListener(attributionListener!!)
        
        // Fallback: Start timeout coroutine (event-based is primary, timeout is backup)
        enrichmentJob = SdkRuntime.ioLaunch {
            try {
                // Wait minimum time to allow events to arrive
                delay(MIN_WAIT_TIME_MS)
                
                // Check if we already sent
                if (sentThisProcess) {
                    return@ioLaunch
                }
                
                // Wait for attribution with timeout (using elapsedRealtime for relative time)
                val elapsed = SystemClock.elapsedRealtime() - startTime.get()
                val remainingTimeout = (timeoutMs - elapsed).coerceAtLeast(0)
                
                if (remainingTimeout > 0) {
                    val hasAttribution = waitForAttributionEvent(remainingTimeout)
                    if (hasAttribution) {
                        val attr = AttributionStore.get()
                        sendEnrichment(apiKey, attr)
                        return@ioLaunch
                    }
                }
                
                // Timeout reached - send with whatever we have (don't skip entirely)
                Logger.d("StartupEnrichment: timeout reached, sending with available data.")
                val attr = AttributionStore.get()
                sendEnrichment(apiKey, attr, force = true)
                
            } catch (e: CancellationException) {
                // Job was cancelled, ignore
            } catch (t: Throwable) {
                Logger.e("StartupEnrichment failure", t)
                // Still try to send with available data
                try {
                    val attr = AttributionStore.get()
                    sendEnrichment(apiKey, attr, force = true)
                } catch (e: Exception) {
                    Logger.e("StartupEnrichment: failed to send on error", e)
                }
            } finally {
                cleanup()
            }
        }
    }
    
    /**
     * Wait for attribution event (event-based, not polling)
     * Uses a suspend function that waits for the listener to fire
     */
    private suspend fun waitForAttributionEvent(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Boolean>()
        var listener: ((Map<String, String>) -> Unit)? = null
        
        listener = { attribution ->
            if (hasAttributionData(attribution) && !deferred.isCompleted) {
                deferred.complete(true)
            }
        }
        
        AttributionStore.addListener(listener)
        
        try {
            // Wait for either attribution or timeout
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } ?: false
        } finally {
            AttributionStore.removeListener(listener)
        }
    }
    
    /**
     * Check if attribution data has meaningful content
     */
    private fun hasAttributionData(attr: Map<String, String>): Boolean {
        val hasUtm = !attr["utm_source"].isNullOrBlank()
        val hasIds = !attr["gclid"].isNullOrBlank() || 
                     !attr["fbclid"].isNullOrBlank() || 
                     !attr["ttclid"].isNullOrBlank()
        val hasClickId = !attr["click_id"].isNullOrBlank()
        return hasUtm || hasIds || hasClickId
    }
    
    /**
     * Send enrichment data
     */
    private fun sendEnrichment(apiKey: String, attribution: Map<String, String>, force: Boolean = false) {
        if (sentThisProcess && !force) {
            return
        }
        
        // Launch coroutine to call suspend function
        SdkRuntime.ioLaunch {
            try {
                // Build enrichment data: base + first-touch attribution
                val base = DeeplinklyUtils.collectEnrichment().toMutableMap()
                base.putAll(attribution.mapValues { it.value })
                
                // Only send if we have meaningful data or if forced
                if (force || hasAttributionData(attribution) || base.isNotEmpty()) {
                    EnrichmentSender.sendOnce(
                        context = DeeplinklyContext.app,
                        enrichmentData = base,
                        source = "app_start",
                        apiKey = apiKey
                    )
                    sentThisProcess = true
                    Logger.d("StartupEnrichment: sent (force=$force, hasData=${hasAttributionData(attribution)}).")
                } else {
                    Logger.d("StartupEnrichment: no meaningful data, skipping.")
                }
            } catch (t: Throwable) {
                Logger.e("StartupEnrichment: send failure", t)
            } finally {
                cleanup()
            }
        }
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        isWaiting.set(false)
        attributionListener?.let { listener ->
            AttributionStore.removeListener(listener)
            attributionListener = null
        }
        enrichmentJob?.cancel()
        enrichmentJob = null
    }
    
    /**
     * Cancel waiting (if app is closing, etc.)
     */
    fun cancel() {
        cleanup()
    }
}

