// FILE: com/deeplinkly/flutter_deeplinkly/storage/AttributionStore.kt
package com.deeplinkly.flutter_deeplinkly.storage

import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import org.json.JSONObject

object AttributionStore {
    private const val KEY = "initial_attribution"
    private val prefs get() =
        DeeplinklyContext.app.getSharedPreferences("deeplinkly_prefs", 0)

    fun saveOnce(map: Map<String, String?>) {
        if (!prefs.contains(KEY)) {
            val json = JSONObject(map.filterValues { it != null }).toString()
            prefs.edit().putString(KEY, json).apply()
        }
    }

    fun get(): Map<String, String> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { k -> put(k, obj.optString(k, "")) }
            }
        } catch (_: Exception) { emptyMap() }
    }
}
