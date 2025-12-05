package com.deeplinkly.flutter_deeplinkly.handlers

import android.app.Activity
import android.content.Context
import androidx.core.net.toUri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.network.DomainConfig
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

object InstallReferrerHandler {
    private const val KEY_REFERRER_HANDLED = "install_referrer_handled"
    private val isProcessing = AtomicBoolean(false)

    fun checkInstallReferrer(
        context: Context,
        activity: Activity,
        channel: MethodChannel?,
        apiKey: String
    ) {
        Logger.d("checkInstallReferrer()")

        val prefs = Prefs.of()
        // Use atomic check to prevent race conditions
        if (isProcessing.get() || prefs.getBoolean(KEY_REFERRER_HANDLED, false)) {
            Logger.d("Install referrer already handled or processing; skipping.")
            return
        }

        isProcessing.set(true)

        val referrerClient = InstallReferrerClient.newBuilder(context).build()
        referrerClient.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                try {
                    if (responseCode == InstallReferrerResponse.OK) {
                        val rawReferrer = referrerClient.installReferrer.installReferrer
                        val parsedReferrer = "https://dummy?$rawReferrer".toUri()
                        val clickId = parsedReferrer.getQueryParameter("click_id")

                        // Collect enrichment data (preserve in error paths)
                        val enrichmentData = try {
                            DeeplinklyUtils.collectEnrichment().toMutableMap()
                        } catch (e: Exception) {
                            NetworkUtils.reportError(apiKey, "collectEnrichmentData failed", e.stackTraceToString(), clickId)
                            mutableMapOf()
                        }

                        enrichmentData["install_referrer"] = rawReferrer
                        enrichmentData["android_reported_at"] = System.currentTimeMillis().toString()
                        clickId?.let { enrichmentData["click_id"] = it }
                        enrichmentData["utm_source"] = parsedReferrer.getQueryParameter("utm_source")
                        enrichmentData["utm_medium"] = parsedReferrer.getQueryParameter("utm_medium")
                        enrichmentData["utm_campaign"] = parsedReferrer.getQueryParameter("utm_campaign")
                        enrichmentData["utm_term"] = parsedReferrer.getQueryParameter("utm_term")
                        enrichmentData["utm_content"] = parsedReferrer.getQueryParameter("utm_content")
                        enrichmentData["gclid"] = parsedReferrer.getQueryParameter("gclid")
                        enrichmentData["fbclid"] = parsedReferrer.getQueryParameter("fbclid")
                        enrichmentData["ttclid"] = parsedReferrer.getQueryParameter("ttclid")

                        val localParams = mapOf<String, String?>(
                            "utm_source" to parsedReferrer.getQueryParameter("utm_source"),
                            "utm_medium" to parsedReferrer.getQueryParameter("utm_medium"),
                            "utm_campaign" to parsedReferrer.getQueryParameter("utm_campaign"),
                            "utm_term" to parsedReferrer.getQueryParameter("utm_term"),
                            "utm_content" to parsedReferrer.getQueryParameter("utm_content"),
                            "gclid" to parsedReferrer.getQueryParameter("gclid"),
                            "fbclid" to parsedReferrer.getQueryParameter("fbclid"),
                            "ttclid" to parsedReferrer.getQueryParameter("ttclid")
                        )

                        val initialAttribution = linkedMapOf<String, String?>(
                            "source" to "install_referrer",
                            "install_referrer" to rawReferrer,
                            "utm_source" to parsedReferrer.getQueryParameter("utm_source"),
                            "utm_medium" to parsedReferrer.getQueryParameter("utm_medium"),
                            "utm_campaign" to parsedReferrer.getQueryParameter("utm_campaign"),
                            "utm_term" to parsedReferrer.getQueryParameter("utm_term"),
                            "utm_content" to parsedReferrer.getQueryParameter("utm_content"),
                            "gclid" to parsedReferrer.getQueryParameter("gclid"),
                            "fbclid" to parsedReferrer.getQueryParameter("fbclid"),
                            "ttclid" to parsedReferrer.getQueryParameter("ttclid"),
                            "click_id" to clickId
                        )
                        AttributionStore.saveOnce(initialAttribution)

                        if (clickId != null && clickId.isNotEmpty()) {
                            // Queue for resolution (with retry)
                            val pendingResolve = DeepLinkQueue.PendingResolve(
                                clickId = clickId,
                                code = null,
                                uri = null,
                                localParams = localParams,
                                enrichmentData = enrichmentData
                            )
                            DeepLinkQueue.enqueueResolve(pendingResolve)

                            SdkRuntime.ioLaunch {
                                try {
                                    val (_, json) = NetworkUtils.resolveClickWithRetry(
                                        "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?click_id=$clickId", 
                                        apiKey,
                                        maxRetries = 2,
                                        initialDelayMs = 50
                                    )
                                    val dartMap = NetworkUtils.extractParamsFromJson(json, clickId)
                                    
                                    // Update enrichment with resolved click_id
                                    (dartMap["click_id"] as? String)?.let { enrichmentData["click_id"] = it }
                                    
                                    // Create delivery item
                                    val delivery = DeepLinkQueue.PendingDelivery(
                                        resolvedData = dartMap,
                                        enrichmentData = enrichmentData,
                                        source = "install_referrer"
                                    )
                                    
                                    // Always enqueue for reliability
                                    DeepLinkQueue.enqueueDelivery(delivery)
                                    
                                    // Try immediate delivery if Flutter is ready
                                    // If successful, remove from queue to prevent QueueProcessor from sending again
                                    if (channel != null && SdkRuntime.isFlutterReady()) {
                                        SdkRuntime.postToFlutter(channel, "onDeepLink", dartMap)
                                        // Remove from queue after a short delay to allow postToFlutter to execute
                                        val resolvedClickId = (dartMap["click_id"] as? String) ?: clickId
                                        resolvedClickId?.let { clickIdToRemove ->
                                            // Use a small delay to ensure postToFlutter has executed
                                            SdkRuntime.ioLaunch {
                                                kotlinx.coroutines.delay(100) // 100ms delay
                                                DeepLinkQueue.removeDeliveryByClickId(clickIdToRemove, "install_referrer")
                                            }
                                        }
                                    }
                                    
                                    // Mark as handled (atomic)
                                    if (!prefs.getBoolean(KEY_REFERRER_HANDLED, false)) {
                                        prefs.edit().putBoolean(KEY_REFERRER_HANDLED, true).apply()
                                    }
                                    
                                    // Remove from resolve queue (success)
                                    DeepLinkQueue.removeResolve(pendingResolve)
                                    
                                    com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
                                        .sendOnce(context, enrichmentData, "install_referrer", apiKey)
                                } catch (e: Exception) {
                                    Logger.e("installReferrer resolve error", e)
                                    NetworkUtils.reportError(apiKey, "installReferrer resolve error", e.stackTraceToString(), clickId)
                                    // Keep in queue for retry
                                }
                            }
                        } else {
                            Logger.d("No click_id in install referrer; marking as handled")
                            prefs.edit().putBoolean(KEY_REFERRER_HANDLED, true).apply()
                        }
                    } else {
                        Logger.w("InstallReferrer: code=$responseCode")
                    }
                } finally {
                    isProcessing.set(false)
                    try { referrerClient.endConnection() } catch (e: Exception) { 
                        Logger.e("Error closing referrer client", e) 
                    }
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                Logger.w("InstallReferrerServiceDisconnected()")
                isProcessing.set(false)
            }
        })
    }
}
