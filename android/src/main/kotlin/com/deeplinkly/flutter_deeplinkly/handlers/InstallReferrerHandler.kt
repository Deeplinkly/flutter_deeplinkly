package com.deeplinkly.flutter_deeplinkly.handlers

import android.app.Activity
import android.content.Context
import androidx.core.net.toUri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.network.DomainConfig
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.util.EnrichmentUtils
import io.flutter.plugin.common.MethodChannel

object InstallReferrerHandler {
    private const val KEY_REFERRER_HANDLED = "install_referrer_handled"

    fun checkInstallReferrer(
        context: Context,
        activity: Activity,
        channel: MethodChannel,
        apiKey: String
    ) {
        Logger.d("checkInstallReferrer()")

        val prefs = com.deeplinkly.flutter_deeplinkly.core.Prefs.of()
        if (prefs.getBoolean(KEY_REFERRER_HANDLED, false)) {
            Logger.d("Install referrer already handled; skipping.")
            return
        }

        val referrerClient = InstallReferrerClient.newBuilder(context).build()
        referrerClient.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                try {
                    if (responseCode == InstallReferrerResponse.OK) {
                        val rawReferrer = referrerClient.installReferrer.installReferrer
                        val parsedReferrer = "https://dummy?$rawReferrer".toUri()
                        val clickId = parsedReferrer.getQueryParameter("click_id")

                        val enrichmentData = try {
                            EnrichmentUtils.collect().toMutableMap()
                        } catch (e: Exception) {
                            NetworkUtils.reportError(apiKey, "collectEnrichmentData failed", e.stackTraceToString(), clickId)
                            mutableMapOf()
                        }

                        enrichmentData["install_referrer"] = rawReferrer
                        enrichmentData["android_reported_at"] = System.currentTimeMillis().toString()
                        enrichmentData["click_id"] = clickId
                        enrichmentData["utm_source"] = parsedReferrer.getQueryParameter("utm_source")
                        enrichmentData["utm_medium"] = parsedReferrer.getQueryParameter("utm_medium")
                        enrichmentData["utm_campaign"] = parsedReferrer.getQueryParameter("utm_campaign")
                        enrichmentData["utm_term"] = parsedReferrer.getQueryParameter("utm_term")
                        enrichmentData["utm_content"] = parsedReferrer.getQueryParameter("utm_content")
                        enrichmentData["gclid"] = parsedReferrer.getQueryParameter("gclid")
                        enrichmentData["fbclid"] = parsedReferrer.getQueryParameter("fbclid")
                        enrichmentData["ttclid"] = parsedReferrer.getQueryParameter("ttclid")

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

                        if (!clickId.isNullOrEmpty()) {
                            SdkRuntime.ioLaunch {
                                try {
                                    val (_, json) = NetworkUtils.resolveClick(
                                        "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?click_id=$clickId", apiKey
                                    )
                                    val dartMap = NetworkUtils.extractParamsFromJson(json, clickId)
                                    if (!prefs.getBoolean(KEY_REFERRER_HANDLED, false)) {
                                        SdkRuntime.postToFlutter(channel, "onDeepLink", dartMap)
                                        prefs.edit().putBoolean(KEY_REFERRER_HANDLED, true).apply()
                                    }
                                    com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
                                        .sendOnce(context, enrichmentData, "install_referrer", apiKey)
                                } catch (e: Exception) {
                                    NetworkUtils.reportError(apiKey, "installReferrer resolve error", e.stackTraceToString(), clickId)
                                }
                            }
                        } else {
                            Logger.d("No click_id in install referrer; skipping resolveClick")
                        }
                    } else {
                        Logger.w("InstallReferrer: code=$responseCode")
                    }
                } finally {
                    try { referrerClient.endConnection() } catch (e: Exception) { Logger.e("Error closing referrer client", e) }
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                Logger.w("InstallReferrerServiceDisconnected()")
            }
        })
    }
}
