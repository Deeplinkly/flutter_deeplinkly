// FILE: com/deeplinkly/flutter_deeplinkly/session/DeeplinklySession.kt
package com.deeplinkly.flutter_deeplinkly.session

import android.app.Activity
import android.content.Intent
import androidx.core.net.toUri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyCore
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import kotlinx.coroutines.delay

class DeeplinklySession(
    private val apiKey: String,
    private val channel: io.flutter.plugin.common.MethodChannel
) {
    private var flutterReady = false
    private val pendingEmits = mutableListOf<Map<String, Any?>>()

    fun markFlutterReady() {
        flutterReady = true
        if (pendingEmits.isNotEmpty()) {
            pendingEmits.forEach { payload ->
                DeeplinklyCore.mainHandler.post {
                    channel.invokeMethod("onDeepLink", payload)
                }
            }
            pendingEmits.clear()
        }
    }

    fun onActivityStarted(activity: Activity, intent: Intent?) {
        handleIntent(intent)
        checkInstallReferrer(activity)
    }

    fun onNewIntent(intent: Intent?) {
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        try {
            val data = intent?.data ?: return
            val clickId = data.getQueryParameter("click_id")
            val code = data.pathSegments?.firstOrNull()
            if (clickId == null && code == null) return

            val enrichment = DeeplinklyUtils.collectEnrichment().toMutableMap().apply {
                put("android_reported_at", System.currentTimeMillis().toString())
                clickId?.let { put("click_id", it) }
                if (clickId == null && code != null) put("code", code)
            }

            val resolveUrl =
                if (clickId != null)
                    "${DeeplinklyNetwork.RESOLVE_CLICK_ENDPOINT}?click_id=$clickId"
                else
                    "${DeeplinklyNetwork.RESOLVE_CLICK_ENDPOINT}?code=$code"

            DeeplinklyCore.ioLaunch {
                try {
                    val (_, json) = DeeplinklyNetwork.resolveClick(resolveUrl, apiKey)
                    val dartMap = DeeplinklyNetwork.extractParamsFromJson(json, clickId)

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
                    AttributionStore.saveOnce(normalized)

                    // ✅ Emit only the params JSON (not click_id or other keys)
                    val paramsString = dartMap["params"] as? String
                    if (!paramsString.isNullOrBlank() && paramsString.trim().startsWith("{")) {
                        try {
                            val paramsJson = org.json.JSONObject(paramsString)
                            val paramsMap = mutableMapOf<String, Any?>()
                            paramsJson.keys().forEach { key ->
                                paramsMap[key] = paramsJson.opt(key)
                            }
                            DeeplinklyCore.d("emitToFlutter(deep_link) params-only: $paramsMap")
                            emitToFlutter(paramsMap)
                        } catch (e: Exception) {
                            DeeplinklyCore.w("Failed to parse params JSON: ${e.message}")
                        }
                    } else {
                        DeeplinklyCore.w("No params found, skipping emit")
                    }

                    runCatching {
                        EnrichmentSender.sendOnce(DeeplinklyContext.app, enrichment, "deep_link", apiKey)
                    }
                } catch (e: Exception) {
                    DeeplinklyCore.w("resolveClick failed: ${e.message}")
                    val fallback = linkedMapOf<String, Any?>(
                        "utm_source" to data.getQueryParameter("utm_source"),
                        "utm_medium" to data.getQueryParameter("utm_medium"),
                        "utm_campaign" to data.getQueryParameter("utm_campaign"),
                        "utm_term" to data.getQueryParameter("utm_term"),
                        "utm_content" to data.getQueryParameter("utm_content"),
                        "gclid" to data.getQueryParameter("gclid"),
                        "fbclid" to data.getQueryParameter("fbclid"),
                        "ttclid" to data.getQueryParameter("ttclid")
                    )
                    AttributionStore.saveOnce(fallback.mapValues { it.value as? String })
                    DeeplinklyCore.d("emitToFlutter(deep_link) fallback: ${fallback}")
                    emitToFlutter(fallback)
                    DeeplinklyNetwork.reportError(apiKey, "resolve exception", e.stackTraceToString(), clickId)
                }
            }
        } catch (e: Exception) {
            DeeplinklyCore.ioLaunch {
                DeeplinklyNetwork.reportError(apiKey, "handleIntent outer crash", e.stackTraceToString())
            }
        }
    }

    private val KEY_REFERRER_LAST_CHECK_AT = "dl_referrer_last_check_at"
    private val REFERRER_MIN_INTERVAL_MS = 60_000L
    private val REFERRER_RETRY_DELAY_MS = 3_000L

    private fun shouldThrottle(): Boolean {
        val prefs = DeeplinklyContext.app.getSharedPreferences("deeplinkly_prefs", 0)
        val last = prefs.getLong(KEY_REFERRER_LAST_CHECK_AT, 0L)
        val now = System.currentTimeMillis()
        return (now - last) < REFERRER_MIN_INTERVAL_MS
    }

    private fun markChecked() {
        DeeplinklyContext.app
            .getSharedPreferences("deeplinkly_prefs", 0)
            .edit()
            .putLong(KEY_REFERRER_LAST_CHECK_AT, System.currentTimeMillis())
            .apply()
    }

    private fun checkInstallReferrer(activity: Activity, isRetry: Boolean = false) {
        if (shouldThrottle()) {
            DeeplinklyCore.d("InstallReferrer throttled; will try later")
            return
        }
        markChecked()

        val context = activity.applicationContext
        val client = InstallReferrerClient.newBuilder(context).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                try {
                    if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) {
                        DeeplinklyCore.w("InstallReferrer code=$responseCode")
                        return
                    }

                    val rawReferrer = runCatching { client.installReferrer.installReferrer }.getOrNull()
                    if (rawReferrer.isNullOrBlank()) {
                        DeeplinklyCore.w("Empty install referrer")
                        if (!isRetry) {
                            DeeplinklyCore.mainHandler.postDelayed({
                                checkInstallReferrer(activity, true)
                            }, REFERRER_RETRY_DELAY_MS)
                        }
                        return
                    }

                    val parsed = "https://dummy?$rawReferrer".toUri()
                    val clickId = parsed.getQueryParameter("click_id")

                    val initial = linkedMapOf<String, String?>(
                        "source" to "install_referrer",
                        "install_referrer" to rawReferrer,
                        "utm_source" to parsed.getQueryParameter("utm_source"),
                        "utm_medium" to parsed.getQueryParameter("utm_medium"),
                        "utm_campaign" to parsed.getQueryParameter("utm_campaign"),
                        "utm_term" to parsed.getQueryParameter("utm_term"),
                        "utm_content" to parsed.getQueryParameter("utm_content"),
                        "gclid" to parsed.getQueryParameter("gclid"),
                        "fbclid" to parsed.getQueryParameter("fbclid"),
                        "ttclid" to parsed.getQueryParameter("ttclid"),
                        "click_id" to clickId
                    )
                    AttributionStore.saveOnce(initial)

                    if (clickId.isNullOrBlank()) {
                        DeeplinklyCore.d("No click_id in install referrer; skip resolve")
                        return
                    }

                    val enrichment: MutableMap<String, String?> = DeeplinklyUtils.collectEnrichment().toMutableMap().apply {
                        put("install_referrer", rawReferrer)
                        put("android_reported_at", System.currentTimeMillis().toString())
                        put("click_id", clickId)
                    }

                    DeeplinklyCore.ioLaunch {
                        try {
                            val (_, json) = DeeplinklyNetwork.resolveClick(
                                "${DeeplinklyNetwork.RESOLVE_CLICK_ENDPOINT}?click_id=$clickId",
                                apiKey
                            )
                            val dartMap = DeeplinklyNetwork.extractParamsFromJson(json, clickId)

                            // ✅ Emit only the params JSON (legacy-style payload)
                            val paramsString = dartMap["params"] as? String
                            if (!paramsString.isNullOrBlank() && paramsString.trim().startsWith("{")) {
                                try {
                                    val paramsJson = org.json.JSONObject(paramsString)
                                    val paramsMap = mutableMapOf<String, Any?>()
                                    paramsJson.keys().forEach { key ->
                                        paramsMap[key] = paramsJson.opt(key)
                                    }
                                    DeeplinklyCore.d("emitToFlutter(install_referrer) params-only: $paramsMap")
                                    emitToFlutter(paramsMap)
                                } catch (e: Exception) {
                                    DeeplinklyCore.w("Failed to parse deferred params JSON: ${e.message}")
                                }
                            } else {
                                DeeplinklyCore.w("No params found in deferred deep link")
                            }

                            runCatching {
                                EnrichmentSender.sendOnce(
                                    DeeplinklyContext.app,
                                    enrichment,
                                    "install_referrer",
                                    apiKey
                                )
                            }
                        } catch (e: Exception) {
                            DeeplinklyCore.w("installReferrer resolve error: ${e.message}")
                            DeeplinklyNetwork.reportError(
                                apiKey,
                                "installReferrer resolve error",
                                e.stackTraceToString(),
                                clickId
                            )
                        }
                    }
                } finally {
                    runCatching { client.endConnection() }
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                DeeplinklyCore.w("InstallReferrerServiceDisconnected")
            }
        })
    }

    private fun emitToFlutter(payload: Map<String, Any?>) {
        if (!flutterReady) {
            pendingEmits.add(payload)
            DeeplinklyCore.d("Flutter not ready, buffering emit (${pendingEmits.size})")
            return
        }
        try {
            DeeplinklyCore.mainHandler.post {
                channel.invokeMethod("onDeepLink", payload)
            }
        } catch (e: Exception) {
            DeeplinklyCore.e("emitToFlutter failed", e)
        }
    }
}
