package com.deeplinkly.flutter_deeplinkly.handlers

import android.content.Context
import android.content.Intent
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DomainConfig
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.util.EnrichmentUtils
import io.flutter.plugin.common.MethodChannel

object DeepLinkHandler {
    fun handleIntent(
        context: Context,
        intent: Intent?,
        channel: MethodChannel,
        apiKey: String
    ) {
        try {
            Logger.d("handleIntent with $intent")

            val data = intent?.data

            // Extract raw (local) params from the URI if present
            val localParams: Map<String, String?> = data?.let { uri ->
                buildMap<String, String?> {
                    for (name in uri.queryParameterNames) {
                        put(name, uri.getQueryParameter(name))
                    }
                }
            } ?: emptyMap()

            // SAFETY: data is nullable; use safe calls everywhere
            val clickId: String? = data?.getQueryParameter("click_id")
            val code: String? = data?.pathSegments?.firstOrNull()
            if (clickId == null && code == null) return

            // Collect enrichment (guarded)
            val enrichmentData = try {
                EnrichmentUtils.collect().toMutableMap()
            } catch (e: Exception) {
                NetworkUtils.reportError(
                    apiKey,
                    "collectEnrichmentData failed",
                    e.stackTraceToString(),
                    clickId
                )
                mutableMapOf()
            }
            enrichmentData["android_reported_at"] = System.currentTimeMillis().toString()
            clickId?.let { enrichmentData["click_id"] = it }
            if (clickId == null && code != null) enrichmentData["code"] = code

            val resolveUrl = if (clickId != null) {
                "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?click_id=$clickId"
            } else {
                "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?code=$code"
            }

            SdkRuntime.ioLaunch {
                try {
                    // Make sure your resolveClick returns Pair<Int, JSONObject> or similar
                    val (_, json) = NetworkUtils.resolveClick(resolveUrl, apiKey)

                    // Build a nullable String map from the JSON
                    val serverParams: Map<String, String?> = buildMap {
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            // optString with default null preserves nullability
                            put(k, json.optString(k, null))
                        }
                    }

                    val dartMap = NetworkUtils.extractParamsFromJson(json, clickId)

                    // Ensure enrichment has the click_id we actually resolved
                    (dartMap["click_id"] as? String)?.let { enrichmentData["click_id"] = it }

                    // Normalized attribution snapshot we want to persist (stable keys)
                    val normalized = linkedMapOf<String, String?>(
                        "source" to "deep_link",
                        "click_id" to ((dartMap["click_id"] as? String) ?: clickId),
                        "utm_source" to (dartMap["utm_source"] as? String),
                        "utm_medium" to (dartMap["utm_medium"] as? String),
                        "utm_campaign" to (dartMap["utm_campaign"] as? String),
                        "utm_term" to (dartMap["utm_term"] as? String),
                        "utm_content" to (dartMap["utm_content"] as? String),
                        "gclid" to (dartMap["gclid"] as? String),
                        "fbclid" to (dartMap["fbclid"] as? String),
                        "ttclid" to (dartMap["ttclid"] as? String)
                    )

                    // Persist once (normalized form). If you prefer merging local+server, you can
                    // also do: val finalAttribution = localParams.toMutableMap().apply { putAll(serverParams) }
                    AttributionStore.saveOnce(normalized)

                    // Notify Flutter
                    SdkRuntime.postToFlutter(channel, "onDeepLink", dartMap)

                    // Fire enrichment (respecting your "sendOnce" semantics)
                    com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
                        .sendOnce(context, enrichmentData, "deep_link", apiKey)

                } catch (e: Exception) {
                    // Fallback to whatever we got directly from the URI (all safe calls!)
                    val fallback = linkedMapOf<String, Any?>(
                        "click_id" to clickId,
                        "utm_source" to data?.getQueryParameter("utm_source"),
                        "utm_medium" to data?.getQueryParameter("utm_medium"),
                        "utm_campaign" to data?.getQueryParameter("utm_campaign"),
                        "utm_term" to data?.getQueryParameter("utm_term"),
                        "utm_content" to data?.getQueryParameter("utm_content"),
                        "gclid" to data?.getQueryParameter("gclid"),
                        "fbclid" to data?.getQueryParameter("fbclid"),
                        "ttclid" to data?.getQueryParameter("ttclid")
                    )

                    AttributionStore.saveOnce(fallback.mapValues { it.value as? String })
                    SdkRuntime.postToFlutter(channel, "onDeepLink", fallback)

                    NetworkUtils.reportError(
                        apiKey,
                        "resolve exception",
                        e.stackTraceToString(),
                        clickId
                    )
                }
            }
        } catch (e: Exception) {
            NetworkUtils.reportError(
                apiKey,
                "handleIntent outer crash",
                e.stackTraceToString()
            )
        }
    }
}
