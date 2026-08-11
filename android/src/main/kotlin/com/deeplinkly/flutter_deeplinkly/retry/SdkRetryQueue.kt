package com.deeplinkly.flutter_deeplinkly.retry

import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.flutter_deeplinkly.network.isTerminalHttp
import com.deeplinkly.flutter_deeplinkly.network.optStringOrNull
import com.deeplinkly.flutter_deeplinkly.privacy.AttributionLevel
import com.deeplinkly.flutter_deeplinkly.privacy.SignalCatalogue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
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

    /**
     * How long a queued payload stays worth sending.
     *
     * The attempt cap does not bound age on its own: an item only burns an
     * attempt when a retry is actually tried, so a device that stays offline
     * keeps a payload indefinitely and then reports its device state as
     * current whenever it comes back.
     */
    private const val MAX_ITEM_AGE_MS = 7L * 24 * 60 * 60 * 1000

    
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
        val createdAt: Long = System.currentTimeMillis(),
        /**
         * Identifies this item for its whole life in the queue.
         *
         * Removal used to match on type plus createdAt plus the payload's
         * toString(), which is both fragile - key order is not part of the
         * value - and ambiguous: two identical enrichments queued in the same
         * millisecond were indistinguishable, so removing one dropped both.
         */
        val id: String = UUID.randomUUID().toString()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("payload", payload)
            put("type", type)
            put("attempt_count", attemptCount)
            put("last_attempt_time", lastAttemptTime)
            put("created_at", createdAt)
            put("id", id)
        }

        companion object {
            fun fromJson(json: JSONObject): RetryItem {
                val type = json.getString("type")
                val createdAt = json.optLong("created_at", System.currentTimeMillis())
                return RetryItem(
                    payload = json.getJSONObject("payload"),
                    type = type,
                    attemptCount = json.optInt("attempt_count", 0),
                    lastAttemptTime = json.optLong("last_attempt_time", System.currentTimeMillis()),
                    createdAt = createdAt,
                    // Entries written before ids existed have to derive the same
                    // one on every read, or they could be sent but never removed.
                    id = json.optStringOrNull("id") ?: "legacy-$type-$createdAt"
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
    /**
     * Reads the queue in the order it was written.
     *
     * Stored as a Set<String> until now, which SharedPreferences hands back in
     * no particular order: retries fired in an order unrelated to the one they
     * failed in, an overflow trim dropped an arbitrary entry rather than the
     * oldest, and - worst - two entries that serialized identically collapsed
     * into one, silently discarding a pending report. Same fix, and same
     * legacy-key migration, as DeepLinkQueue.
     */
    private fun getQueue(): List<RetryItem> = lock.withLock {
        val prefs = Prefs.of()

        val stored = try {
            prefs.getString(KEY_PENDING_RETRIES, null)
        } catch (_: ClassCastException) {
            null
        }

        val raw: List<JSONObject> = if (stored != null) {
            try {
                val array = JSONArray(stored)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } catch (e: Exception) {
                Logger.e("Failed to parse retry queue, dropping it", e)
                emptyList()
            }
        } else {
            val legacy = try {
                prefs.getStringSet(KEY_PENDING_RETRIES, null)
            } catch (_: ClassCastException) {
                null
            } ?: emptySet()
            legacy.mapNotNull {
                try {
                    JSONObject(it)
                } catch (e: Exception) {
                    Logger.e("Failed to parse legacy retry entry", e)
                    null
                }
            }
        }

        return raw.mapNotNull { json ->
            try {
                RetryItem.fromJson(json)
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
        val array = JSONArray().apply { queue.forEach { put(it.toJson()) } }
        Prefs.of().edit().putString(KEY_PENDING_RETRIES, array.toString()).apply()
    }

    /**
     * Remove an item from the queue
     */
    private fun removeItem(item: RetryItem) = lock.withLock {
        val queue = getQueue().toMutableList()
        if (queue.removeAll { it.id == item.id }) {
            saveQueue(queue)
        }
    }

    /**
     * Update an item's attempt count
     */
    private fun updateItem(item: RetryItem) = lock.withLock {
        val queue = getQueue().toMutableList()
        val index = queue.indexOfFirst { it.id == item.id }
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

    /** Older than [MAX_ITEM_AGE_MS], so no longer worth reporting as current. */
    internal fun isExpired(item: RetryItem, now: Long = System.currentTimeMillis()): Boolean =
        now - item.createdAt > MAX_ITEM_AGE_MS

    private fun ageDays(item: RetryItem): Long =
        (System.currentTimeMillis() - item.createdAt) / (24 * 60 * 60 * 1000)

    /**
     * Re-applies the current attribution level to a payload built earlier.
     *
     * Retry items are stored fully assembled and already filtered, so without
     * this a level downgrade between queueing and sending would never be
     * honoured for anything already in the queue.
     */
    internal fun refilter(payload: JSONObject): JSONObject {
        val level = AttributionLevel.current
        val out = JSONObject()
        for (key in payload.keys()) {
            if (!SignalCatalogue.allows(key, level)) continue
            out.put(key, payload.get(key))
        }
        return out
    }
    
    /**
     * Retry all pending items
     */
    fun retryAll(apiKey: String) {
        // One atomic claim, not a get() followed by a set(). retryAll is called
        // on every activity attach, and two callers passing the read before
        // either wrote drained the same queue in parallel - re-sending every
        // pending enrichment twice.
        if (!isProcessing.compareAndSet(false, true)) {
            Logger.d("Retry processing already in progress, skipping")
            return
        }

        SdkRuntime.ioLaunch {
            try {
                val queue = getQueue()
                if (queue.isEmpty()) {
                    Logger.d("No pending retries")
                    return@ioLaunch
                }
                
                Logger.d("Processing ${queue.size} pending retries")
                
                queue.forEach { item ->
                    // A device that was offline for a month would otherwise
                    // replay month-old device state as current. The attempt cap
                    // alone does not bound age: an item only burns an attempt
                    // when a retry is actually tried.
                    if (isExpired(item)) {
                        Logger.w("Dropping ${item.type} queued ${ageDays(item)} days ago")
                        removeItem(item)
                        return@forEach
                    }

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
                                // Re-filtered against the level in force *now*.
                                // The payload was built and stored at whatever
                                // level applied when it was queued, so a user
                                // who has since moved from full to minimal would
                                // otherwise have the original full payload sent
                                // anyway. This also repairs items already in
                                // storage from an older SDK.
                                DeeplinklyNetwork.sendEnrichmentNow(refilter(item.payload), apiKey)
                                Logger.d("Successfully retried enrichment")
                                removeItem(item)
                            }
                            "error" -> {
                                DeeplinklyNetwork.sendErrorNow(item.payload, apiKey)
                                Logger.d("Successfully retried error report")
                                removeItem(item)
                            }
                            "event" -> {
                                DeeplinklyNetwork.sendEventNow(item.payload, apiKey)
                                Logger.d("Successfully retried event")
                                removeItem(item)
                            }
                            else -> {
                                Logger.w("Unknown retry type: ${item.type}")
                                removeItem(item)
                            }
                        }
                    } catch (e: Exception) {
                        // A payload the server has already rejected outright never
                        // becomes valid; retrying it just replays the same request
                        // on every launch until the attempt cap runs out.
                        if (e.isTerminalHttp()) {
                            Logger.w("Dropping ${item.type} after terminal response: ${e.message}")
                            removeItem(item)
                        } else {
                            Logger.e("Retry failed for ${item.type}, will retry later", e)
                            val updatedItem = item.copy(
                                attemptCount = item.attemptCount + 1,
                                lastAttemptTime = System.currentTimeMillis()
                            )
                            updateItem(updatedItem)
                        }
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


