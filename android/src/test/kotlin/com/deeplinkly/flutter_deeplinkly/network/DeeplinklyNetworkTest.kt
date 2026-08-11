package com.deeplinkly.flutter_deeplinkly.network

import com.deeplinkly.flutter_deeplinkly.privacy.SignalCatalogue
import com.deeplinkly.flutter_deeplinkly.privacy.SignalScope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeeplinklyNetworkTest {

    // --- DeeplinklyHttpException.isTerminal -------------------------------

    @Test
    fun `client errors are terminal`() {
        for (status in listOf(400, 401, 402, 403, 404)) {
            assertTrue("HTTP $status should be terminal", DeeplinklyHttpException(status, "").isTerminal)
        }
    }

    @Test
    fun `timeout and rate limit are not terminal`() {
        assertFalse(DeeplinklyHttpException(408, "").isTerminal)
        assertFalse(DeeplinklyHttpException(429, "").isTerminal)
    }

    @Test
    fun `server errors are not terminal`() {
        for (status in listOf(500, 502, 503)) {
            assertFalse("HTTP $status should be retryable", DeeplinklyHttpException(status, "").isTerminal)
        }
    }

    @Test
    fun `isTerminalHttp is false for ordinary exceptions`() {
        assertFalse(java.io.IOException("socket closed").isTerminalHttp())
        assertTrue(DeeplinklyHttpException(403, "").isTerminalHttp())
    }

    // --- resolveUrl -------------------------------------------------------

    /**
     * Resolving by code makes the backend *create* the ClickEvent, and
     * create_click_event reads UTMs and ad-click ids straight off request.GET.
     * They used to be left behind entirely, so every UTM on a link that opened
     * through a verified App Link was lost.
     */
    @Test
    fun `resolve by code forwards the click-time attribution params`() {
        val url = DeeplinklyNetwork.resolveUrl(
            clickId = null,
            code = "abc123",
            localParams = mapOf(
                "utm_source" to "twitter",
                "utm_campaign" to "spring sale",
                "gclid" to "XYZ"
            )
        )

        assertTrue(url, url.contains("code=abc123"))
        assertTrue(url, url.contains("utm_source=twitter"))
        assertTrue(url, url.contains("gclid=XYZ"))
        // Values are encoded, not pasted in raw.
        assertTrue(url, url.contains("utm_campaign=spring+sale"))
    }

    /** The host app's own query parameters are none of the backend's business. */
    @Test
    fun `resolve url forwards only attribution params`() {
        val url = DeeplinklyNetwork.resolveUrl(
            clickId = "click-1",
            code = null,
            localParams = mapOf("utm_source" to "twitter", "auth_token" to "secret")
        )

        assertTrue(url, url.contains("utm_source=twitter"))
        assertFalse(url, url.contains("auth_token"))
        assertFalse(url, url.contains("secret"))
    }

    /** A click id wins over a code; sending both would be ambiguous. */
    @Test
    fun `resolve url prefers the click id`() {
        val url = DeeplinklyNetwork.resolveUrl("click-1", "abc123")
        assertTrue(url, url.contains("click_id=click-1"))
        assertFalse(url, url.contains("code="))
    }

    @Test
    fun `resolve url percent-encodes identifiers`() {
        val url = DeeplinklyNetwork.resolveUrl(clickId = null, code = "a b&c")
        assertTrue(url, url.contains("code=a+b%26c"))
    }

    /**
     * Resolving names the link and nothing else. /resolve has never matched a
     * click to an install on device signals, so anything describing the device
     * on this call is shipped for nothing — and at a level the user may have
     * declined, since the resolve path is not filtered by AttributionLevel.
     */
    @Test
    fun `resolve url carries no device signals`() {
        val url = DeeplinklyNetwork.resolveUrl(
            clickId = "click-1",
            code = null,
            localParams = mapOf("utm_source" to "twitter")
        )

        val deviceKeys = SignalCatalogue.keysFor(SignalScope.STATIC) +
            SignalCatalogue.keysFor(SignalScope.DYNAMIC)
        for (key in deviceKeys) {
            assertFalse("$url leaked $key", url.contains("$key="))
        }
        assertFalse(url, url.contains("fingerprint"))
    }

    @Test
    fun `blank attribution values are dropped rather than sent empty`() {
        val query = DeeplinklyNetwork.attributionQuery(
            mapOf("utm_source" to "", "utm_medium" to null, "utm_campaign" to "launch")
        )
        assertEquals(mapOf("utm_campaign" to "launch"), query)
    }

    // --- generateLinkResult ----------------------------------------------

    @Test
    fun `success maps url even when backend omits the success flag`() {
        // Older backends answer with a bare {"url": ...}; the flag has to be
        // derived or DeeplinklyResult reports a failure alongside a good URL.
        val response = JSONObject().put("url", "https://d.example/abc").put("_status_code", 200)
        val out = DeeplinklyNetwork.generateLinkResult(response)

        assertEquals(true, out["success"])
        assertEquals("https://d.example/abc", out["url"])
    }

    @Test
    fun `success passes through when backend sends the success flag`() {
        val response = JSONObject()
            .put("success", true)
            .put("url", "https://d.example/abc")
            .put("_status_code", 200)

        assertEquals(true, DeeplinklyNetwork.generateLinkResult(response)["success"])
    }

    @Test
    fun `2xx without a url reports NO_URL`() {
        val response = JSONObject().put("_status_code", 200)
        val out = DeeplinklyNetwork.generateLinkResult(response)

        assertEquals(false, out["success"])
        assertEquals("NO_URL", out["error_code"])
    }

    @Test
    fun `billing failure surfaces the backend error code`() {
        val response = JSONObject()
            .put("code", "ER_011")
            .put("message", "Account paused for billing.")
            .put("_status_code", 402)
        val out = DeeplinklyNetwork.generateLinkResult(response)

        assertEquals(false, out["success"])
        assertEquals("ER_011", out["error_code"])
        assertEquals("Account paused for billing.", out["error_message"])
    }

    @Test
    fun `error without a code falls back to the status`() {
        val response = JSONObject().put("_status_code", 500)
        val out = DeeplinklyNetwork.generateLinkResult(response)

        assertEquals(false, out["success"])
        assertEquals("HTTP_500", out["error_code"])
    }

    @Test
    fun `400 error body uses the error field as the message`() {
        val response = JSONObject()
            .put("error", "Missing canonical_identifier")
            .put("_status_code", 400)

        assertEquals(
            "Missing canonical_identifier",
            DeeplinklyNetwork.generateLinkResult(response)["error_message"]
        )
    }

    // --- toJsonObject -----------------------------------------------------

    @Test
    fun `nested maps and lists become real json structures`() {
        val payload = mapOf(
            "items" to listOf(mapOf("sku" to "A1", "qty" to 2)),
            "meta" to mapOf("nested" to mapOf("deep" to true))
        )

        // Round-trip: the Java toString() form ("{sku=A1}") would not parse back.
        val parsed = JSONObject(DeeplinklyNetwork.toJsonObject(payload).toString())

        assertEquals("A1", parsed.getJSONArray("items").getJSONObject(0).getString("sku"))
        assertEquals(2, parsed.getJSONArray("items").getJSONObject(0).getInt("qty"))
        assertTrue(parsed.getJSONObject("meta").getJSONObject("nested").getBoolean("deep"))
    }

    @Test
    fun `scalars keep their json types`() {
        val parsed = JSONObject(
            DeeplinklyNetwork.toJsonObject(
                mapOf("amount" to 49.99, "flag" to true, "name" to "x", "count" to 3)
            ).toString()
        )

        assertEquals(49.99, parsed.getDouble("amount"), 0.0001)
        assertTrue(parsed.getBoolean("flag"))
        assertEquals("x", parsed.getString("name"))
        assertEquals(3, parsed.getInt("count"))
    }

    @Test
    fun `nulls survive as json null`() {
        val obj = DeeplinklyNetwork.toJsonObject(mapOf("missing" to null))
        assertTrue(obj.isNull("missing"))
    }

    // --- extractParamsFromJson -------------------------------------------

    @Test
    fun `resolved params are unwrapped into plain kotlin values`() {
        val json = JSONObject(
            """{"click_id":"abc123","params":{"screen":"microdramas","episode_index":50,"note":null}}"""
        )

        val out = DeeplinklyNetwork.extractParamsFromJson(json, "abc123")

        assertEquals("abc123", out["click_id"])
        @Suppress("UNCHECKED_CAST")
        val params = out["params"] as Map<String, Any?>
        assertEquals("microdramas", params["screen"])
        assertEquals(50, params["episode_index"])
        // JSONObject.NULL is not encodable by the method channel codec.
        assertTrue(params.containsKey("note"))
        assertNull(params["note"])
    }

    @Test
    fun `a stale response never reports the requested click id as live`() {
        val json = JSONObject("""{"click_id":null,"params":{},"stale":true}""")

        assertTrue(DeeplinklyNetwork.isStale(json))
        assertNull(DeeplinklyNetwork.extractParamsFromJson(json, "requested123")["click_id"])
    }

    @Test
    fun `a live response falls back to the requested click id`() {
        val json = JSONObject("""{"params":{"screen":"home"}}""")

        assertFalse(DeeplinklyNetwork.isStale(json))
        assertEquals(
            "requested123",
            DeeplinklyNetwork.extractParamsFromJson(json, "requested123")["click_id"]
        )
    }

    @Test
    fun `click id is read off the body when none was requested`() {
        val json = JSONObject("""{"click_id":"fromBody","params":{}}""")

        assertEquals("fromBody", DeeplinklyNetwork.extractParamsFromJson(json, null)["click_id"])
    }

    // --- fallbackPayload --------------------------------------------------

    @Test
    fun `fallback keeps the same envelope as a resolved link`() {
        val out = DeeplinklyNetwork.fallbackPayload(
            "abc123",
            mapOf(
                "click_id" to "abc123",
                "screen" to "microdramas",
                "utm_source" to "google",
                "empty" to null
            )
        )

        assertEquals("abc123", out["click_id"])
        @Suppress("UNCHECKED_CAST")
        val params = out["params"] as Map<String, Any?>
        assertEquals("microdramas", params["screen"])
        assertEquals("google", params["utm_source"])
        // click_id belongs to the envelope, not to the link's params.
        assertFalse(params.containsKey("click_id"))
        assertFalse(params.containsKey("empty"))
    }

    @Test
    fun `attribution still reads utm keys out of a fallback payload`() {
        val fallback = DeeplinklyNetwork.fallbackPayload(
            "abc123", mapOf("utm_source" to "google", "gclid" to "g1")
        )

        val snapshot = DeeplinklyNetwork.attributionSnapshot(
            fallback, source = "deep_link", fallbackClickId = "abc123"
        )

        assertEquals("abc123", snapshot["click_id"])
        assertEquals("google", snapshot["utm_source"])
        assertEquals("g1", snapshot["gclid"])
    }

    // --- attributionSnapshot (BUG-1 regression guard) ---------------------

    @Test
    fun `attribution is read out of the nested params map`() {
        val resolved = mapOf<String, Any?>(
            "click_id" to "abc123",
            "params" to mapOf("utm_source" to "google", "utm_campaign" to "spring", "gclid" to "g1")
        )

        val snapshot = DeeplinklyNetwork.attributionSnapshot(resolved, "deep_link")

        assertEquals("deep_link", snapshot["source"])
        assertEquals("abc123", snapshot["click_id"])
        assertEquals("google", snapshot["utm_source"])
        assertEquals("spring", snapshot["utm_campaign"])
        assertEquals("g1", snapshot["gclid"])
        assertNull(snapshot["utm_medium"])
    }

    @Test
    fun `attribution falls back to the requested click id`() {
        val snapshot = DeeplinklyNetwork.attributionSnapshot(
            mapOf<String, Any?>("params" to emptyMap<String, Any?>()),
            source = "install_referrer",
            fallbackClickId = "fallback123"
        )

        assertEquals("fallback123", snapshot["click_id"])
    }

    @Test
    fun `non-string param values are coerced rather than dropped`() {
        val resolved = mapOf<String, Any?>("params" to mapOf("utm_campaign" to 2026))

        assertEquals("2026", DeeplinklyNetwork.attributionSnapshot(resolved, "deep_link")["utm_campaign"])
    }
}
