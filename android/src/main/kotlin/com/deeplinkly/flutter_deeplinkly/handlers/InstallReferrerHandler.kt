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
import com.deeplinkly.flutter_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.flutter_deeplinkly.network.isTerminalHttp
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.queue.DeepLinkQueue
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

object InstallReferrerHandler {
    private const val KEY_REFERRER_HANDLED = "install_referrer_handled"

    /** The mechanism this handler reports. Outranks every other claim. */
    const val SOURCE = "install_referrer"

    private val isProcessing = AtomicBoolean(false)

    fun checkInstallReferrer(
        context: Context,
        activity: Activity,
        channel: MethodChannel?,
        apiKey: String
    ) {
        Logger.d("checkInstallReferrer()")

        if (Prefs.of().getBoolean(KEY_REFERRER_HANDLED, false)) {
            Logger.d("Install referrer already handled; skipping.")
            return
        }

        // One atomic claim, not a get() followed by a set(). Two attaches
        // landing together - which happens when an activity is recreated -
        // could both pass the read before either wrote, and each open its own
        // referrer client.
        if (!isProcessing.compareAndSet(false, true)) {
            Logger.d("Install referrer already being processed; skipping.")
            return
        }

        val prefs = Prefs.of()
        val referrerClient = InstallReferrerClient.newBuilder(context).build()
        referrerClient.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                // This runs on the Play library's binder callback thread, and
                // installReferrer throws RemoteException/IllegalStateException
                // when the service dies mid-call. There was no catch here at
                // all, only a finally - so that exception unwound into Google's
                // callback rather than being handled.
                try {
                    if (responseCode == InstallReferrerResponse.OK) {
                        val details = referrerClient.installReferrer
                        val rawReferrer = details.installReferrer
                        val parsedReferrer = "https://dummy?$rawReferrer".toUri()
                        val clickId = parsedReferrer.getQueryParameter("click_id")

                        // The click-to-install timings ride on the same
                        // ReferrerDetails we already had in hand, and give the
                        // backend click→install latency for free. Latched into
                        // the static profile rather than sent from here, since
                        // they describe the install and never change.
                        InstallReferrerTimings.record(details)

                        // Link identity only. The device description is
                        // assembled by EnrichmentSender at send time.
                        val attributionData = mutableMapOf<String, String?>()
                        attributionData["install_referrer"] = rawReferrer
                        // The catalogued key; see DeepLinkHandler for why this
                        // is not "event_at".
                        attributionData["android_reported_at"] =
                            System.currentTimeMillis().toString()
                        clickId?.let { attributionData["click_id"] = it }
                        attributionData["source"] = SOURCE
                        attributionData["utm_source"] = parsedReferrer.getQueryParameter("utm_source")
                        attributionData["utm_medium"] = parsedReferrer.getQueryParameter("utm_medium")
                        attributionData["utm_campaign"] = parsedReferrer.getQueryParameter("utm_campaign")
                        attributionData["utm_term"] = parsedReferrer.getQueryParameter("utm_term")
                        attributionData["utm_content"] = parsedReferrer.getQueryParameter("utm_content")
                        attributionData["gclid"] = parsedReferrer.getQueryParameter("gclid")
                        attributionData["fbclid"] = parsedReferrer.getQueryParameter("fbclid")
                        attributionData["ttclid"] = parsedReferrer.getQueryParameter("ttclid")

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
                            "source" to SOURCE,
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
                                attributionData = attributionData,
                                source = SOURCE
                            )
                            DeepLinkQueue.enqueueResolve(pendingResolve)

                            SdkRuntime.ioLaunch {
                                // Claimed so the periodic processor leaves this
                                // entry alone while our own request is out. It
                                // is enqueued above before the resolve starts,
                                // so a tick landing inside a slow first-launch
                                // resolve used to resolve the same click in
                                // parallel and deliver the deferred link twice.
                                // A failed claim means the processor already
                                // owns it, and it will deliver.
                                if (!DeepLinkQueue.claimResolve(pendingResolve)) {
                                    Logger.d("Install referrer resolve already in flight; leaving it to the queue processor")
                                    return@ioLaunch
                                }
                                try {
                                    val (_, json) = DeeplinklyNetwork.resolveClickWithRetry(
                                        DeeplinklyNetwork.resolveUrl(clickId, null, localParams),
                                        apiKey,
                                        maxRetries = 2,
                                        initialDelayMs = 50
                                    )
                                    // An unknown click id comes back 200 with
                                    // stale: true. The referrer is read once per
                                    // install, so mark it handled rather than
                                    // re-resolving an id the backend disowns.
                                    if (DeeplinklyNetwork.isStale(json)) {
                                        Logger.w("Install referrer resolve returned a stale click; suppressing delivery.")
                                        prefs.edit().putBoolean(KEY_REFERRER_HANDLED, true).apply()
                                        DeepLinkQueue.removeResolve(pendingResolve)
                                        return@ioLaunch
                                    }

                                    val dartMap = DeeplinklyNetwork.extractParamsFromJson(json, clickId)

                                    // Update enrichment with resolved click_id
                                    (dartMap["click_id"] as? String)?.let { attributionData["click_id"] = it }
                                    
                                    // Create delivery item
                                    val delivery = DeepLinkQueue.PendingDelivery(
                                        resolvedData = dartMap,
                                        attributionData = attributionData,
                                        source = SOURCE
                                    )
                                    
                                    // Always enqueue for reliability
                                    DeepLinkQueue.enqueueDelivery(delivery)

                                    if (channel != null) {
                                        SdkRuntime.deliverDeepLink(delivery)
                                    }

                                    // Mark as handled (atomic)
                                    if (!prefs.getBoolean(KEY_REFERRER_HANDLED, false)) {
                                        prefs.edit().putBoolean(KEY_REFERRER_HANDLED, true).apply()
                                    }
                                    
                                    // Remove from resolve queue (success)
                                    DeepLinkQueue.removeResolve(pendingResolve)
                                    
                                    com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
                                        .sendOnce(attributionData, SOURCE, apiKey)
                                } catch (e: Exception) {
                                    Logger.e("installReferrer resolve error", e)
                                    DeeplinklyNetwork.reportError(apiKey, "installReferrer resolve error", e.stackTraceToString(), clickId)

                                    // A revoked key or suspended account answers
                                    // the same way every time, and the referrer
                                    // stays readable for the life of the install
                                    // - so without this the SDK re-read it and
                                    // re-sent the same doomed resolve on every
                                    // cold start, forever. A transient failure
                                    // still leaves it unhandled, to be retried.
                                    if (e.isTerminalHttp()) {
                                        Logger.w("Install referrer resolve rejected (terminal); not retrying")
                                        prefs.edit().putBoolean(KEY_REFERRER_HANDLED, true).apply()
                                        DeepLinkQueue.removeResolve(pendingResolve)
                                    }
                                    // Otherwise keep in queue for retry
                                } finally {
                                    DeepLinkQueue.releaseResolve(pendingResolve)
                                }
                            }
                        } else {
                            Logger.d("No click_id in install referrer; marking as handled")
                            prefs.edit().putBoolean(KEY_REFERRER_HANDLED, true).apply()
                        }
                    } else {
                        Logger.w("InstallReferrer: code=$responseCode")
                    }
                } catch (e: Exception) {
                    // Left unhandled on purpose: the referrer stays readable for
                    // the life of the install, so a transient service failure is
                    // retried on the next launch.
                    Logger.e("Install referrer read failed", e)
                    DeeplinklyNetwork.reportError(
                        apiKey, "installReferrer read failed", e.stackTraceToString()
                    )
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
