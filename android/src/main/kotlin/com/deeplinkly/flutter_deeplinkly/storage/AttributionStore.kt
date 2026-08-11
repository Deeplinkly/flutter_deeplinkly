package com.deeplinkly.flutter_deeplinkly.storage

import org.json.JSONObject
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.network.optStringOrNull
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object AttributionStore {
    private const val KEY = "initial_attribution"
    private val lock = ReentrantLock()
    private val attributionListeners = mutableListOf<(Map<String, String>) -> Unit>()
    
    /**
     * Register a listener for attribution changes (event-based notification)
     */
    fun addListener(listener: (Map<String, String>) -> Unit) {
        lock.withLock {
            attributionListeners.add(listener)
        }
    }
    
    /**
     * Remove a listener
     */
    fun removeListener(listener: (Map<String, String>) -> Unit) {
        lock.withLock {
            attributionListeners.remove(listener)
        }
    }
    
    /**
     * Notify all listeners of attribution change
     */
    private fun notifyListeners(attribution: Map<String, String>) {
        lock.withLock {
            attributionListeners.forEach { listener ->
                try {
                    listener(attribution)
                } catch (e: Exception) {
                    com.deeplinkly.flutter_deeplinkly.core.Logger.e("Error in attribution listener", e)
                }
            }
        }
    }

    fun saveOnce(map: Map<String, String?>) {
        lock.withLock {
            val prefs = Prefs.of()
            if (!prefs.contains(KEY)) {
                val json = JSONObject(map.filterValues { it != null }).toString()
                prefs.edit().putString(KEY, json).apply()
                
                // Notify listeners of new attribution
                val attribution = get()
                notifyListeners(attribution)
            }
        }
    }
    
    fun get(): Map<String, String> {
        val prefs = Prefs.of()
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                // optStringOrNull, not optString(k, ""): on Android the latter
                // answers the literal string "null" for a JSON null, so a null
                // that reached storage would come back as attribution reading
                // `utm_source == "null"` - true for every isNullOrBlank check
                // the SDK and the host app make.
                obj.keys().forEach { k -> obj.optStringOrNull(k)?.let { put(k, it) } }
            }
        } catch (_: Exception) { emptyMap() }
    }
}
