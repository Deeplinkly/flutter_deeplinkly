package com.deeplinkly.flutter_deeplinkly.storage

import org.json.JSONObject
import com.deeplinkly.flutter_deeplinkly.core.Prefs

object AttributionStore {
    private const val KEY = "initial_attribution"

    fun saveOnce(map: Map<String, String?>) {
        val prefs = Prefs.of()
        if (!prefs.contains(KEY)) {
            val json = JSONObject(map.filterValues { it != null }).toString()
            prefs.edit().putString(KEY, json).apply()
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
