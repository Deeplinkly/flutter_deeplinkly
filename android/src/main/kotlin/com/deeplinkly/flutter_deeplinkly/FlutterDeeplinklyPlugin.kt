package com.deeplinkly.flutter_deeplinkly

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.NonNull
import kotlinx.coroutines.cancel
import androidx.core.net.toUri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler

import com.google.android.gms.ads.identifier.AdvertisingIdClient

import java.util.UUID

object DeeplinklyContext {
    lateinit var app: Context
}

object AttributionStore {
    private const val KEY = "initial_attribution"

    fun saveOnce(map: Map<String, String?>) {
        val ctx = DeeplinklyContext.app
        val prefs = ctx.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) {
            val json = JSONObject(map.filterValues { it != null }).toString()
            prefs.edit().putString(KEY, json).apply()
        }
    }

    fun get(): Map<String, String> {
        val ctx = DeeplinklyContext.app
        val prefs = ctx.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { k -> put(k, obj.optString(k, "")) }
            }
        } catch (_: Exception) { emptyMap() }
    }
}


object SdkRuntime {
    lateinit var ioScope: CoroutineScope
    lateinit var mainHandler: Handler

    fun ioLaunch(block: suspend CoroutineScope.() -> Unit) =
        (if (::ioScope.isInitialized) ioScope else CoroutineScope(Dispatchers.IO)).launch(block = block)

    fun postToFlutter(channel: MethodChannel, method: String, args: Any?) {
        mainHandler.post {
            try {
                channel.invokeMethod(method, args)
            } catch (e: Exception) {
                Logger.w("invoke failed: ${e.message}")
            }
        }
    }
}


object TrackingPreferences {
    private const val TRACKING_DISABLED_KEY = "tracking_disabled"

    fun isTrackingDisabled(): Boolean {
        val prefs = DeeplinklyContext.app.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(TRACKING_DISABLED_KEY, false)
    }

    fun setTrackingDisabled(disabled: Boolean) {
        val prefs = DeeplinklyContext.app.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(TRACKING_DISABLED_KEY, disabled).apply()
    }
}

suspend fun getAdvertisingId(context: Context): String? = withContext(Dispatchers.IO) {
    try {
        val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
        if (!info.isLimitAdTrackingEnabled) info.id else null
    } catch (e: Exception) {
        Logger.w("Failed to get Advertising ID: ${e.message}")
        null
    }
}


fun retrieveAppLinkHosts(context: Context): List<String> {
    val hosts = mutableSetOf<String>()
    val pm = context.packageManager

    val intent = Intent(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        data = Uri.parse("https://example.com")
    }

    val resolveInfos = pm.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)

    for (info in resolveInfos) {
        if (info.activityInfo.packageName != context.packageName) continue

        val filter = info.filter ?: continue

        val isAppLinkIntent = filter.hasAction(Intent.ACTION_VIEW) &&
                filter.hasCategory(Intent.CATEGORY_DEFAULT) &&
                filter.hasCategory(Intent.CATEGORY_BROWSABLE)

        if (!isAppLinkIntent) continue

        for (i in 0 until filter.countDataSchemes()) {
            if (filter.getDataScheme(i) != "https") continue

            for (j in 0 until filter.countDataAuthorities()) {
                val host = filter.getDataAuthority(j)?.host
                if (!host.isNullOrBlank()) {
                    hosts.add(host)
                }
            }
        }
    }

    return hosts.toList()
}

object SdkRetryQueue {
    private const val KEY = "sdk_retry_queue"
    private const val MAX_QUEUE_SIZE = 50

    fun enqueue(payload: JSONObject, type: String) {
        val context = DeeplinklyContext.app
        val prefs = context.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
        val current = getQueue().toMutableList()

        val wrapped = JSONObject().apply {
            put("type", type)
            put("payload", payload.toString())
        }

        current.add(wrapped.toString())
        if (current.size > MAX_QUEUE_SIZE) current.removeAt(0)

        prefs.edit().putStringSet(KEY, current.toSet()).apply()
    }

    fun getQueue(): List<String> {
        val context = DeeplinklyContext.app
        val prefs = context.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, emptySet())?.toList() ?: emptyList()
    }

    fun remove(json: String) {
        val context = DeeplinklyContext.app
        val prefs = context.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
        val current = getQueue().toMutableSet()
        current.remove(json)
        prefs.edit().putStringSet(KEY, current).apply()
    }

    suspend fun retryAll(apiKey: String) = withContext(Dispatchers.IO) {
        val context = DeeplinklyContext.app
        if (TrackingPreferences.isTrackingDisabled()) {
            Logger.d("Tracking is disabled. Skipping retry queue.")
            return@withContext
        }
        for (json in getQueue()) {
            try {
                val obj = JSONObject(json)
                val type = obj.getString("type")
                val payload = JSONObject(obj.getString("payload"))

                when (type) {
                    "enrichment" -> NetworkUtils.sendEnrichmentNow(payload, apiKey)
                    "error"      -> NetworkUtils.sendErrorNow(payload, apiKey)
                    else         -> Logger.w("Unknown SDK retry type: $type")
                }

                remove(json)
            } catch (e: Exception) {
                Logger.e("Retry failed for $json", e) // keep in queue
            }
        }
    }
}


object ClipboardHandler {

    private var cachedAppLinkDomains: List<String>? = null

    private fun getCachedAppLinkDomains(context: Context): List<String> {
        if (cachedAppLinkDomains == null) {
            cachedAppLinkDomains = retrieveAppLinkHosts(context)
        }
        return cachedAppLinkDomains ?: emptyList()
    }

    fun checkClipboard(channel: MethodChannel, apiKey: String) {
        val context = DeeplinklyContext.app
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            if (!clipboard.hasPrimaryClip()) {
                Logger.d("Clipboard is empty")
                return
            }

            val clip = clipboard.primaryClip ?: return
            val item = clip.getItemAt(0)
            val text = item.text?.toString()?.trim() ?: return

            val lowerText = text.lowercase()

            val domains = getCachedAppLinkDomains(context)

            val matched = domains.any { domain ->
                lowerText.startsWith(domain) ||
                        lowerText.startsWith("https://$domain") ||
                        lowerText.startsWith("http://$domain")
            }

            if (!matched) {
                Logger.d("Clipboard text does not match any known app link domain")
                return
            }

            Logger.d("Found deep link in clipboard: $text")

            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(text))
            DeepLinkHandler.handleIntent(context, deepLinkIntent, channel, apiKey)

            clipboard.setPrimaryClip(ClipData.newPlainText("", "")) // clear clipboard

        } catch (e: Exception) {
            NetworkUtils.reportError(context, apiKey, "Clipboard fallback failed", e.stackTraceToString())
        }
    }
}

object EnrichmentSender {
    suspend fun sendOnce(
        context: Context,
        enrichmentData: MutableMap<String, String?>,
        source: String,
        apiKey: String
    ) {
        if (TrackingPreferences.isTrackingDisabled()) {
            Logger.d("Tracking is disabled. Skipping enrichment/reporting.")
            return
        }
        val prefs = context.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
        val clickId = enrichmentData["click_id"]
        val deviceId = enrichmentData["deeplinkly_device_id"]

        // Check for already enriched
        val enrichedKey = "${source}_enriched"
        val alreadyEnriched = prefs.getBoolean(enrichedKey, false)

        // Attach advertising_id if needed
        if (enrichmentData["advertising_id"].isNullOrEmpty()) {
            val cachedAdId = prefs.getString("advertising_id", null)
            if (!cachedAdId.isNullOrEmpty()) {
                enrichmentData["advertising_id"] = cachedAdId
                Logger.d("Using cached advertising_id: $cachedAdId")
            } else {
                val fetchedAdId = getAdvertisingId(context)
                if (!fetchedAdId.isNullOrEmpty()) {
                    enrichmentData["advertising_id"] = fetchedAdId
                    prefs.edit().putString("advertising_id", fetchedAdId).apply()
                    Logger.d("Fetched advertising_id: $fetchedAdId")
                }
            }
        }

        val hasAttributionData = listOf(
            "click_id", "utm_source", "utm_medium", "utm_campaign", "gclid", "fbclid", "ttclid"
        ).any { !enrichmentData[it].isNullOrBlank() }

        if (!alreadyEnriched && hasAttributionData) {
            Logger.d("Sending enrichment for source: $source")
            NetworkUtils.sendEnrichment(enrichmentData, apiKey)
            prefs.edit().putBoolean(enrichedKey, true).apply()
        } else {
            Logger.d("Skipping enrichment: already sent or no attribution data")
        }
    }
}


object DomainConfig {
    // Primary API domain
    const val API_BASE_URL = "https://deeplinkly.com"

    // Endpoints
    const val ENRICH_ENDPOINT = "$API_BASE_URL/api/v1/enrich"
    const val ERROR_ENDPOINT = "$API_BASE_URL/api/v1/sdk-error"
    const val RESOLVE_CLICK_ENDPOINT = "$API_BASE_URL/api/v1/resolve"
    const val GENERATE_LINK_ENDPOINT = "$API_BASE_URL/api/v1/generate-url"
}

object Logger {
    private const val TAG = "Deeplinkly"

    fun d(msg: String) = Log.d(TAG, "✅ $msg")
    fun w(msg: String) = Log.w(TAG, "⚠️ $msg")
    fun e(msg: String, e: Throwable? = null) = Log.e(TAG, "❌ $msg", e)
}

object DeviceIdManager {
    private const val DEVICE_ID_KEY = "deeplinkly_device_id"

    fun getOrCreateDeviceId(): String {
        val context = DeeplinklyContext.app
        val prefs = context.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
        return prefs.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(DEVICE_ID_KEY, it).apply()
        }
    }
}

object EnrichmentUtils {

    private fun formatToIso8601(timestampMillis: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(timestampMillis))
    }

    private fun generateHardwareFingerprint(
        platform: String,
        screenWidth: Int?,
        screenHeight: Int?,
        pixelRatio: Float?,
        language: String,
        timezone: String,
        osVersion: String,
        deviceModel: String
    ): String {
        val fingerprintString = listOfNotNull(
            platform,
            osVersion,
            deviceModel,
            screenWidth?.toString(),
            screenHeight?.toString(),
            pixelRatio?.toString(),
            language,
            timezone
        ).joinToString("|")
        return fingerprintString.hashCode().toString(16)
    }

    fun collect(): Map<String, String?> {
        val context = DeeplinklyContext.app
        val pm = context.packageManager
        val pkg = context.packageName

        val out = mutableMapOf<String, String?>()
        val prefs = context.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)

        out["custom_user_id"] = prefs.getString("custom_user_id", null)
        out["deeplinkly_device_id"] = DeviceIdManager.getOrCreateDeviceId()
        out.putAll(collectFingerprint())

        val versionInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
        } catch (e: Exception) {
            null
        }

        val versionCode = versionInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString()
            else @Suppress("DEPRECATION") it.versionCode.toString()
        }

        val config = context.resources.configuration
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) config.locales[0] else @Suppress("DEPRECATION") config.locale

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        val installer = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                runCatching { pm.getInstallSourceInfo(pkg).installingPackageName }.getOrNull()
            }
            else -> @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }

        out += mapOf(
            "android_id" to androidId,
            "device_id" to androidId, // if you truly want same value
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "sdk_int" to Build.VERSION.SDK_INT.toString(),
            "os_version" to Build.VERSION.RELEASE,
            "platform" to "android",
            "device_model" to Build.MODEL,
            "installer_package" to installer,
            "app_version" to versionInfo?.versionName,
            "app_build_number" to versionCode,
            "locale" to if (Build.VERSION.SDK_INT >= 21) locale.toLanguageTag() else locale.toString(),
            "language" to locale.language,
            "region" to locale.country,
            "timezone" to TimeZone.getDefault().id
        )

        return out
    }

    fun collectFingerprint(): Map<String, String?> {
        val context = DeeplinklyContext.app
        val base = mutableMapOf<String, String?>()

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val pixelRatio = displayMetrics.density

        val currentTime = System.currentTimeMillis()

        base["screen_width"] = screenWidth.toString()
        base["screen_height"] = screenHeight.toString()
        base["pixel_ratio"] = pixelRatio.toString()
        base["last_opened_at"] = formatToIso8601(currentTime)

        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (e: Exception) {
            null
        }

        val installTimeMillis = packageInfo?.firstInstallTime ?: currentTime
        base["installed_at"] = formatToIso8601(installTimeMillis)

        base["user_agent"] =
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"

        // ✅ Put them here
        val language = context.resources.configuration.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) it.locales[0].language else it.locale.language
        }
        val timezone = TimeZone.getDefault().id
        val osVersion = Build.VERSION.RELEASE ?: ""
        val deviceModel = Build.MODEL ?: ""

        val hardwareFingerprint = generateHardwareFingerprint(
            platform = "android",
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            pixelRatio = pixelRatio,
            language = language,
            timezone = timezone,
            osVersion = osVersion,
            deviceModel = deviceModel
        )
        base["hardware_fingerprint"] = hardwareFingerprint

        return base
    }
}

object NetworkUtils {
    // Use endpoints from DomainConfig

    fun resolveClick(url: String, apiKey: String): Pair<String, JSONObject> {
        val conn = openConnection(url, apiKey).apply {
            setRequestProperty("Accept", "application/json")
        }
        val response = conn.inputStream.bufferedReader().readText()
        return Pair(response, JSONObject(response))
    }

    fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            var value = this[key]
            if (value is JSONObject) {
                value = value.toMap()
            }
            map[key] = value
        }
        return map
    }


    fun generateLink(payload: Map<String, Any?>, apiKey: String): Map<String, Any?> {
        val conn = openConnection(DomainConfig.GENERATE_LINK_ENDPOINT, apiKey).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }

        val json = JSONObject(payload)
        conn.outputStream.use { it.write(json.toString().toByteArray()) }

        val responseCode = conn.responseCode
        val responseBody = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
        }

        // Parse the response into a Map
        val jsonResponse = JSONObject(responseBody)
        return jsonResponse.toMap()
    }


    fun extractParamsFromJson(json: JSONObject, clickId: String?): HashMap<String, Any?> {
        val params = json.optJSONObject("params") ?: JSONObject()
        return hashMapOf<String, Any?>().apply {
            put("click_id", clickId ?: json.optString("click_id", null))
            params.keys().forEach { key -> put(key, params.get(key)) }
        }
    }

    fun sendEnrichment(data: Map<String, String?>, apiKey: String) {
        try {
            val conn = openConnection(DomainConfig.ENRICH_ENDPOINT, apiKey).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val json = JSONObject(data.filterValues { it != null })
            conn.outputStream.use { it.write(json.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                throw Exception("Non-200 response: ${conn.responseCode}")
            }

        } catch (e: Exception) {
            Logger.e("Enrichment failed, queueing", e)
            val json = JSONObject(data.filterValues { it != null })
            SdkRetryQueue.enqueue(json, "enrichment")
        }
    }

    suspend fun sendEnrichmentNow(payload: JSONObject, apiKey: String) = withContext(Dispatchers.IO) {
        val conn = openConnection(DomainConfig.ENRICH_ENDPOINT, apiKey).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        if (conn.responseCode != 200) throw Exception("Non-200 enrichment response: ${conn.responseCode}")
    }


    fun reportError(context: Context, apiKey: String, message: String, stack: String, clickId: String? = null) {
        if (TrackingPreferences.isTrackingDisabled()) {
            Logger.d("Tracking is disabled. Skipping enrichment/reporting.")
            return
        }

        try {
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
        } catch (e: Exception) {
            Logger.e("Error reporting failed, queueing", e)
            val payload = JSONObject().apply {
                put("message", message)
                put("stack", stack)
                clickId?.let { put("click_id", it) }
            }
            SdkRetryQueue.enqueue(payload, "error")
        }
    }

    suspend fun sendErrorNow(payload: JSONObject, apiKey: String) = withContext(Dispatchers.IO) {
        val conn = openConnection(DomainConfig.ERROR_ENDPOINT, apiKey).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        if (conn.responseCode != 200) throw Exception("Non-200 error report response: ${conn.responseCode}")
    }

    private fun openConnection(url: String, apiKey: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
    }
}

object InstallReferrerHandler {
    private const val KEY_REFERRER_HANDLED = "install_referrer_handled"

    fun checkInstallReferrer(
        context: Context,
        activity: Activity,
        channel: MethodChannel,
        apiKey: String
    ) {
        Logger.d("checkInstallReferrer()")
        if (TrackingPreferences.isTrackingDisabled()) {
            Logger.d("Tracking is disabled. Skipping enrichment/reporting.")
            return
        }

        val prefs = context.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
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
                            NetworkUtils.reportError(context, apiKey, "collectEnrichmentData failed", e.stackTraceToString(), clickId)
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
                            "click_id" to clickId,
                            "deeplinkly_device_id" to enrichmentData["deeplinkly_device_id"],
                            "advertising_id" to enrichmentData["advertising_id"]
                        )
                        AttributionStore.saveOnce(initialAttribution)

                        if (!clickId.isNullOrEmpty()) {
                            SdkRuntime.ioLaunch {
                                try {
                                    val (_, json) = NetworkUtils.resolveClick(
                                        "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?click_id=$clickId", apiKey
                                    )
                                    val dartMap = NetworkUtils.extractParamsFromJson(json, clickId)

                                    // 🔒 Post to Flutter only once per install
                                    if (!prefs.getBoolean(KEY_REFERRER_HANDLED, false)) {
                                        SdkRuntime.postToFlutter(channel, "onDeepLink", dartMap)
                                        prefs.edit().putBoolean(KEY_REFERRER_HANDLED, true).apply()
                                    }

                                    EnrichmentSender.sendOnce(context, enrichmentData, "install_referrer", apiKey)
                                } catch (e: Exception) {
                                    NetworkUtils.reportError(context, apiKey, "installReferrer resolve error", e.stackTraceToString(), clickId)
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

object DeepLinkHandler {
    fun handleIntent(
        context: Context,
        intent: Intent?,
        channel: MethodChannel,
        apiKey: String
    ) {
        try {
            Logger.d("handleIntent with $intent")
            val data = intent?.data ?: return
            val clickId = data.getQueryParameter("click_id")
            val code = data.pathSegments.firstOrNull()

            if (clickId == null && code == null) {
                Logger.d("No click_id or code in intent")
                return
            }

            val enrichmentData = try {
                EnrichmentUtils.collect().toMutableMap()
            } catch (e: Exception) {
                NetworkUtils.reportError(context, apiKey, "collectEnrichmentData failed", e.stackTraceToString(), clickId)
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
                    val (_, json) = NetworkUtils.resolveClick(resolveUrl, apiKey)
                    val dartMap = NetworkUtils.extractParamsFromJson(json, clickId)
                    dartMap["click_id"]?.let { enrichmentData["click_id"] = it.toString() }

                    val normalized = linkedMapOf<String, String?>(
                        "source" to "deep_link",
                        "click_id" to (dartMap["click_id"] as? String ?: clickId),
                        "utm_source" to (dartMap["utm_source"] as? String),
                        "utm_medium" to (dartMap["utm_medium"] as? String),
                        "utm_campaign" to (dartMap["utm_campaign"] as? String),
                        "utm_term" to (dartMap["utm_term"] as? String),
                        "utm_content" to (dartMap["utm_content"] as? String),
                        "gclid" to (dartMap["gclid"] as? String),
                        "fbclid" to (dartMap["fbclid"] as? String),
                        "ttclid" to (dartMap["ttclid"] as? String),
                        "deeplinkly_device_id" to enrichmentData["deeplinkly_device_id"],
                        "advertising_id" to enrichmentData["advertising_id"]
                    )
                    AttributionStore.saveOnce(normalized)

                    SdkRuntime.postToFlutter(channel, "onDeepLink", dartMap)
                    EnrichmentSender.sendOnce(context, enrichmentData, "deep_link", apiKey)

                } catch (e: Exception) {
                    // Fallback: parse directly from the incoming deep link URI
                    val fallback = linkedMapOf<String, Any?>(
                        "click_id" to clickId,
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
                    SdkRuntime.postToFlutter(channel, "onDeepLink", fallback)
                    NetworkUtils.reportError(context, apiKey, "resolve exception", e.stackTraceToString(), clickId)
                }
            }

        } catch (e: Exception) {
            NetworkUtils.reportError(context, apiKey, "handleIntent outer crash", e.stackTraceToString())
        }
    }
}

class FlutterDeeplinklyPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private lateinit var apiKey: String
    private var sdkEnabled = false

    private val coroutineErrorHandler = CoroutineExceptionHandler { _, e ->
        try {
            DeeplinklyContext.app.let { ctx ->
                NetworkUtils.reportError(ctx, apiKey, "Coroutine crash", Log.getStackTraceString(e))
            }
        } catch (_: Exception) { /* swallow */
        }
        Logger.e("Coroutine crash", e)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        DeeplinklyContext.app = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "deeplinkly/channel")
        channel.setMethodCallHandler(this)

        SdkRuntime.ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineErrorHandler)
        SdkRuntime.mainHandler = Handler(Looper.getMainLooper())

        sdkEnabled = try {
            val appInfo = DeeplinklyContext.app.packageManager.getApplicationInfo(
                DeeplinklyContext.app.packageName,
                android.content.pm.PackageManager.GET_META_DATA
            )
            apiKey = appInfo.metaData?.getString("com.deeplinkly.sdk.api_key").orEmpty()
            if (apiKey.isBlank()) {
                Logger.e("Missing API key in AndroidManifest.xml (com.deeplinkly.sdk.api_key)")
                false
            } else true
        } catch (e: Exception) {
            Logger.e("Failed to read API key from manifest", e)
            false
        }
    }


    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (!sdkEnabled) {
            result.success(mapOf(
                "success" to false,
                "error_code" to "SDK_DISABLED",
                "error_message" to "Deeplinkly SDK is disabled (missing API key)."
            ))
            return
        }
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${Build.VERSION.RELEASE}")
            }

            "getInstallAttribution" -> {
                result.success(AttributionStore.get())
            }

            "generateLink" -> {
                try {
                    val args = call.arguments as? Map<*, *> ?: return result.success(
                        mapOf("success" to false, "error_code" to "INVALID", "error_message" to "Expected map")
                    )

                    val content = args["content"] as? Map<*, *> ?: return result.success(
                        mapOf("success" to false, "error_code" to "INVALID", "error_message" to "Missing content")
                    )

                    val options = args["options"] as? Map<*, *> ?: return result.success(
                        mapOf("success" to false, "error_code" to "INVALID", "error_message" to "Missing options")
                    )

                    val payload = mutableMapOf<String, Any?>().apply {
                        putAll(content.mapKeys { it.key.toString() })
                        putAll(options.mapKeys { it.key.toString() })
                    }

                    SdkRuntime.ioLaunch {
                        try {
                            val url = NetworkUtils.generateLink(payload, apiKey)
                            activity?.runOnUiThread {
                                result.success(mapOf("success" to true, "url" to url))
                            }
                        } catch (e: Exception) {
                            Logger.e("generateLink failed", e)
                            activity?.runOnUiThread {
                                result.success(mapOf("success" to false, "error_code" to "LINK_ERROR", "error_message" to e.message))
                            }
                        }
                    }

                } catch (e: Exception) {
                    result.success(
                        mapOf(
                            "success" to false,
                            "error_code" to "LINK_ERROR",
                            "error_message" to e.message
                        )
                    )
                }
            }

            "disableTracking" -> {
                val disabled = call.argument<Boolean>("disabled") ?: false
                val context = activity?.applicationContext
                if (context != null) {
                    TrackingPreferences.setTrackingDisabled(disabled)
                }
                result.success(true)
            }


            "setCustomUserId" -> {
                val userId = call.argument<String>("user_id")
                val prefs = activity?.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
                prefs?.edit()?.putString("custom_user_id", userId)?.apply()
                result.success(true)
            }


            else -> result.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Logger.d("onAttachedToActivity")
        activity = binding.activity
        if (!sdkEnabled) {
            Logger.w("SDK disabled (missing API key). Skipping initialization.")
            return
        }
        val context = binding.activity.applicationContext
        try {
            DeepLinkHandler.handleIntent(activity!!, activity!!.intent, channel, apiKey)
            binding.addOnNewIntentListener {
                Logger.d("onNewIntent received")
                DeepLinkHandler.handleIntent(activity!!, it, channel, apiKey)
                true
            }

            InstallReferrerHandler.checkInstallReferrer(context, activity!!, channel, apiKey)
            ClipboardHandler.checkClipboard(channel, apiKey)
            SdkRuntime.ioLaunch {
                SdkRetryQueue.retryAll(apiKey)
            }

        } catch (e: Exception) {
            NetworkUtils.reportError(context, apiKey, "Plugin startup failure", e.stackTraceToString())
        }
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)

    override fun onDetachedFromActivityForConfigChanges() {
        Logger.d("onDetachedFromActivityForConfigChanges")
        activity = null
    }

    override fun onDetachedFromActivity() {
        Logger.d("onDetachedFromActivity")
        activity = null
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        Logger.d("onDetachedFromEngine")
        channel.setMethodCallHandler(null)
        SdkRuntime.ioScope.cancel()
    }
}
