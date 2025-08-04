package com.deeplinkly.flutter_deeplinkly

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.NonNull
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
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit
import android.content.IntentFilter

fun getAppLinkHosts(context: Context): List<String> {
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


object ClipboardHandler {

    private var cachedAppLinkDomains: List<String>? = null

    private fun getCachedAppLinkDomains(context: Context): List<String> {
        if (cachedAppLinkDomains == null) {
            cachedAppLinkDomains = getAppLinkHosts(context)
        }
        return cachedAppLinkDomains ?: emptyList()
    }

    fun checkClipboard(context: Context, channel: MethodChannel, apiKey: String) {
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
            NetworkUtils.reportError(apiKey, "Clipboard fallback failed", e.stackTraceToString())
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
    const val RESOLVE_CLICK_WITH_CODE_ENDPOINT = "$API_BASE_URL/api/v1/resolve-click"
    const val MATCH_FP_ENDPOINT = "$API_BASE_URL/api/v1/match-fp"
    const val GENERATE_LINK_ENDPOINT = "$API_BASE_URL/api/v1/generate-url"
}

object Logger {
    private const val TAG = "Deeplinkly"

    fun d(msg: String) = Log.d(TAG, "✅ $msg")
    fun w(msg: String) = Log.w(TAG, "⚠️ $msg")
    fun e(msg: String, e: Throwable? = null) = Log.e(TAG, "❌ $msg", e)
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

    fun collect(context: Context): Map<String, String?> {
        val pm = context.packageManager
        val pkg = context.packageName

        val versionInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            versionInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            versionInfo.versionCode.toString()
        }

        val config = context.resources.configuration
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0]
        } else {
            @Suppress("DEPRECATION")
            config.locale
        }

        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        return mapOf(
            "android_id" to deviceId,
            "device_id" to deviceId,
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "sdk_int" to Build.VERSION.SDK_INT.toString(),
            "os_version" to Build.VERSION.RELEASE,
            "platform" to "android",
            "device_model" to Build.MODEL,
            "installer_package" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) null else pm.getInstallerPackageName(pkg),
            "app_version" to versionInfo.versionName,
            "app_build_number" to versionCode,
            "locale" to locale.toString(),
            "language" to locale.language,
            "region" to locale.country,
            "timezone" to TimeZone.getDefault().id
        )
    }

    fun collectFingerprint(context: Context): Map<String, String?> {
        val base = collect(context).toMutableMap()

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val pixelRatio = displayMetrics.density

        val currentTime = System.currentTimeMillis()

        // Add enriched display metrics
        base["screen_width"] = screenWidth.toString()
        base["screen_height"] = screenHeight.toString()
        base["pixel_ratio"] = pixelRatio.toString()

        // Timestamps
        base["last_opened_at"] = formatToIso8601(currentTime)
        base["installed_at"] = formatToIso8601(currentTime) // Adjust if you have a real install time

        // User agent approximation
        base["user_agent"] =
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"

        // Required but currently unset fields
        base["advertising_id"] = "" // You can fetch using Google Play Services ID APIs if needed
        base["vendor_id"] = "" // Optional, usually not relevant on Android

        // Hardware fingerprint
        val hardwareFingerprint = generateHardwareFingerprint(
            platform = "android",
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            pixelRatio = pixelRatio,
            language = base["language"] ?: "",
            timezone = base["timezone"] ?: "",
            osVersion = base["os_version"] ?: "",
            deviceModel = base["device_model"] ?: ""
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

    fun generateLink(payload: Map<String, Any?>, apiKey: String): String {
        val conn = openConnection(DomainConfig.GENERATE_LINK_ENDPOINT, apiKey).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }

        val json = JSONObject(payload)
        conn.outputStream.use { it.write(json.toString().toByteArray()) }

        return conn.inputStream.bufferedReader().readText()
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
        } catch (e: Exception) {
            Logger.e("Enrichment failed", e)
        }
    }

    fun reportError(apiKey: String, message: String, stack: String, clickId: String? = null) {
        try {
            val conn = openConnection(DomainConfig.ERROR_ENDPOINT, apiKey).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val payload = JSONObject().apply {
                put("message", message)
                put("stack", stack)
                clickId?.let { put("click_id", it) }
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        } catch (e: Exception) {
            Logger.e("Error reporting failed", e)
        }
    }

    private fun openConnection(url: String, apiKey: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
    }
}

object InstallReferrerHandler {
    fun checkInstallReferrer(
        context: Context,
        activity: Activity,
        channel: MethodChannel,
        apiKey: String
    ) {
        Logger.d("checkInstallReferrer()")
        val referrerClient = InstallReferrerClient.newBuilder(context).build()

        referrerClient.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                if (responseCode == InstallReferrerResponse.OK) {
                    val rawReferrer = referrerClient.installReferrer.installReferrer
                    val clickId = "https://dummy?$rawReferrer".toUri().getQueryParameter("click_id")

                    if (!clickId.isNullOrEmpty()) {
                        // ✅ CLICK ID FLOW
                        val enrichmentData = try {
                            EnrichmentUtils.collect(context).toMutableMap()
                        } catch (e: Exception) {
                            NetworkUtils.reportError(apiKey, "collectEnrichmentData failed", e.stackTraceToString(), clickId)
                            mutableMapOf()
                        }

                        enrichmentData["click_id"] = clickId
                        enrichmentData["install_referrer"] = rawReferrer
                        enrichmentData["android_reported_at"] = System.currentTimeMillis().toString()

                        Thread {
                            try {
                                val (response, json) = NetworkUtils.resolveClick(
                                    "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?click_id=$clickId", apiKey
                                )
                                val dartMap = NetworkUtils.extractParamsFromJson(json, clickId)
                                activity?.runOnUiThread {
                                    channel.invokeMethod("onDeepLink", dartMap)
                                }
                                NetworkUtils.sendEnrichment(enrichmentData, apiKey)
                            } catch (e: Exception) {
                                NetworkUtils.reportError(apiKey, "installReferrer resolve error", e.stackTraceToString(), clickId)
                            }
                        }.start()

                    } else {
                        if (false) {
                            // Fingerprint on android is a little ugly so its better to avoid it
                            val prefs = context.getSharedPreferences("deeplinkly_prefs", Context.MODE_PRIVATE)
                            val hasRunFingerprintFlow = prefs.getBoolean("fingerprint_flow_ran", false)

                            if (hasRunFingerprintFlow) {
                                Logger.d("Fingerprint flow already run before, skipping.")
                                return
                            }

                            Logger.d("No click_id found, running fingerprint fallback (first time)")

                            prefs.edit { putBoolean("fingerprint_flow_ran", true) }

                            val fingerprintData: MutableMap<String, String?> = try {
                                EnrichmentUtils.collectFingerprint(context).toMutableMap().apply {
                                    this["install_referrer"] = rawReferrer
                                    this["android_reported_at"] = System.currentTimeMillis().toString()
                                }
                            } catch (e: Exception) {
                                NetworkUtils.reportError(apiKey, "fingerprint collection failed", e.stackTraceToString())
                                return
                            }


                            Thread {
                                Logger.d("Collected fingerprint data: ${fingerprintData}")
                                Logger.d("Posting to match-fp endpoint...")
                                try {
                                    Logger.d("Thread started for match-fp")

                                    val conn = URL(DomainConfig.MATCH_FP_ENDPOINT).openConnection() as HttpURLConnection
                                    conn.requestMethod = "POST"
                                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                                    conn.setRequestProperty("Content-Type", "application/json")
                                    conn.doOutput = true

                                    val payload = JSONObject(fingerprintData)
                                    Logger.d("Sending payload to match-fp: $payload")
                                    conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                                    val responseText = conn.inputStream.bufferedReader().readText()
                                    Logger.d("Received match-fp response: $responseText")
                                    val json = JSONObject(responseText)

                                    if (json.optBoolean("match", false)) {
                                        val clickIdFromMatch = json.optString("click_id", null)
                                        val dartMap = NetworkUtils.extractParamsFromJson(json, clickIdFromMatch)

                                        activity?.runOnUiThread {
                                            channel.invokeMethod("onDeepLink", dartMap)
                                        }

                                        NetworkUtils.sendEnrichment(fingerprintData, apiKey)
                                    } else {
                                        Logger.d("No fingerprint match found")
                                    }
                                } catch (e: Exception) {
                                    Logger.e("fingerprint fallback failed", e)
                                    NetworkUtils.reportError(apiKey, "fingerprint fallback failed", e.stackTraceToString())
                                }
                            }.start()
                        }
                    }

                } else {
                    Logger.w("InstallReferrer: code=$responseCode")
                }

                try {
                    referrerClient.endConnection()
                } catch (e: Exception) {
                    Logger.e("Error closing referrer client", e)
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
                EnrichmentUtils.collect(context).toMutableMap()
            } catch (e: Exception) {
                NetworkUtils.reportError(apiKey, "collectEnrichmentData failed", e.stackTraceToString(), clickId)
                mutableMapOf()
            }

            enrichmentData["android_reported_at"] = System.currentTimeMillis().toString()
            clickId?.let { enrichmentData["click_id"] = it }
            if (clickId == null && code != null) enrichmentData["code"] = code

            val resolveUrl = if (clickId != null) {
                "${DomainConfig.RESOLVE_CLICK_ENDPOINT}?click_id=$clickId"
            } else {
                "${DomainConfig.RESOLVE_CLICK_WITH_CODE_ENDPOINT}?code=$code"
            }

            Thread {
                try {
                    val (response, json) = NetworkUtils.resolveClick(resolveUrl, apiKey)
                    val dartMap = NetworkUtils.extractParamsFromJson(json, clickId)
                    dartMap["click_id"]?.let { enrichmentData["click_id"] = it.toString() }
                    (context as? Activity)?.runOnUiThread {
                        channel.invokeMethod("onDeepLink", dartMap)
                    }
                    NetworkUtils.sendEnrichment(enrichmentData, apiKey)
                } catch (e: Exception) {
                    NetworkUtils.reportError(apiKey, "resolve exception", e.stackTraceToString(), clickId)
                }
            }.start()

        } catch (e: Exception) {
            NetworkUtils.reportError(apiKey, "handleIntent outer crash", e.stackTraceToString())
        }
    }
}

class FlutterDeeplinklyPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private lateinit var apiKey: String

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Logger.d("onAttachedToEngine")
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "deeplinkly/channel")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${Build.VERSION.RELEASE}")
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

                    Thread {
                        try {
                            val url = NetworkUtils.generateLink(payload, apiKey)
                            activity?.runOnUiThread {
                                result.success(
                                    mapOf("success" to true, "url" to url)
                                )
                            }
                        } catch (e: Exception) {
                            Logger.e("generateLink failed", e)
                            activity?.runOnUiThread {
                                result.success(
                                    mapOf(
                                        "success" to false,
                                        "error_code" to "LINK_ERROR",
                                        "error_message" to e.message
                                    )
                                )
                            }
                        }
                    }.start()
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


            else -> result.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Logger.d("onAttachedToActivity")
        activity = binding.activity

        try {
            val context = binding.activity.applicationContext
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_META_DATA
            )
            val key = appInfo.metaData.getString("com.deeplinkly.sdk.api_key")
            if (key.isNullOrEmpty()) {
                Logger.e("Missing API key in AndroidManifest.xml")
                return
            }
            apiKey = key

            DeepLinkHandler.handleIntent(activity!!, activity!!.intent, channel, apiKey)
            binding.addOnNewIntentListener {
                Logger.d("onNewIntent received")
                DeepLinkHandler.handleIntent(activity!!, it, channel, apiKey)
                true
            }

            InstallReferrerHandler.checkInstallReferrer(context, activity!!, channel, apiKey)
            ClipboardHandler.checkClipboard(context, channel, apiKey)

        } catch (e: Exception) {
            NetworkUtils.reportError(apiKey, "Plugin startup failure", e.stackTraceToString())
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
    }
}
