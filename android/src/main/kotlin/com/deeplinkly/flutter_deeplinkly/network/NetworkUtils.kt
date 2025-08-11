package com.deeplinkly.flutter_deeplinkly.network

import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.privacy.TrackingPreferences
import com.deeplinkly.flutter_deeplinkly.retry.SdkRetryQueue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NetworkUtils {

    fun resolveClick(url: String, apiKey: String): Pair<String, JSONObject> {
        val conn = openConnection(url, apiKey).apply {
            setRequestProperty("Accept", "application/json")
        }
        val response = conn.inputStream.bufferedReader().readText()
        return Pair(response, JSONObject(response))
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
        }
}
