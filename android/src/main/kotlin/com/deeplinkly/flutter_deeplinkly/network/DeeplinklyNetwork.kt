package com.deeplinkly.flutter_deeplinkly.network

import android.util.Log
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyCore
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Single consolidated networking + retry layer.
 *
 * Handles:
 *  - API endpoints (DomainConfig)
 *  - POST/GET helpers
 *  - Link generation, click resolution
 *  - Enrichment & error reporting
 *  - Simple offline retry queue (SharedPreferences-backed)
 */
object DeeplinklyNetwork {

    // -------------------- DomainConfig --------------------
    private const val BASE = "https://deeplinkly.com/api/v1"
    private const val LINK_ENDPOINT = "$BASE/generate-url"
    const val RESOLVE_CLICK_ENDPOINT = "$BASE/resolve"
    private const val ENRICH_ENDPOINT = "$BASE/enrich"
    private const val ERROR_ENDPOINT = "$BASE/error/report"

    // -------------------- Retry Queue --------------------
    private const val PREFS_KEY = "deeplinkly_retry_queue"

    private val prefs
        get() = DeeplinklyContext.app
            .getSharedPreferences("deeplinkly_prefs", android.content.Context.MODE_PRIVATE)

    private fun enqueue(task: JSONObject) {
        val existing = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        val arr = org.json.JSONArray(existing)
        arr.put(task)
        prefs.edit().putString(PREFS_KEY, arr.toString()).apply()
        DeeplinklyCore.w("Queued network task (${arr.length()})")
    }

    suspend fun retryAll(apiKey: String) = withContext(Dispatchers.IO) {
        val existing = prefs.getString(PREFS_KEY, null) ?: return@withContext
        val arr = org.json.JSONArray(existing)
        val remaining = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val task = arr.optJSONObject(i) ?: continue
            val url = task.optString("url")
            val body = task.optString("body")
            val type = task.optString("type", "POST")
            val ok = runCatching {
                if (type == "POST") doPost(url, body, apiKey)
                else doGet(url, apiKey)
            }.isSuccess
            if (!ok) remaining.put(task)
        }
        prefs.edit().putString(PREFS_KEY, remaining.toString()).apply()
    }

    // -------------------- Public API helpers --------------------

    suspend fun generateLink(payload: Map<String, Any?>, apiKey: String): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(payload)
                val response = doPost(LINK_ENDPOINT, json.toString(), apiKey)
                val url = response.optString("url")
                mapOf("success" to true, "url" to url)
            } catch (e: Exception) {
                DeeplinklyCore.e("generateLink failed", e)
                mapOf("success" to false, "error" to e.message)
            }
        }

    suspend fun resolveClick(url: String, apiKey: String): Pair<Int, JSONObject> =
        withContext(Dispatchers.IO) {
            val conn = makeConnection(url, apiKey, "GET")
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use(BufferedReader::readText)
            conn.disconnect()
            code to JSONObject(body)
        }

    suspend fun sendEnrichment(data: Map<String, String?>, apiKey: String) =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject(data.filterValues { it != null }).toString()
                doPost(ENRICH_ENDPOINT, body, apiKey)
                DeeplinklyCore.d("Enrichment sent (${data.size} fields)")
            } catch (e: Exception) {
                DeeplinklyCore.w("Enrichment failed; queueing")
                enqueue(JSONObject(mapOf("url" to ENRICH_ENDPOINT, "type" to "POST", "body" to JSONObject(data).toString())))
            }
        }

    suspend fun reportError(apiKey: String, title: String, stack: String, clickId: String? = null) =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("title", title)
                    put("stack", stack)
                    put("click_id", clickId)
                    put("session_id", DeeplinklyUtils.getSessionId())
                    put("device_id", DeeplinklyUtils.getOrCreateDeviceId())
                    put("reported_at", System.currentTimeMillis())
                }
                doPost(ERROR_ENDPOINT, payload.toString(), apiKey)
            } catch (e: Exception) {
                DeeplinklyCore.w("Failed to report error: ${e.message}")
            }
        }

    // -------------------- Internal HTTP helpers --------------------
    private fun makeConnection(url: String, apiKey: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("X-Request-Id", UUID.randomUUID().toString())
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.doInput = true
        if (method == "POST") conn.doOutput = true
        return conn
    }

    private fun doGet(url: String, apiKey: String): JSONObject {
        val conn = makeConnection(url, apiKey, "GET")
        conn.connect()
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream.bufferedReader().use(BufferedReader::readText)
        conn.disconnect()
        return JSONObject(body)
    }

    private fun doPost(url: String, body: String, apiKey: String): JSONObject {
        val conn = makeConnection(url, apiKey, "POST")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream.bufferedReader().use(BufferedReader::readText)
        conn.disconnect()
        return JSONObject(resp.ifBlank { "{}" }).apply { put("_status_code", code) }
    }

    // -------------------- JSON Extractors --------------------
    fun extractParamsFromJson(json: JSONObject, clickId: String?): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = json.optString(key, null)
        }
        if (clickId != null) map["click_id"] = clickId
        return map
    }
}
