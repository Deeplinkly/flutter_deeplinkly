package com.deeplinkly.flutter_deeplinkly.storage

import org.json.JSONObject
import com.deeplinkly.flutter_deeplinkly.core.Prefs
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
    
    /**
     * Save and merge with existing attribution (thread-safe)
     */
    fun saveAndMerge(map: Map<String, String?>) {
        lock.withLock {
            val prefs = Prefs.of()
            val existing = get()
            val merged = mutableMapOf<String, String?>()
            
            // Add existing attribution
            existing.forEach { (key, value) ->
                merged[key] = value
            }
            
            // Merge with new data (new data takes precedence for non-null values)
            map.forEach { (key, value) ->
                if (value != null) {
                    merged[key] = value
                }
            }
            
            val json = JSONObject(merged.filterValues { it != null }).toString()
            prefs.edit().putString(KEY, json).apply()
            
            // Notify listeners
            val attribution = get()
            notifyListeners(attribution)
        }
    }

    fun get(): Map<String, String> {
        val prefs = Prefs.of()
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { k -> put(k, obj.optString(k, "")) }
            }
        } catch (_: Exception) { emptyMap() }
    }
}
