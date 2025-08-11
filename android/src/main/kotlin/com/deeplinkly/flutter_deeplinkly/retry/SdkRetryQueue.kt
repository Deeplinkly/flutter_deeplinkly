package com.deeplinkly.flutter_deeplinkly.retry

import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.privacy.TrackingPreferences
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object SdkRetryQueue {
    private const val KEY = "sdk_retry_queue"
    private const val MAX_QUEUE_SIZE = 50

    fun enqueue(payload: JSONObject, type: String) {
        val current = getQueue().toMutableList()
        val wrapped = JSONObject().apply {
            put("type", type)
            put("payload", payload.toString())
        }
        current.add(wrapped.toString())
        if (current.size > MAX_QUEUE_SIZE) current.removeAt(0)
        Prefs.of().edit().putStringSet(KEY, current.toSet()).apply()
    }

    fun getQueue(): List<String> =
        Prefs.of().getStringSet(KEY, emptySet())?.toList() ?: emptyList()

    fun remove(json: String) {
        val current = getQueue().toMutableSet()
        current.remove(json)
        Prefs.of().edit().putStringSet(KEY, current).apply()
    }

    suspend fun retryAll(apiKey: String) = withContext(Dispatchers.IO) {
        if (TrackingPreferences.isTrackingDisabled()) {
            Logger.d("Tracking is disabled. Skipping retry queue.")
            return@withContext
        }
        for (json in getQueue()) {
            try {
                val obj = JSONObject(json)
                val type = obj.getString("type")
                val payload = JSONObject(obj.getString("payload"))
                when (type) {
                    "enrichment" -> NetworkUtils.sendEnrichmentNow(payload, apiKey)
                    "error"      -> NetworkUtils.sendErrorNow(payload, apiKey)
                    else         -> Logger.w("Unknown SDK retry type: $type")
                }
                remove(json)
            } catch (e: Exception) {
                Logger.e("Retry failed for $json", e) // keep in queue
            }
        }
    }
}
