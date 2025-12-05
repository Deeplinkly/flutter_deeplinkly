package com.deeplinkly.flutter_deeplinkly.handlers

import android.content.Context
import android.content.Intent
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DomainConfig
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue
import io.flutter.plugin.common.MethodChannel

object DeepLinkHandler {
    fun handleIntent(
        context: Context,
        intent: Intent?,
        channel: MethodChannel?,
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
            if (clickId == null && code == null) {
                Logger.d("No click_id or code in intent, skipping")
                return
            }

            // Collect enrichment (guarded) - CRITICAL: Preserve this data
            val enrichmentData = try {
                DeeplinklyUtils.collectEnrichment().toMutableMap()
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

            // Queue for resolution if not immediately resolvable
            val pendingResolve = DeepLinkQueue.PendingResolve(
                clickId = clickId,
                code = code,
                uri = data?.toString(),
                localParams = localParams,
                enrichmentData = enrichmentData
            )

            SdkRuntime.ioLaunch {
                try {
                    val resolveUrl = if (clickId != null) {
                        "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?click_id=$clickId"
                    } else {
                        "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?code=$code"
                    }

                    // Try immediate resolution with fast retry
                    val (_, json) = try {
                        NetworkUtils.resolveClickWithRetry(resolveUrl, apiKey, maxRetries = 2, initialDelayMs = 50)
                    } catch (e: Exception) {
                        // If immediate resolution fails, queue for retry
                        Logger.w("Immediate resolve failed, queueing for retry: ${e.message}")
                        DeepLinkQueue.enqueueResolve(pendingResolve)
                        throw e
                    }

                    // Build a nullable String map from the JSON
                    val serverParams: Map<String, String?> = buildMap {
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
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

                    // Persist attribution
                    AttributionStore.saveOnce(normalized)

                    // Create delivery item
                    val delivery = DeepLinkQueue.PendingDelivery(
                        resolvedData = dartMap,
                        enrichmentData = enrichmentData,
                        source = "deep_link"
                    )

                    // Always enqueue for reliability (handles Flutter not ready, app restart, etc.)
                    DeepLinkQueue.enqueueDelivery(delivery)

                    // Try immediate delivery if Flutter is ready
                    // If successful, remove from queue to prevent QueueProcessor from sending again
                    if (channel != null && SdkRuntime.isFlutterReady()) {
                        SdkRuntime.postToFlutter(channel, "onDeepLink", dartMap)
                        // Remove from queue after a short delay to allow postToFlutter to execute
                        // This prevents QueueProcessor from sending it again
                        val resolvedClickId = (dartMap["click_id"] as? String) ?: clickId
                        resolvedClickId?.let { clickIdToRemove ->
                            // Use a small delay to ensure postToFlutter has executed
                            SdkRuntime.ioLaunch {
                                kotlinx.coroutines.delay(100) // 100ms delay
                                DeepLinkQueue.removeDeliveryByClickId(clickIdToRemove, "deep_link")
                            }
                        }
                    }

                    // Fire enrichment (respecting your "sendOnce" semantics)
                    com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
                        .sendOnce(context, enrichmentData, "deep_link", apiKey)

                    Logger.d("Successfully processed deep link: clickId=$clickId, code=$code")

                } catch (e: Exception) {
                    // CRITICAL: Preserve all data in error path
                    Logger.e("Resolve failed, using fallback with preserved data", e)
                    
                    // Merge local params with any available enrichment data
                    val fallback = linkedMapOf<String, Any?>().apply {
                        put("click_id", clickId)
                        // Add all local params
                        localParams.forEach { (key, value) ->
                            if (value != null) put(key, value)
                        }
                        // Preserve enrichment data that might be useful
                        enrichmentData["android_reported_at"]?.let { put("android_reported_at", it) }
                    }

                    // Save attribution with fallback data
                    AttributionStore.saveOnce(fallback.mapValues { it.value as? String })

                    // Create delivery item
                    val fallbackDelivery = DeepLinkQueue.PendingDelivery(
                        resolvedData = fallback,
                        enrichmentData = enrichmentData, // Preserve enrichment data
                        source = "deep_link_fallback"
                    )

                    // Always enqueue for reliability
                    DeepLinkQueue.enqueueDelivery(fallbackDelivery)

                    // Try immediate delivery if Flutter is ready
                    // If successful, remove from queue to prevent QueueProcessor from sending again
                    if (channel != null && SdkRuntime.isFlutterReady()) {
                        SdkRuntime.postToFlutter(channel, "onDeepLink", fallback)
                        // Remove from queue after a short delay to allow postToFlutter to execute
                        clickId?.let { clickIdToRemove ->
                            // Use a small delay to ensure postToFlutter has executed
                            SdkRuntime.ioLaunch {
                                kotlinx.coroutines.delay(100) // 100ms delay
                                DeepLinkQueue.removeDeliveryByClickId(clickIdToRemove, "deep_link_fallback")
                            }
                        }
                    }

                    // Ensure it's queued for retry
                    DeepLinkQueue.enqueueResolve(pendingResolve)

                    NetworkUtils.reportError(
                        apiKey,
                        "resolve exception",
                        e.stackTraceToString(),
                        clickId
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e("handleIntent outer crash", e)
            NetworkUtils.reportError(
                apiKey,
                "handleIntent outer crash",
                e.stackTraceToString()
            )
        }
    }
}
