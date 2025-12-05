package com.deeplinkly.flutter_deeplinkly.handlers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.net.toUri
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DomainConfig
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue
import io.flutter.plugin.common.MethodChannel

object ClipboardHandler {
    private const val KEY_CLIPBOARD_HANDLED = "clipboard_handled"
    
    fun checkClipboard(channel: MethodChannel?, apiKey: String) {
        try {
            val context = DeeplinklyContext.app
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return
            
            // Check if clipboard was already handled
            val prefs = com.deeplinkly.flutter_deeplinkly.core.Prefs.of()
            if (prefs.getBoolean(KEY_CLIPBOARD_HANDLED, false)) {
                Logger.d("Clipboard already handled; skipping.")
                return
            }
            
            // Access clipboard on main thread for Android 10+
            SdkRuntime.mainHandler.post {
                try {
                    val clipData = clipboard.primaryClip
                    val text = clipData?.getItemAt(0)?.text?.toString() ?: return@post
                    
                    // Check if text looks like a deep link URL
                    if (!text.startsWith("http://") && !text.startsWith("https://")) {
                        return@post
                    }
                    
                    val uri = text.toUri()
                    val clickId = uri.getQueryParameter("click_id")
                    val code = uri.pathSegments?.firstOrNull()
                    
                    if (clickId == null && code == null) {
                        Logger.d("No click_id or code in clipboard, skipping")
                        return@post
                    }
                    
                    // Mark as handled
                    prefs.edit().putBoolean(KEY_CLIPBOARD_HANDLED, true).apply()
                    
                    // Collect enrichment data
                    val enrichmentData = try {
                        DeeplinklyUtils.collectEnrichment().toMutableMap()
                    } catch (e: Exception) {
                        NetworkUtils.reportError(apiKey, "collectEnrichmentData failed in clipboard", e.stackTraceToString(), clickId)
                        mutableMapOf()
                    }
                    enrichmentData["android_reported_at"] = System.currentTimeMillis().toString()
                    clickId?.let { enrichmentData["click_id"] = it }
                    if (clickId == null && code != null) enrichmentData["code"] = code
                    enrichmentData["source"] = "clipboard"
                    
                    // Extract local params
                    val localParams: Map<String, String?> = buildMap {
                        for (name in uri.queryParameterNames) {
                            put(name, uri.getQueryParameter(name))
                        }
                    }
                    
                    // Queue for resolution
                    val pendingResolve = DeepLinkQueue.PendingResolve(
                        clickId = clickId,
                        code = code,
                        uri = text,
                        localParams = localParams,
                        enrichmentData = enrichmentData
                    )
                    DeepLinkQueue.enqueueResolve(pendingResolve)
                    
                    // Process in background
                    SdkRuntime.ioLaunch {
                        try {
                            val resolveUrl = if (clickId != null) {
                                "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?click_id=$clickId"
                            } else {
                                "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?code=$code"
                            }
                            
                            val (_, json) = NetworkUtils.resolveClickWithRetry(resolveUrl, apiKey, maxRetries = 2, initialDelayMs = 50)
                            val dartMap = NetworkUtils.extractParamsFromJson(json, clickId)
                            
                            // Update enrichment with resolved click_id
                            (dartMap["click_id"] as? String)?.let { enrichmentData["click_id"] = it }
                            
                            // Save attribution
                            val normalized = linkedMapOf<String, String?>(
                                "source" to "clipboard",
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
                            AttributionStore.saveOnce(normalized)
                            
                            // Create delivery item
                            val delivery = DeepLinkQueue.PendingDelivery(
                                resolvedData = dartMap,
                                enrichmentData = enrichmentData,
                                source = "clipboard"
                            )
                            DeepLinkQueue.enqueueDelivery(delivery)
                            
                            // Try immediate delivery if Flutter is ready
                            if (channel != null && SdkRuntime.isFlutterReady()) {
                                SdkRuntime.postToFlutter(channel, "onDeepLink", dartMap)
                                val resolvedClickId = (dartMap["click_id"] as? String) ?: clickId
                                resolvedClickId?.let { clickIdToRemove ->
                                    SdkRuntime.ioLaunch {
                                        kotlinx.coroutines.delay(100)
                                        DeepLinkQueue.removeDeliveryByClickId(clickIdToRemove, "clipboard")
                                    }
                                }
                            }
                            
                            // Remove from resolve queue
                            DeepLinkQueue.removeResolve(pendingResolve)
                            
                            // Send enrichment
                            com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
                                .sendOnce(context, enrichmentData, "clipboard", apiKey)
                                
                            Logger.d("Successfully processed clipboard deep link: clickId=$clickId, code=$code")
                        } catch (e: Exception) {
                            Logger.e("Clipboard resolve failed, using fallback", e)
                            
                            // Fallback: use local params
                            val fallback = linkedMapOf<String, Any?>().apply {
                                put("click_id", clickId)
                                localParams.forEach { (key, value) ->
                                    if (value != null) put(key, value)
                                }
                            }
                            
                            AttributionStore.saveOnce(fallback.mapValues { it.value as? String })
                            
                            val fallbackDelivery = DeepLinkQueue.PendingDelivery(
                                resolvedData = fallback,
                                enrichmentData = enrichmentData,
                                source = "clipboard_fallback"
                            )
                            DeepLinkQueue.enqueueDelivery(fallbackDelivery)
                            
                            if (channel != null && SdkRuntime.isFlutterReady()) {
                                SdkRuntime.postToFlutter(channel, "onDeepLink", fallback)
                            }
                            
                            // Keep in queue for retry
                            NetworkUtils.reportError(apiKey, "clipboard resolve exception", e.stackTraceToString(), clickId)
                        }
                    }
                    
                    // Clear clipboard after processing (optional, can be removed if needed)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        } catch (e: Exception) {
                            Logger.e("Failed to clear clipboard", e)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("Clipboard check failed", e)
                    NetworkUtils.reportError(apiKey, "clipboard check exception", e.stackTraceToString())
                }
            }
        } catch (e: Exception) {
            Logger.e("ClipboardHandler outer crash", e)
            NetworkUtils.reportError(apiKey, "ClipboardHandler outer crash", e.stackTraceToString())
        }
    }
}

