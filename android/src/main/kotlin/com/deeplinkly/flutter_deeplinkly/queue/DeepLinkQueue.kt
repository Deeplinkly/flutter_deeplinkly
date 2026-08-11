package com.deeplinkly.flutter_deeplinkly.queue

import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.network.optStringOrNull
import com.deeplinkly.flutter_deeplinkly.network.toValueMap
import com.deeplinkly.flutter_deeplinkly.privacy.SignalCatalogue
import com.deeplinkly.flutter_deeplinkly.privacy.SignalScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
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

    /**
     * Deliveries currently handed to the method channel and awaiting its answer.
     *
     * In-memory on purpose: it describes this process's live attempts, and a
     * process that dies mid-delivery has to reconsider the item, not inherit a
     * claim on it. Held only so the periodic processor does not pick up a link
     * that is already on its way to Flutter and deliver it a second time.
     */
    private val inFlightDeliveries: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    /**
     * Resolves currently being sent to the backend by somebody.
     *
     * The delivery queue has had [inFlightDeliveries] for a while; the resolve
     * queue had no equivalent, and every handler enqueues its PendingResolve
     * *before* attempting its own resolve. So a periodic tick landing while a
     * handler's own request was still outstanding - a cold start on a slow
     * network is enough, given the 10s connect timeout - resolved the same
     * click a second time and enqueued a second delivery for it. Delivery ids
     * are fresh UUIDs, so nothing downstream collapsed the pair and Dart saw
     * onDeepLink twice.
     *
     * Keyed the same way the queue dedupes: click id when there is one, code
     * otherwise. In-memory for the same reason as the delivery set - it
     * describes this process's live attempts, and a process that dies mid
     * resolve must reconsider the item rather than inherit a claim on it.
     */
    private val inFlightResolves: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    private fun resolveKey(pending: PendingResolve): String =
        pending.clickId?.let { "click:$it" } ?: "code:${pending.code}"

    /** Claims a resolve, or answers false if somebody else already holds it. */
    fun claimResolve(pending: PendingResolve): Boolean =
        inFlightResolves.add(resolveKey(pending))

    /** Releases a resolve claim, whatever the outcome. */
    fun releaseResolve(pending: PendingResolve) {
        inFlightResolves.remove(resolveKey(pending))
    }


    /**
     * Link identity that may be persisted in the queue.
     *
     * Device signals must never be queued. An item can sit here for days, and a
     * stored snapshot of the advertising id, the network or the clock would be
     * replayed as if it were current — reporting the state of the device when
     * the link was first seen, not when the payload was finally sent.
     * [EnrichmentSender] assembles the device half fresh at send time instead.
     *
     * Entries written by an older SDK still hold the full enrichment map; they
     * are filtered through this on read, so device keys are dropped rather than
     * migrated.
     *
     * `android_reported_at` is the one non-identity key allowed through, and it
     * is added by name rather than by scope because it is catalogued DYNAMIC —
     * dynamic signals are exactly what must not be queued. It is the exception
     * that proves the rule: it describes *the event*, not the device, so
     * replaying it later is the point rather than the hazard.
     *
     * The old spelling, "event_at", was in no catalogue on either platform, so
     * it survived this filter only to be dropped by EnrichmentSender's
     * fail-closed one. Anything named here must exist in signals.json or it
     * never reaches the wire.
     */
    private val QUEUEABLE_KEYS: Set<String> =
        SignalCatalogue.keysFor(SignalScope.IDENTITY) - "custom_user_id" +
            "android_reported_at"

    /** Drops anything that is not link identity. */
    private fun onlyIdentity(data: Map<String, String?>): Map<String, String?> =
        data.filterKeys { it in QUEUEABLE_KEYS }

    /**
     * Represents a deep link that needs backend resolution
     */
    data class PendingResolve(
        val clickId: String?,
        val code: String?,
        val uri: String?,
        val localParams: Map<String, String?>,
        /**
         * Link identity only — see [QUEUEABLE_KEYS]. Carries
         * `android_reported_at`, the moment the link was actually opened, so a
         * sample delivered by a retry three days later is still dated to the
         * event rather than to the retry.
         */
        val attributionData: Map<String, String?>,
        /**
         * The mechanism that queued this, carried so a recovered item is not
         * relabelled. QueueProcessor used to hardcode "deep_link" for
         * everything it retried, so an install-referrer recovery was stored
         * locally as an ordinary deep link.
         */
        val source: String = "deep_link",
        val attemptCount: Int = 0,
        val lastAttemptTime: Long = System.currentTimeMillis(),
        val createdAt: Long = System.currentTimeMillis(),
        /**
         * Identifies this resolve for its whole life in the queue, for the same
         * reason [PendingDelivery.id] exists: removal used to fall back to
         * matching on createdAt, which collides for anything queued inside the
         * same millisecond.
         */
        val id: String = UUID.randomUUID().toString()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("click_id", clickId)
            put("code", code)
            put("uri", uri)
            put("local_params", JSONObject(localParams.filterValues { it != null }))
            // The JSON key stays "enrichment_data" so an SDK downgrade can still
            // read what a newer build wrote. Only the contents narrowed.
            put(
                "enrichment_data",
                JSONObject(onlyIdentity(attributionData).filterValues { it != null }),
            )
            put("source", source)
            put("attempt_count", attemptCount)
            put("last_attempt_time", lastAttemptTime)
            put("created_at", createdAt)
            put("id", id)
        }
        
        companion object {
            fun fromJson(json: JSONObject): PendingResolve {
                val localParamsObj = json.optJSONObject("local_params") ?: JSONObject()
                val enrichmentObj = json.optJSONObject("enrichment_data") ?: JSONObject()
                
                val localParams = mutableMapOf<String, String?>()
                localParamsObj.keys().forEach { key ->
                    localParams[key] = localParamsObj.optStringOrNull(key)
                }

                // Filtered on read, which is the migration path for entries an
                // older SDK wrote with the full device description in them:
                // their device keys are dropped rather than replayed.
                val attributionData = mutableMapOf<String, String?>()
                enrichmentObj.keys().forEach { key ->
                    attributionData[key] = enrichmentObj.optStringOrNull(key)
                }

                // A null field is written by dropping the key (JSONObject.put
                // removes rather than stores it), so every one of these is
                // routinely absent: an install referrer carries no code and no
                // uri, a custom-scheme link has no path segment to read a code
                // from. Reading them with optString(key, null) threw NPE on
                // exactly those items, and getResolveQueue() swallowed it - so
                // the retry queue silently discarded every entry it existed to
                // preserve.
                val createdAt = json.optLong("created_at", System.currentTimeMillis())
                val source = json.optStringOrNull("source") ?: "deep_link"
                return PendingResolve(
                    clickId = json.optStringOrNull("click_id"),
                    code = json.optStringOrNull("code"),
                    uri = json.optStringOrNull("uri"),
                    localParams = localParams,
                    attributionData = onlyIdentity(attributionData),
                    source = source,
                    attemptCount = json.optInt("attempt_count", 0),
                    lastAttemptTime = json.optLong("last_attempt_time", System.currentTimeMillis()),
                    createdAt = createdAt,
                    // An entry written before ids existed derives the same one
                    // on every read; a fresh UUID per read would never match the
                    // copy still in storage.
                    id = json.optStringOrNull("id") ?: "legacy-$source-$createdAt"
                )
            }
        }
    }
    
    /**
     * Represents a resolved deep link waiting for Flutter delivery
     */
    data class PendingDelivery(
        val resolvedData: Map<String, Any?>,
        /** Link identity only — see [QUEUEABLE_KEYS]. */
        val attributionData: Map<String, String?>,
        val source: String,
        val createdAt: Long = System.currentTimeMillis(),
        /**
         * Identifies this delivery for its whole life in the queue.
         *
         * Removal used to match on createdAt plus source, which collides for
         * anything enqueued inside the same millisecond - removing one delivery
         * dropped its twin unsent - or on click_id, which a link resolved by
         * code does not have, so nothing was ever removed for those and the
         * processor delivered them a second time.
         */
        val id: String = UUID.randomUUID().toString()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("resolved_data", JSONObject(resolvedData.filterValues { it != null }))
            put(
                "enrichment_data",
                JSONObject(onlyIdentity(attributionData).filterValues { it != null }),
            )
            put("source", source)
            put("created_at", createdAt)
            put("id", id)
        }

        companion object {
            fun fromJson(json: JSONObject): PendingDelivery {
                val resolvedObj = json.optJSONObject("resolved_data") ?: JSONObject()
                val enrichmentObj = json.optJSONObject("enrichment_data") ?: JSONObject()
                
                // toValueMap, not opt(): a persisted payload's nested "params"
                // comes back as a JSONObject, which the method-channel codec
                // cannot encode - the delivery would fail for every link that
                // waited in the queue.
                val resolvedData = resolvedObj.toValueMap().toMutableMap()

                val attributionData = mutableMapOf<String, String?>()
                enrichmentObj.keys().forEach { key ->
                    attributionData[key] = enrichmentObj.optStringOrNull(key)
                }

                val source = json.optString("source", "unknown")
                val createdAt = json.optLong("created_at", System.currentTimeMillis())

                return PendingDelivery(
                    resolvedData = resolvedData,
                    attributionData = onlyIdentity(attributionData),
                    source = source,
                    createdAt = createdAt,
                    // An entry written before ids existed has to derive the same
                    // one on every read: a fresh UUID per read would never match
                    // the copy still in storage, so the item could be delivered
                    // but never removed.
                    id = json.optStringOrNull("id") ?: "legacy-$source-$createdAt"
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
        queue.removeAll { it.id == pending.id }
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
        return readQueue(KEY_PENDING_RESOLVE).mapNotNull { json ->
            try {
                PendingResolve.fromJson(json)
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
        return readQueue(KEY_PENDING_DELIVERY).mapNotNull { json ->
            try {
                PendingDelivery.fromJson(json)
            } catch (e: Exception) {
                Logger.e("Failed to parse pending delivery", e)
                null
            }
        }
    }

    /**
     * Pending deliveries nothing is currently attempting.
     *
     * What the queue processor should work from. [getDeliveryQueue] answers the
     * whole queue, including in-flight items, and stays that way because every
     * mutator reads it before writing it back - filtering there would let an
     * enqueue quietly drop the delivery another thread was mid-way through.
     */
    fun getDeliverableQueue(): List<PendingDelivery> =
        getDeliveryQueue().filterNot { isInFlight(it.id) }

    /** Claims a delivery for the channel; see [inFlightDeliveries]. */
    fun markInFlight(id: String) {
        inFlightDeliveries.add(id)
    }

    /** Releases a claim once the channel has answered, either way. */
    fun clearInFlight(id: String) {
        inFlightDeliveries.remove(id)
    }

    fun isInFlight(id: String): Boolean = inFlightDeliveries.contains(id)


    /**
     * Remove a resolved item from queue
     */
    fun removeResolve(pending: PendingResolve) = lock.withLock {
        val queue = getResolveQueue().toMutableList()
        // Identity first, then the click the entry is *about* - enqueueResolve
        // dedupes on that, so an entry queued by one handler and removed by
        // another still matches. What is gone is the createdAt fallback, which
        // removed an unrelated entry that happened to be queued in the same
        // millisecond.
        queue.removeAll { existing ->
            existing.id == pending.id ||
            (existing.clickId != null && existing.clickId == pending.clickId) ||
            (existing.code != null && existing.code == pending.code)
        }
        saveResolveQueue(queue)
    }
    
    /**
     * Remove a delivery item from queue
     */
    fun removeDelivery(pending: PendingDelivery) = lock.withLock {
        val queue = getDeliveryQueue().toMutableList()
        if (queue.removeAll { it.id == pending.id }) {
            saveDeliveryQueue(queue)
            Logger.d("Removed delivery from queue: id=${pending.id}, source=${pending.source}")
        }
    }


    /**
     * Update a pending resolve with new attempt info
     */
    fun updateResolveAttempt(pending: PendingResolve) = lock.withLock {
        val queue = getResolveQueue().toMutableList()
        val index = queue.indexOfFirst { existing -> existing.id == pending.id }
        if (index >= 0) {
            queue[index] = pending
            saveResolveQueue(queue)
        }
    }
    
    /**
     * Check if a resolve should be retried (exponential backoff)
     */
    /**
     * True once an item has burned its retry budget and should be dropped.
     *
     * Callers need this separately from [shouldRetry], which returns false both
     * for an exhausted item and for one that is merely still backing off.
     */
    fun isExhausted(pending: PendingResolve): Boolean =
        pending.attemptCount >= MAX_RETRY_ATTEMPTS

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
    
    /**
     * Reads a queue in the order it was written.
     *
     * These were stored as a Set<String>, which SharedPreferences hands back in
     * no particular order: deep links reached Flutter in an order unrelated to
     * the one they arrived in, an overflow trim dropped an arbitrary entry
     * rather than the oldest, and two entries that happened to serialize
     * identically collapsed into one. A JSON array preserves the order the code
     * already assumes it has.
     */
    private fun readQueue(key: String): List<JSONObject> {
        val prefs = Prefs.of()

        // Reading a key still holding the legacy Set through getString throws
        // ClassCastException, so both shapes are tried and the next write
        // migrates the key.
        val stored = try {
            prefs.getString(key, null)
        } catch (_: ClassCastException) {
            null
        }
        if (stored != null) {
            return try {
                val array = JSONArray(stored)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } catch (e: Exception) {
                Logger.e("Failed to parse queue $key, dropping it", e)
                emptyList()
            }
        }

        val legacy = try {
            prefs.getStringSet(key, null)
        } catch (_: ClassCastException) {
            null
        } ?: return emptyList()

        return legacy.mapNotNull { entry ->
            try {
                JSONObject(entry)
            } catch (e: Exception) {
                Logger.e("Failed to parse legacy queue entry in $key", e)
                null
            }
        }
    }

    private fun saveQueue(key: String, items: List<JSONObject>) {
        val array = JSONArray().apply { items.forEach { put(it) } }
        Prefs.of().edit().putString(key, array.toString()).apply()
    }

    private fun saveResolveQueue(queue: List<PendingResolve>) {
        saveQueue(KEY_PENDING_RESOLVE, queue.map { it.toJson() })
    }

    private fun saveDeliveryQueue(queue: List<PendingDelivery>) {
        saveQueue(KEY_PENDING_DELIVERY, queue.map { it.toJson() })
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
        inFlightDeliveries.clear()
        inFlightResolves.clear()
        Logger.d("Cleared all deep link queues")
    }

    /** True when neither queue has anything left to do. */
    fun isIdle(): Boolean = lock.withLock {
        readQueue(KEY_PENDING_RESOLVE).isEmpty() && readQueue(KEY_PENDING_DELIVERY).isEmpty()
    }
}

