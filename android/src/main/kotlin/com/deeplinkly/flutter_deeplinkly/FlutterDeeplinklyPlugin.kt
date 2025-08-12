package com.deeplinkly.flutter_deeplinkly

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.handlers.DeepLinkHandler
import com.deeplinkly.flutter_deeplinkly.handlers.InstallReferrerHandler
import com.deeplinkly.flutter_deeplinkly.handlers.ClipboardHandler
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
import com.deeplinkly.flutter_deeplinkly.retry.SdkRetryQueue
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.privacy.TrackingPreferences
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import android.os.Handler
import android.os.Looper
import com.deeplinkly.flutter_deeplinkly.enrichment.StartupEnrichment

class FlutterDeeplinklyPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private lateinit var apiKey: String
    private var sdkEnabled = false

    private val coroutineErrorHandler = CoroutineExceptionHandler { _, e ->
        try {
            NetworkUtils.reportError(apiKey, "Coroutine crash", Log.getStackTraceString(e))
        } catch (_: Exception) {}
        Logger.e("Coroutine crash", e)
    }

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
            StartupEnrichment.schedule(apiKey)
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
            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")
            "getInstallAttribution" -> result.success(AttributionStore.get())
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
                    result.success(mapOf("success" to false, "error_code" to "LINK_ERROR", "error_message" to e.message))
                }
            }
            "disableTracking" -> {
                val disabled = call.argument<Boolean>("disabled") ?: false
                TrackingPreferences.setTrackingDisabled(disabled)
                result.success(true)
            }
            "setCustomUserId" -> {
                val userId = call.argument<String>("user_id")
                Prefs.of().edit().putString("custom_user_id", userId).apply()
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
            SdkRuntime.ioLaunch { SdkRetryQueue.retryAll(apiKey) }
        } catch (e: Exception) {
            NetworkUtils.reportError(apiKey, "Plugin startup failure", e.stackTraceToString())
        }
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
    override fun onDetachedFromActivityForConfigChanges() { Logger.d("onDetachedFromActivityForConfigChanges"); activity = null }
    override fun onDetachedFromActivity() { Logger.d("onDetachedFromActivity"); activity = null }
    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        Logger.d("onDetachedFromEngine")
        channel.setMethodCallHandler(null)
        SdkRuntime.ioScope.cancel()
    }
}
