package com.deeplinkly.flutter_deeplinkly.queue

import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * High-reliability queue for deep links that need to be:
 * 1. Resolved from backend (network retry)
 * 2. Delivered to Flutter (when Flutter is ready)
 * 
 * Thread-safe and persistent across app restarts.
 */
object DeepLinkQueue {
    private const val KEY_PENDING_RESOLVE = "dl_pending_resolve"
    private const val KEY_PENDING_DELIVERY = "dl_pending_delivery"
    private const val MAX_QUEUE_SIZE = 100
    private const val MAX_RETRY_ATTEMPTS = 5
    private const val INITIAL_RETRY_DELAY_MS = 100L
    private const val MAX_RETRY_DELAY_MS = 10_000L
    
    private val lock = ReentrantLock()
    private val isProcessing = AtomicBoolean(false)
    
    /**
     * Represents a deep link that needs backend resolution
     */
    data class PendingResolve(
        val clickId: String?,
        val code: String?,
        val uri: String?,
        val localParams: Map<String, String?>,
        val enrichmentData: Map<String, String?>,
        val attemptCount: Int = 0,
        val lastAttemptTime: Long = System.currentTimeMillis(),
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("click_id", clickId)
            put("code", code)
            put("uri", uri)
            put("local_params", JSONObject(localParams.filterValues { it != null }))
            put("enrichment_data", JSONObject(enrichmentData.filterValues { it != null }))
            put("attempt_count", attemptCount)
            put("last_attempt_time", lastAttemptTime)
            put("created_at", createdAt)
        }
        
        companion object {
            fun fromJson(json: JSONObject): PendingResolve {
                val localParamsObj = json.optJSONObject("local_params") ?: JSONObject()
                val enrichmentObj = json.optJSONObject("enrichment_data") ?: JSONObject()
                
                val localParams = mutableMapOf<String, String?>()
                localParamsObj.keys().forEach { key ->
                    localParams[key] = localParamsObj.optString(key, null)
                }
                
                val enrichmentData = mutableMapOf<String, String?>()
                enrichmentObj.keys().forEach { key ->
                    enrichmentData[key] = enrichmentObj.optString(key, null)
                }
                
                return PendingResolve(
                    clickId = json.optString("click_id", null).takeIf { it.isNotBlank() },
                    code = json.optString("code", null).takeIf { it.isNotBlank() },
                    uri = json.optString("uri", null).takeIf { it.isNotBlank() },
                    localParams = localParams,
                    enrichmentData = enrichmentData,
                    attemptCount = json.optInt("attempt_count", 0),
                    lastAttemptTime = json.optLong("last_attempt_time", System.currentTimeMillis()),
                    createdAt = json.optLong("created_at", System.currentTimeMillis())
                )
            }
        }
    }
    
    /**
     * Represents a resolved deep link waiting for Flutter delivery
     */
    data class PendingDelivery(
        val resolvedData: Map<String, Any?>,
        val enrichmentData: Map<String, String?>,
        val source: String,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("resolved_data", JSONObject(resolvedData.filterValues { it != null }))
            put("enrichment_data", JSONObject(enrichmentData.filterValues { it != null }))
            put("source", source)
            put("created_at", createdAt)
        }
        
        companion object {
            fun fromJson(json: JSONObject): PendingDelivery {
                val resolvedObj = json.optJSONObject("resolved_data") ?: JSONObject()
                val enrichmentObj = json.optJSONObject("enrichment_data") ?: JSONObject()
                
                val resolvedData = mutableMapOf<String, Any?>()
                resolvedObj.keys().forEach { key ->
                    resolvedData[key] = resolvedObj.opt(key)
                }
                
                val enrichmentData = mutableMapOf<String, String?>()
                enrichmentObj.keys().forEach { key ->
                    enrichmentData[key] = enrichmentObj.optString(key, null)
                }
                
                return PendingDelivery(
                    resolvedData = resolvedData,
                    enrichmentData = enrichmentData,
                    source = json.optString("source", "unknown"),
                    createdAt = json.optLong("created_at", System.currentTimeMillis())
                )
            }
        }
    }
    
    /**
     * Enqueue a deep link that needs backend resolution
     */
    fun enqueueResolve(pending: PendingResolve) = lock.withLock {
        val queue = getResolveQueue().toMutableList()
        // Remove duplicates (same click_id or code)
        queue.removeAll { existing ->
            (existing.clickId != null && existing.clickId == pending.clickId) ||
            (existing.code != null && existing.code == pending.code)
        }
        queue.add(pending)
        // Keep queue size manageable
        while (queue.size > MAX_QUEUE_SIZE) {
            queue.removeAt(0)
        }
        saveResolveQueue(queue)
        Logger.d("Enqueued resolve: clickId=${pending.clickId}, code=${pending.code}, queueSize=${queue.size}")
    }
    
    /**
     * Enqueue a resolved deep link for Flutter delivery
     */
    fun enqueueDelivery(pending: PendingDelivery) = lock.withLock {
        val queue = getDeliveryQueue().toMutableList()
        queue.add(pending)
        // Keep queue size manageable
        while (queue.size > MAX_QUEUE_SIZE) {
            queue.removeAt(0)
        }
        saveDeliveryQueue(queue)
        Logger.d("Enqueued delivery: source=${pending.source}, queueSize=${queue.size}")
    }
    
    /**
     * Get all pending resolves (for retry processing)
     */
    fun getResolveQueue(): List<PendingResolve> = lock.withLock {
        val prefs = Prefs.of()
        val jsonArray = prefs.getStringSet(KEY_PENDING_RESOLVE, emptySet()) ?: emptySet()
        return jsonArray.mapNotNull { jsonStr ->
            try {
                PendingResolve.fromJson(JSONObject(jsonStr))
            } catch (e: Exception) {
                Logger.e("Failed to parse pending resolve", e)
                null
            }
        }
    }
    
    /**
     * Get all pending deliveries (for Flutter delivery)
     */
    fun getDeliveryQueue(): List<PendingDelivery> = lock.withLock {
        val prefs = Prefs.of()
        val jsonArray = prefs.getStringSet(KEY_PENDING_DELIVERY, emptySet()) ?: emptySet()
        return jsonArray.mapNotNull { jsonStr ->
            try {
                PendingDelivery.fromJson(JSONObject(jsonStr))
            } catch (e: Exception) {
                Logger.e("Failed to parse pending delivery", e)
                null
            }
        }
    }
    
    /**
     * Remove a resolved item from queue
     */
    fun removeResolve(pending: PendingResolve) = lock.withLock {
        val queue = getResolveQueue().toMutableList()
        queue.removeAll { existing ->
            (existing.clickId != null && existing.clickId == pending.clickId) ||
            (existing.code != null && existing.code == pending.code) ||
            existing.createdAt == pending.createdAt
        }
        saveResolveQueue(queue)
    }
    
    /**
     * Remove a delivery item from queue
     */
    fun removeDelivery(pending: PendingDelivery) = lock.withLock {
        val queue = getDeliveryQueue().toMutableList()
        queue.removeAll { existing ->
            existing.createdAt == pending.createdAt && existing.source == pending.source
        }
        saveDeliveryQueue(queue)
    }
    
    /**
     * Remove a delivery item from queue by matching click_id and source
     * Used to prevent duplicate sends when immediate delivery succeeds
     */
    fun removeDeliveryByClickId(clickId: String?, source: String) = lock.withLock {
        if (clickId == null) return@withLock
        val queue = getDeliveryQueue().toMutableList()
        val initialSize = queue.size
        queue.removeAll { existing ->
            existing.source == source && 
            (existing.resolvedData["click_id"] as? String) == clickId
        }
        val removed = initialSize - queue.size
        if (removed > 0) {
            saveDeliveryQueue(queue)
            Logger.d("Removed delivery from queue: clickId=$clickId, source=$source")
        }
    }
    
    /**
     * Update a pending resolve with new attempt info
     */
    fun updateResolveAttempt(pending: PendingResolve) = lock.withLock {
        val queue = getResolveQueue().toMutableList()
        val index = queue.indexOfFirst { existing ->
            (existing.clickId != null && existing.clickId == pending.clickId) ||
            (existing.code != null && existing.code == pending.code) ||
            existing.createdAt == pending.createdAt
        }
        if (index >= 0) {
            queue[index] = pending
            saveResolveQueue(queue)
        }
    }
    
    /**
     * Check if a resolve should be retried (exponential backoff)
     */
    fun shouldRetry(pending: PendingResolve): Boolean {
        if (pending.attemptCount >= MAX_RETRY_ATTEMPTS) {
            return false
        }
        val delay = calculateRetryDelay(pending.attemptCount)
        val timeSinceLastAttempt = System.currentTimeMillis() - pending.lastAttemptTime
        return timeSinceLastAttempt >= delay
    }
    
    /**
     * Calculate exponential backoff delay
     */
    private fun calculateRetryDelay(attemptCount: Int): Long {
        val delay = INITIAL_RETRY_DELAY_MS * (1 shl attemptCount)
        return delay.coerceAtMost(MAX_RETRY_DELAY_MS)
    }
    
    /**
     * Get next retry delay in milliseconds
     */
    fun getNextRetryDelay(pending: PendingResolve): Long {
        val delay = calculateRetryDelay(pending.attemptCount)
        val timeSinceLastAttempt = System.currentTimeMillis() - pending.lastAttemptTime
        return (delay - timeSinceLastAttempt).coerceAtLeast(0)
    }
    
    private fun saveResolveQueue(queue: List<PendingResolve>) {
        val prefs = Prefs.of()
        val jsonSet = queue.map { it.toJson().toString() }.toSet()
        prefs.edit().putStringSet(KEY_PENDING_RESOLVE, jsonSet).apply()
    }
    
    private fun saveDeliveryQueue(queue: List<PendingDelivery>) {
        val prefs = Prefs.of()
        val jsonSet = queue.map { it.toJson().toString() }.toSet()
        prefs.edit().putStringSet(KEY_PENDING_DELIVERY, jsonSet).apply()
    }
    
    /**
     * Clear all queues (for testing or reset)
     */
    fun clearAll() = lock.withLock {
        val prefs = Prefs.of()
        prefs.edit()
            .remove(KEY_PENDING_RESOLVE)
            .remove(KEY_PENDING_DELIVERY)
            .apply()
        Logger.d("Cleared all deep link queues")
    }
    
    /**
     * Get queue statistics
     */
    fun getStats(): Map<String, Int> = lock.withLock {
        mapOf(
            "pending_resolve" to getResolveQueue().size,
            "pending_delivery" to getDeliveryQueue().size
        )
    }
}

