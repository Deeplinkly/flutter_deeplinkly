package com.deeplinkly.flutter_deeplinkly.network

import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.privacy.TrackingPreferences
import com.deeplinkly.flutter_deeplinkly.retry.SdkRetryQueue
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object NetworkUtils {

    fun resolveClick(url: String, apiKey: String): Pair<String, JSONObject> {
        val conn = openConnection(url, apiKey).apply {
            setRequestProperty("Accept", "application/json")
        }
        val responseCode = conn.responseCode
        val response = if (responseCode == 200) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            throw Exception("HTTP $responseCode: $errorBody")
        }
        return Pair(response, JSONObject(response))
    }
    
    /**
     * Resolve click with retry mechanism (for queue processing)
     */
    suspend fun resolveClickWithRetry(
        url: String,
        apiKey: String,
        maxRetries: Int = 3,
        initialDelayMs: Long = 100
    ): Pair<String, JSONObject> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        var delay = initialDelayMs
        
        repeat(maxRetries) { attempt ->
            try {
                return@withContext resolveClick(url, apiKey)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    delay *= 2 // Exponential backoff
                    kotlinx.coroutines.delay(delay)
                    Logger.w("Resolve click retry ${attempt + 1}/$maxRetries after ${delay}ms")
                }
            }
        }
        
        throw lastException ?: Exception("Failed to resolve click after $maxRetries attempts")
    }

    fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            var value = this[key]
            if (value is JSONObject) value = value.toMap()
            map[key] = value
        }
        return map
    }

    fun generateLink(payload: Map<String, Any?>, apiKey: String): Map<String, Any?> {
        val conn = openConnection(DomainConfig.GENERATE_LINK_ENDPOINT, apiKey).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        val json = JSONObject(payload)
        conn.outputStream.use { it.write(json.toString().toByteArray()) }
        val responseBody = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
        }
        val jsonResponse = JSONObject(responseBody)
        return jsonResponse.toMap()
    }

    fun extractParamsFromJson(json: JSONObject, clickId: String?): HashMap<String, Any?> {
        val params = json.optJSONObject("params") ?: JSONObject()
        return hashMapOf<String, Any?>().apply {
            put("click_id", clickId ?: json.optString("click_id", null))
            params.keys().forEach { key -> put(key, params.get(key)) }
        }
    }

    fun sendEnrichment(data: Map<String, String?>, apiKey: String) {
        if (TrackingPreferences.isTrackingDisabled()) return
        try {
            val conn = openConnection(DomainConfig.ENRICH_ENDPOINT, apiKey).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val json = JSONObject(data.filterValues { it != null })
            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            if (conn.responseCode != 200) throw Exception("Non-200 response: ${conn.responseCode}")
        } catch (e: Exception) {
            Logger.e("Enrichment failed, queueing", e)
            val json = JSONObject(data.filterValues { it != null })
            SdkRetryQueue.enqueue(json, "enrichment")
        }
    }

    suspend fun sendEnrichmentNow(payload: JSONObject, apiKey: String) = withContext(Dispatchers.IO) {
        val conn = openConnection(DomainConfig.ENRICH_ENDPOINT, apiKey).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        if (conn.responseCode != 200) throw Exception("Non-200 enrichment response: ${conn.responseCode}")
    }

    fun reportError(apiKey: String, message: String, stack: String, clickId: String? = null) {
        if (TrackingPreferences.isTrackingDisabled()) return
        try {
            val payload = JSONObject().apply {
                put("message", message)
                put("stack", stack)
                clickId?.let { put("click_id", it) }
            }
            SdkRuntime.ioLaunch {
                try {
                    sendErrorNow(payload, apiKey)
                } catch (e: Exception) {
                    Logger.e("Error reporting failed, queueing", e)
                    SdkRetryQueue.enqueue(payload, "error")
                }
            }
        } catch (e: Exception) {
            Logger.e("Error reporting failed, queueing", e)
            val payload = JSONObject().apply {
                put("message", message)
                put("stack", stack)
                clickId?.let { put("click_id", it) }
            }
            SdkRetryQueue.enqueue(payload, "error")
        }
    }

    suspend fun sendErrorNow(payload: JSONObject, apiKey: String) = withContext(Dispatchers.IO) {
        val conn = openConnection(DomainConfig.ERROR_ENDPOINT, apiKey).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        if (conn.responseCode != 200) throw Exception("Non-200 error report response: ${conn.responseCode}")
    }

    private fun openConnection(url: String, apiKey: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")

            // IDs on every call
            val sp = Prefs.of()
            val customUserId = sp.getString("custom_user_id", null)
            val deeplinklyUserId = DeeplinklyUtils.getOrCreateDeviceId()
            customUserId?.let { setRequestProperty("X-Custom-User-Id", it) }
            setRequestProperty("X-Deeplinkly-User-Id", deeplinklyUserId)
        }



    private fun getOrPersistDeviceId(): String {
        val sp = Prefs.of()
        val key = "dl_device_id"
        val existing = sp.getString(key, null)
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        sp.edit().putString(key, fresh).apply()
        return fresh
    }
}
