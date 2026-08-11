package com.deeplinkly.flutter_deeplinkly.network

import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.core.DeviceProfile
import com.deeplinkly.flutter_deeplinkly.core.DynamicSignals
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.privacy.AttributionLevel
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.privacy.TrackingPreferences
import com.deeplinkly.flutter_deeplinkly.retry.SdkRetryQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * A non-2xx response, carrying the status so callers can tell a transient
 * failure from one that will never succeed.
 */
class DeeplinklyHttpException(
    val statusCode: Int,
    val body: String
) : Exception("HTTP $statusCode: $body") {
    /**
     * A 4xx will not start succeeding on retry - the API key is invalid, the
     * account is suspended, or the payload is malformed. 408 and 429 are the
     * exceptions: both are transient and worth backing off on.
     */
    val isTerminal: Boolean
        get() = statusCode in 400..499 && statusCode != 408 && statusCode != 429
}

/** True when [this] is a response the server will keep rejecting. */
internal fun Throwable.isTerminalHttp(): Boolean =
    (this as? DeeplinklyHttpException)?.isTerminal == true

/**
 * Converts a parsed JSON object into plain Kotlin values.
 *
 * The method-channel codec only understands Kotlin/Java types, so a JSONObject
 * left anywhere inside an `onDeepLink` payload makes the whole call
 * unencodable - which is how a deep link that had been through the delivery
 * queue could be dropped while the same link delivered directly went through.
 * JSONObject.NULL has to become a real null for the same reason.
 */
internal fun JSONObject.toValueMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    keys().forEach { key -> map[key] = unwrapJsonValue(opt(key)) }
    return map
}

internal fun JSONArray.toValueList(): List<Any?> =
    (0 until length()).map { index -> unwrapJsonValue(opt(index)) }

/**
 * Reads an optional string, answering null for a missing key or a JSON null.
 *
 * `optString(key, null)` is not a safe substitute: it hands back the fallback as
 * a platform type, so `optString(key, null).takeIf { it.isNotBlank() }` throws
 * NullPointerException on the very keys it is meant to tolerate. And the
 * one-argument `optString(key)` answers the literal string "null" for a JSON
 * null on Android.
 */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun unwrapJsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL, null -> null
    is JSONObject -> value.toValueMap()
    is JSONArray -> value.toValueList()
    else -> value
}

/**
 * Single consolidated Android networking layer.
 */
object DeeplinklyNetwork {
    const val RESOLVE_CLICK_ENDPOINT = DomainConfig.RESOLVE_CLICK_ENDPOINT

    /** Backend /log-event returns 200 for accepted payloads; treat full 2xx as success. */
    private fun isHttpSuccess(code: Int) = code in 200..299

    /**
     * Recursively converts a Kotlin map to a JSONObject.
     *
     * The JSONObject(Map) constructor leans on JSONObject.wrap() to handle nested
     * collections, which is not guaranteed across every org.json implementation the
     * SDK can run against. Converting explicitly keeps nested maps and lists as real
     * JSON structures instead of their Java toString() form ("{sku=A1}"), which the
     * backend would then store as an opaque string.
     */
    internal fun toJsonObject(map: Map<*, *>): JSONObject {
        val out = JSONObject()
        map.forEach { (k, v) -> out.put(k.toString(), toJsonValue(v)) }
        return out
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> toJsonObject(value)
        is Iterable<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJsonValue(it)) } }
        is Array<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJsonValue(it)) } }
        is String, is Number, is Boolean -> value
        else -> value.toString()
    }

    /**
     * Normalizes /generate-url into the shape DeeplinklyResult expects.
     *
     * The backend's success body is `{"success": true, "url": ...}`, but older
     * deployments answer with a bare `{"url": ...}`. Returning the raw body meant
     * `DeeplinklyResult.success` read null and reported failure alongside a
     * perfectly good URL, so the flag is derived here rather than trusted.
     */
    internal fun generateLinkResult(response: JSONObject): Map<String, Any?> {
        val status = response.optInt("_status_code", 0)
        val url = response.optString("url", "").takeIf { it.isNotBlank() }

        if (!isHttpSuccess(status)) {
            val code = response.optString("code", "").takeIf { it.isNotBlank() } ?: "HTTP_$status"
            val message = listOf("message", "error", "error_message")
                .firstNotNullOfOrNull { response.optString(it, "").takeIf { m -> m.isNotBlank() } }
                ?: "Link generation failed (HTTP $status)"
            return mapOf("success" to false, "error_code" to code, "error_message" to message)
        }

        if (url == null) {
            return mapOf(
                "success" to false,
                "error_code" to "NO_URL",
                "error_message" to "No 'url' field in response"
            )
        }

        return mapOf("success" to true, "url" to url)
    }

    fun generateLink(payload: Map<String, Any?>, apiKey: String): Map<String, Any?> {
        val response = doPost(DomainConfig.GENERATE_LINK_ENDPOINT, toJsonObject(payload).toString(), apiKey)
        return generateLinkResult(response)
    }

    fun resolveClick(url: String, apiKey: String): Pair<String, JSONObject> {
        val conn = openConnection(url, apiKey, "GET").apply {
            setRequestProperty("Accept", "application/json")
        }
        val responseCode = conn.responseCode
        // Any 2xx is a success; the endpoint answers 200 today but pinning the
        // check to exactly 200 made every other success a thrown exception.
        val responseBody = if (isHttpSuccess(responseCode)) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw DeeplinklyHttpException(responseCode, errorBody)
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
                // Burning the remaining attempts on a 401/402/403 just multiplies
                // the requests a suspended or revoked tenant sends.
                if (e.isTerminalHttp()) throw e
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(delayMs)
                    Logger.w("Resolve click retry ${attempt + 1}/$maxRetries after ${delayMs}ms")
                    delayMs *= 2
                }
            }
        }

        throw lastException ?: Exception("Failed to resolve click after $maxRetries attempts")
    }

    /**
     * True when the backend did not recognise the click id we asked about.
     *
     * /resolve answers an unknown click_id with HTTP 200 and
     * `{"click_id": null, "params": {}, "stale": true}` rather than a 404, so
     * the flag is the only signal that nothing was actually resolved.
     */
    fun isStale(json: JSONObject): Boolean = json.optBoolean("stale", false)

    fun extractParamsFromJson(json: JSONObject, clickId: String?): HashMap<String, Any?> {
        val params = json.optJSONObject("params")
        val out = hashMapOf<String, Any?>()
        // Falling back to the *requested* click id would report a stale click as
        // a live one, so on a stale response the id has to come from the body
        // (where it is null) and never from what we asked for.
        val bodyClickId = json.optStringOrNull("click_id")
        out["click_id"] = if (isStale(json)) bodyClickId else (clickId ?: bodyClickId)

        if (params != null) {
            out["params"] = params.toValueMap()
        }
        if (!json.isNull("probability")) {
            out["probability"] = json.optDouble("probability", -1.0).takeIf { it >= 0 }
        }
        return out
    }

    /**
     * The payload delivered when /resolve never answered.
     *
     * Dart reads a deep link's values off `params` (see [extractParamsFromJson]),
     * so the flat map of query parameters the fallback paths used to send was
     * invisible to any app written against a resolved link - the link arrived
     * carrying nothing the app knew how to read. Fallbacks now keep the same
     * `{click_id, params}` envelope, leaving Dart one shape to handle whether or
     * not the backend answered.
     */
    fun fallbackPayload(
        clickId: String?,
        localParams: Map<String, String?>
    ): HashMap<String, Any?> = hashMapOf(
        "click_id" to clickId,
        // click_id is the envelope's own key; repeating it inside params would
        // have Dart read the same value from two places.
        "params" to localParams.filterKeys { it != "click_id" }.filterValues { it != null }
    )

    /** Attribution keys the backend surfaces inside the resolve response's "params". */
    private val ATTRIBUTION_KEYS = listOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "gclid", "fbclid", "ttclid"
    )

    /**
     * The click-time attribution params worth forwarding to /resolve.
     *
     * Only the keys the backend actually reads (_get_utm and
     * _get_tracking_param in links/views.py). The link's other query parameters
     * are the host app's own data and have no business being recorded against
     * the click.
     */
    internal fun attributionQuery(localParams: Map<String, String?>): Map<String, String> =
        ATTRIBUTION_KEYS.mapNotNull { key ->
            localParams[key]?.takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap()

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    /**
     * Builds a GET /resolve URL.
     *
     * Two things this centralises. The click id and code are percent-encoded
     * rather than pasted in raw, which they were at all three call sites. And
     * the click-time attribution params ride along: resolving by `code` makes
     * the backend *create* the ClickEvent (an App Link opened the app directly,
     * so the server never saw the click), and create_click_event reads UTMs and
     * ad-click ids off request.GET. Without them, every UTM on a link that
     * opened through a verified App Link was dropped on the floor.
     */
    fun resolveUrl(
        clickId: String?,
        code: String?,
        localParams: Map<String, String?> = emptyMap()
    ): String {
        val query = linkedMapOf<String, String>()
        when {
            clickId != null -> query["click_id"] = clickId
            code != null -> query["code"] = code
        }
        query.putAll(attributionQuery(localParams))
        return "$RESOLVE_CLICK_ENDPOINT?${encodeQuery(query)}"
    }

    /**
     * Builds the normalized attribution snapshot persisted by AttributionStore.
     *
     * The backend nests UTM and ad-click params inside "params" (click-time values
     * merged over the link's own metadata) - see _click_attribution_params in
     * links/views.py. Reading them off the top level of the map returned by
     * [extractParamsFromJson] always yielded null, so every snapshot taken on the
     * deep-link path carried nothing but a source and a click_id. Every caller goes
     * through here so that nesting is unwrapped in exactly one place.
     */
    fun attributionSnapshot(
        resolved: Map<String, Any?>,
        source: String,
        fallbackClickId: String? = null
    ): LinkedHashMap<String, String?> {
        val params = resolved["params"] as? Map<*, *>
        val out = linkedMapOf<String, String?>(
            "source" to source,
            "click_id" to ((resolved["click_id"] as? String) ?: fallbackClickId)
        )
        ATTRIBUTION_KEYS.forEach { key ->
            // params values arrive from JSON, so they are not necessarily Strings.
            val raw = params?.get(key)
            out[key] = if (raw == null || raw == JSONObject.NULL) {
                null
            } else {
                raw.toString().takeIf { it.isNotBlank() }
            }
        }
        return out
    }

    fun sendEnrichment(data: Map<String, String?>, apiKey: String) {
        if (TrackingPreferences.isTrackingDisabled()) return
        val payload = JSONObject(data.filterValues { it != null })
        try {
            val response = doPost(DomainConfig.ENRICH_ENDPOINT, payload.toString(), apiKey)
            val code = response.optInt("_status_code", 0)
            // doPost reports non-2xx through the body rather than throwing, so the
            // status has to be inspected here for the response to be retried at all.
            if (!isHttpSuccess(code)) {
                val failure = DeeplinklyHttpException(code, response.toString())
                if (failure.isTerminal) {
                    Logger.w("Enrichment rejected (HTTP $code), not queueing")
                } else {
                    Logger.e("Enrichment failed, queueing", failure)
                    SdkRetryQueue.enqueue(payload, "enrichment")
                }
            }
        } catch (e: Exception) {
            Logger.e("Enrichment failed, queueing", e)
            SdkRetryQueue.enqueue(payload, "enrichment")
        }
    }

    fun logEvent(eventName: String, parameters: Map<String, Any?>, apiKey: String): Boolean {
        if (TrackingPreferences.isTrackingDisabled()) return false
        val payload = JSONObject().apply {
            put("event_name", eventName)
            put("parameters", toJsonObject(parameters))
            // A sibling of "parameters", never inside it: parameters is what
            // the tenant reads in their dashboard, and its values are truncated
            // at 256 chars and capped at 25 keys. Nesting is safe here because
            // this body is built fresh at the call site and never stored as a
            // flat map, unlike /enrich.
            deviceBlock()?.let { put("device", it) }
        }
        return try {
            val response = doPost(DomainConfig.LOG_EVENT_ENDPOINT, payload.toString(), apiKey)
            isHttpSuccess(response.optInt("_status_code", 0))
        } catch (e: Exception) {
            Logger.e("logEvent failed, queueing", e)
            SdkRetryQueue.enqueue(payload, "event")
            false
        }
    }

    /**
     * The device state to report alongside an event, filtered to the current
     * attribution level.
     *
     * Null at level `none`, where nothing describing the device may be sent —
     * the event itself still goes, since level gates reporting rather than
     * functionality.
     */
    private fun deviceBlock(): JSONObject? {
        val level = AttributionLevel.current
        if (!level.allowsEnrichment) return null
        return try {
            val signals = DynamicSignals.assemble(DeviceProfile.get())
            val filtered = level.filter(signals).filterValues { it != null }
            if (filtered.isEmpty()) null else JSONObject(filtered)
        } catch (e: Exception) {
            // An event is worth sending even if the device half fails.
            Logger.w("Could not build the event device block: ${e.message}")
            null
        }
    }

    suspend fun sendEventNow(payload: JSONObject, apiKey: String) = withContext(Dispatchers.IO) {
        val response = doPost(DomainConfig.LOG_EVENT_ENDPOINT, payload.toString(), apiKey)
        val code = response.optInt("_status_code", 0)
        if (!isHttpSuccess(code)) {
            throw DeeplinklyHttpException(code, response.toString())
        }
    }

    suspend fun sendEnrichmentNow(payload: JSONObject, apiKey: String) = withContext(Dispatchers.IO) {
        val response = doPost(DomainConfig.ENRICH_ENDPOINT, payload.toString(), apiKey)
        val code = response.optInt("_status_code", 0)
        if (!isHttpSuccess(code)) {
            throw DeeplinklyHttpException(code, response.toString())
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
            throw DeeplinklyHttpException(code, response.toString())
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
