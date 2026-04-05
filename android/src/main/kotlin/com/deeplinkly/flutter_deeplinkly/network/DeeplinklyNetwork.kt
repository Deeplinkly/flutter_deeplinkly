package com.deeplinkly.flutter_deeplinkly.network

import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.privacy.TrackingPreferences
import com.deeplinkly.flutter_deeplinkly.retry.SdkRetryQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Single consolidated Android networking layer.
 */
object DeeplinklyNetwork {
    const val RESOLVE_CLICK_ENDPOINT = DomainConfig.RESOLVE_CLICK_ENDPOINT

    /** Backend /log-event returns 200 for accepted payloads; treat full 2xx as success. */
    private fun isHttpSuccess(code: Int) = code in 200..299

    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            var value = this[key]
            if (value is JSONObject) value = value.toMap()
            map[key] = value
        }
        return map
    }

    fun generateLink(payload: Map<String, Any?>, apiKey: String): Map<String, Any?> {
        val response = doPost(DomainConfig.GENERATE_LINK_ENDPOINT, JSONObject(payload).toString(), apiKey)
        val responseMap = response.toMap().toMutableMap()
        responseMap.remove("_status_code")
        return responseMap
    }

    fun resolveClick(url: String, apiKey: String): Pair<String, JSONObject> {
        val conn = openConnection(url, apiKey, "GET").apply {
            setRequestProperty("Accept", "application/json")
        }
        val responseCode = conn.responseCode
        val responseBody = if (responseCode == 200) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            throw Exception("HTTP $responseCode: $errorBody")
        }
        return responseBody to JSONObject(responseBody)
    }

    suspend fun resolveClickWithRetry(
        url: String,
        apiKey: String,
        maxRetries: Int = 3,
        initialDelayMs: Long = 100
    ): Pair<String, JSONObject> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        var delayMs = initialDelayMs

        repeat(maxRetries) { attempt ->
            try {
                return@withContext resolveClick(url, apiKey)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(delayMs)
                    Logger.w("Resolve click retry ${attempt + 1}/$maxRetries after ${delayMs}ms")
                    delayMs *= 2
                }
            }
        }

        throw lastException ?: Exception("Failed to resolve click after $maxRetries attempts")
    }

    fun extractParamsFromJson(json: JSONObject, clickId: String?): HashMap<String, Any?> {
        val params = json.optJSONObject("params")
        val out = hashMapOf<String, Any?>()
        out["click_id"] = clickId ?: json.optString("click_id", null)

        if (params != null) {
            out["params"] = params.toString()
            params.keys().forEach { key -> out[key] = params.opt(key) }
        }
        return out
    }

    fun sendEnrichment(data: Map<String, String?>, apiKey: String) {
        if (TrackingPreferences.isTrackingDisabled()) return
        try {
            val json = JSONObject(data.filterValues { it != null })
            doPost(DomainConfig.ENRICH_ENDPOINT, json.toString(), apiKey)
        } catch (e: Exception) {
            Logger.e("Enrichment failed, queueing", e)
            val payload = JSONObject(data.filterValues { it != null })
            SdkRetryQueue.enqueue(payload, "enrichment")
        }
    }

    fun logEvent(eventName: String, parameters: Map<String, Any?>, apiKey: String): Boolean {
        if (TrackingPreferences.isTrackingDisabled()) return false
        return try {
            val payload = JSONObject(
                mapOf(
                    "event_name" to eventName,
                    "parameters" to JSONObject(parameters)
                )
            )
            val response = doPost(DomainConfig.LOG_EVENT_ENDPOINT, payload.toString(), apiKey)
            isHttpSuccess(response.optInt("_status_code", 0))
        } catch (e: Exception) {
            Logger.e("logEvent failed, queueing", e)
            val payload = JSONObject(
                mapOf(
                    "event_name" to eventName,
                    "parameters" to JSONObject(parameters)
                )
            )
            SdkRetryQueue.enqueue(payload, "event")
            false
        }
    }

    suspend fun sendEventNow(payload: JSONObject, apiKey: String) = withContext(Dispatchers.IO) {
        val response = doPost(DomainConfig.LOG_EVENT_ENDPOINT, payload.toString(), apiKey)
        val code = response.optInt("_status_code", 0)
        if (!isHttpSuccess(code)) {
            throw Exception("Non-2xx event response: $code")
        }
    }

    suspend fun sendEnrichmentNow(payload: JSONObject, apiKey: String) = withContext(Dispatchers.IO) {
        val response = doPost(DomainConfig.ENRICH_ENDPOINT, payload.toString(), apiKey)
        val code = response.optInt("_status_code", 0)
        if (!isHttpSuccess(code)) {
            throw Exception("Non-2xx enrichment response: $code")
        }
    }

    fun reportError(apiKey: String, message: String, stack: String, clickId: String? = null) {
        if (TrackingPreferences.isTrackingDisabled()) return
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
    }

    suspend fun sendErrorNow(payload: JSONObject, apiKey: String) = withContext(Dispatchers.IO) {
        val response = doPost(DomainConfig.ERROR_ENDPOINT, payload.toString(), apiKey)
        val code = response.optInt("_status_code", 0)
        if (!isHttpSuccess(code)) {
            throw Exception("Non-2xx sdk-error response: $code")
        }
    }

    private fun doPost(url: String, body: String, apiKey: String): JSONObject {
        val conn = openConnection(url, apiKey, "POST")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return JSONObject(response.ifBlank { "{}" }).apply { put("_status_code", code) }
    }

    private fun openConnection(url: String, apiKey: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            doInput = true
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Request-Id", UUID.randomUUID().toString())
            setRequestProperty("X-Deeplinkly-User-Id", DeeplinklyUtils.getOrCreateDeviceId())
            DeeplinklyUtils.getCustomUserId()?.let {
                setRequestProperty("X-Deeplinkly-Custom-User-Id", it)
            }
        }
}
