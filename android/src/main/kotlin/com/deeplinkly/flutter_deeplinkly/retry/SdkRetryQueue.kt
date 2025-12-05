package com.deeplinkly.flutter_deeplinkly.retry

import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DomainConfig
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Retry queue for failed network requests (enrichment, errors, etc.)
 * Handles retries with exponential backoff
 */
object SdkRetryQueue {
    private const val KEY_PENDING_RETRIES = "dl_pending_retries"
    private const val MAX_RETRY_ATTEMPTS = 5
    private const val INITIAL_RETRY_DELAY_MS = 1000L
    private const val MAX_RETRY_DELAY_MS = 30_000L
    private const val MAX_QUEUE_SIZE = 50
    
    private val lock = ReentrantLock()
    private val isProcessing = AtomicBoolean(false)
    
    /**
     * Represents a pending retry item
     */
    data class RetryItem(
        val payload: JSONObject,
        val type: String, // "enrichment", "error", etc.
        val attemptCount: Int = 0,
        val lastAttemptTime: Long = System.currentTimeMillis(),
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("payload", payload)
            put("type", type)
            put("attempt_count", attemptCount)
            put("last_attempt_time", lastAttemptTime)
            put("created_at", createdAt)
        }
        
        companion object {
            fun fromJson(json: JSONObject): RetryItem {
                return RetryItem(
                    payload = json.getJSONObject("payload"),
                    type = json.getString("type"),
                    attemptCount = json.optInt("attempt_count", 0),
                    lastAttemptTime = json.optLong("last_attempt_time", System.currentTimeMillis()),
                    createdAt = json.optLong("created_at", System.currentTimeMillis())
                )
            }
        }
    }
    
    /**
     * Enqueue a failed request for retry
     */
    fun enqueue(payload: JSONObject, type: String) = lock.withLock {
        val queue = getQueue().toMutableList()
        val item = RetryItem(payload, type)
        queue.add(item)
        
        // Keep queue size manageable
        while (queue.size > MAX_QUEUE_SIZE) {
            queue.removeAt(0)
        }
        
        saveQueue(queue)
        Logger.d("Enqueued retry: type=$type, queueSize=${queue.size}")
    }
    
    /**
     * Get all pending retries
     */
    private fun getQueue(): List<RetryItem> = lock.withLock {
        val prefs = Prefs.of()
        val jsonArray = prefs.getStringSet(KEY_PENDING_RETRIES, emptySet()) ?: emptySet()
        return jsonArray.mapNotNull { jsonStr ->
            try {
                RetryItem.fromJson(JSONObject(jsonStr))
            } catch (e: Exception) {
                Logger.e("Failed to parse retry item", e)
                null
            }
        }
    }
    
    /**
     * Save queue to preferences
     */
    private fun saveQueue(queue: List<RetryItem>) {
        val prefs = Prefs.of()
        val jsonSet = queue.map { it.toJson().toString() }.toSet()
        prefs.edit().putStringSet(KEY_PENDING_RETRIES, jsonSet).apply()
    }
    
    /**
     * Remove an item from the queue
     */
    private fun removeItem(item: RetryItem) = lock.withLock {
        val queue = getQueue().toMutableList()
        queue.removeAll { existing ->
            existing.type == item.type &&
            existing.createdAt == item.createdAt &&
            existing.payload.toString() == item.payload.toString()
        }
        saveQueue(queue)
    }
    
    /**
     * Update an item's attempt count
     */
    private fun updateItem(item: RetryItem) = lock.withLock {
        val queue = getQueue().toMutableList()
        val index = queue.indexOfFirst { existing ->
            existing.type == item.type &&
            existing.createdAt == item.createdAt &&
            existing.payload.toString() == item.payload.toString()
        }
        if (index >= 0) {
            queue[index] = item
            saveQueue(queue)
        }
    }
    
    /**
     * Check if an item should be retried
     */
    private fun shouldRetry(item: RetryItem): Boolean {
        if (item.attemptCount >= MAX_RETRY_ATTEMPTS) {
            return false
        }
        val delay = calculateRetryDelay(item.attemptCount)
        val timeSinceLastAttempt = System.currentTimeMillis() - item.lastAttemptTime
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
     * Retry all pending items
     */
    fun retryAll(apiKey: String) {
        if (isProcessing.get()) {
            Logger.d("Retry processing already in progress, skipping")
            return
        }
        
        isProcessing.set(true)
        
        SdkRuntime.ioLaunch {
            try {
                val queue = getQueue()
                if (queue.isEmpty()) {
                    Logger.d("No pending retries")
                    return@ioLaunch
                }
                
                Logger.d("Processing ${queue.size} pending retries")
                
                queue.forEach { item ->
                    if (!shouldRetry(item)) {
                        if (item.attemptCount >= MAX_RETRY_ATTEMPTS) {
                            Logger.w("Retry item exceeded max attempts, removing: type=${item.type}")
                            removeItem(item)
                        }
                        return@forEach
                    }
                    
                    try {
                        when (item.type) {
                            "enrichment" -> {
                                NetworkUtils.sendEnrichmentNow(item.payload, apiKey)
                                Logger.d("Successfully retried enrichment")
                                removeItem(item)
                            }
                            "error" -> {
                                NetworkUtils.sendErrorNow(item.payload, apiKey)
                                Logger.d("Successfully retried error report")
                                removeItem(item)
                            }
                            else -> {
                                Logger.w("Unknown retry type: ${item.type}")
                                removeItem(item)
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("Retry failed for ${item.type}, will retry later", e)
                        val updatedItem = item.copy(
                            attemptCount = item.attemptCount + 1,
                            lastAttemptTime = System.currentTimeMillis()
                        )
                        updateItem(updatedItem)
                    }
                }
            } catch (e: Exception) {
                Logger.e("Error processing retry queue", e)
            } finally {
                isProcessing.set(false)
            }
        }
    }
    
    /**
     * Clear all retries (for testing or reset)
     */
    fun clearAll() = lock.withLock {
        val prefs = Prefs.of()
        prefs.edit().remove(KEY_PENDING_RETRIES).apply()
        Logger.d("Cleared all retry queue")
    }
}


