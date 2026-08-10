package com.deeplinkly.flutter_deeplinkly.handlers

import android.content.Context
import android.content.Intent
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DomainConfig
import com.deeplinkly.flutter_deeplinkly.network.DeeplinklyNetwork
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
            // Priority 1: Try Intent extras first (from #Intent; section)
            // Priority 2: Fall back to URI query parameters
            val clickId: String? = intent?.getStringExtra("click_id")
                ?: data?.getQueryParameter("click_id")
            val code: String? = data?.pathSegments?.firstOrNull()
            if (clickId == null && code == null) {
                Logger.d("No click_id or code in intent, skipping")
                return
            }

            // Collect enrichment (guarded) - CRITICAL: Preserve this data
            val enrichmentData = try {
                DeeplinklyUtils.collectEnrichment().toMutableMap()
            } catch (e: Exception) {
                DeeplinklyNetwork.reportError(
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
                    // Build fingerprint subset for matching (excludes device IDs and app info)
                    val fingerprintKeys = setOf(
                        "platform", "os_version", "screen_width", "screen_height",
                        "pixel_ratio", "hardware_concurrency", "locale", "language",
                        "timezone", "manufacturer", "brand", "device_model"
                    )
                    val fingerprint: Map<String, Any?> = enrichmentData
                        .filterKeys { it in fingerprintKeys }

                    // Try immediate resolution with fast retry
                    val (_, json) = try {
                        DeeplinklyNetwork.resolveWithFingerprintWithRetry(
                            clickId, code, fingerprint, apiKey, maxRetries = 2, initialDelayMs = 50
                        )
                    } catch (e: Exception) {
                        // If immediate resolution fails, queue for retry
                        Logger.w("Immediate resolve failed, queueing for retry: ${e.message}")
                        DeepLinkQueue.enqueueResolve(pendingResolve)
                        throw e
                    }

                    // An unknown click id comes back 200 with stale: true.
                    // Delivering it would fire a deep link carrying no params at
                    // all, on every cold start until the cached id was cleared.
                    if (DeeplinklyNetwork.isStale(json)) {
                        Logger.w("Resolve returned a stale click; suppressing delivery.")
                        DeepLinkQueue.removeResolve(pendingResolve)
                        return@ioLaunch
                    }

                    val dartMap = DeeplinklyNetwork.extractParamsFromJson(json, clickId)

                    // Ensure enrichment has the click_id we actually resolved
                    (dartMap["click_id"] as? String)?.let { enrichmentData["click_id"] = it }

                    // Normalized attribution snapshot we want to persist (stable keys)
                    val normalized = DeeplinklyNetwork.attributionSnapshot(
                        dartMap, source = "deep_link", fallbackClickId = clickId
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
                    
                    // Same {click_id, params} envelope as a resolved link, so Dart
                    // reads an unresolved deep link exactly like a resolved one.
                    val fallback = DeeplinklyNetwork.fallbackPayload(clickId, localParams)

                    // Save attribution with fallback data
                    AttributionStore.saveOnce(
                        DeeplinklyNetwork.attributionSnapshot(
                            fallback, source = "deep_link", fallbackClickId = clickId
                        )
                    )

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

                    DeeplinklyNetwork.reportError(
                        apiKey,
                        "resolve exception",
                        e.stackTraceToString(),
                        clickId
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e("handleIntent outer crash", e)
            DeeplinklyNetwork.reportError(
                apiKey,
                "handleIntent outer crash",
                e.stackTraceToString()
            )
        }
    }
}
