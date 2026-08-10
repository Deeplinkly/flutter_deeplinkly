package com.deeplinkly.flutter_deeplinkly

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.NonNull
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.SdkRuntime
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.core.UserIdManager
import com.deeplinkly.flutter_deeplinkly.handlers.DeepLinkHandler
import com.deeplinkly.flutter_deeplinkly.handlers.InstallReferrerHandler
import com.deeplinkly.flutter_deeplinkly.handlers.ClipboardHandler
import com.deeplinkly.flutter_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.flutter_deeplinkly.retry.SdkRetryQueue
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import com.deeplinkly.flutter_deeplinkly.privacy.TrackingPreferences
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.*
import android.os.Handler
import android.os.Looper
import com.deeplinkly.flutter_deeplinkly.enrichment.StartupEnrichment

class FlutterDeeplinklyPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    // Not lateinit: reading the manifest can throw, and the catch below leaves the
    // key unset. The coroutine error handler and every ioLaunch block capture it,
    // so an unset lateinit turned any background failure into an
    // UninitializedPropertyAccessException raised from the error handler itself.
    private var apiKey: String = ""
    private var sdkEnabled = false

    // Held as a single instance so it can be removed again. Registering a fresh
    // lambda per attach leaks a listener per activity re-attach, and every extra
    // listener re-delivers the same deep link.
    private val newIntentListener = PluginRegistry.NewIntentListener { newIntent ->
        Logger.d("onNewIntent received")
        // Reads the current activity rather than one captured at registration
        // time, which would go stale as soon as the activity was recreated.
        activity?.let { act ->
            DeepLinkHandler.handleIntent(act, newIntent, channel, apiKey)
        } ?: Logger.w("Activity is null, dropping intent")
        true
    }

    private val coroutineErrorHandler = CoroutineExceptionHandler { _, e ->
        try {
            val stackTrace = e.stackTraceToString()
            DeeplinklyNetwork.reportError(apiKey, "Coroutine crash", stackTrace)
        } catch (_: Exception) {}
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
        if (call.method == "getDeeplinklyId") {
            result.success(DeeplinklyUtils.getOrCreateDeviceId())
            return
        }
        if (!sdkEnabled) {
            result.success(
                mapOf(
                    "success" to false,
                    "error_code" to "SDK_DISABLED",
                    "error_message" to "Deeplinkly SDK is disabled (missing API key)."
                )
            )
            return
        }
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")
            "getInstallAttribution" -> result.success(AttributionStore.get())
            "flutterReady" -> {
                SdkRuntime.setFlutterReady(channel)
                SdkRuntime.ioLaunch {
                    com.deeplinkly.flutter_deeplinkly.queue.QueueProcessor.processNow(channel, apiKey)
                }
                result.success(true)
            }
            "onLifecycleChange" -> {
                val state = call.argument<String>("state")
                Logger.d("Flutter lifecycle changed to: $state")
                // Handle lifecycle changes - process queues when app resumes
                if (state == "resumed") {
                    SdkRuntime.setFlutterReady(channel)
                    SdkRuntime.ioLaunch {
                        com.deeplinkly.flutter_deeplinkly.queue.QueueProcessor.processNow(channel, apiKey)
                    }
                }
                result.success(true)
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
                            val response = DeeplinklyNetwork.generateLink(payload, apiKey)
                            // Use main handler instead of activity
                            SdkRuntime.mainHandler.post {
                                result.success(response)
                            }
                        } catch (e: Exception) {
                            Logger.e("generateLink failed", e)
                            SdkRuntime.mainHandler.post {
                                result.success(
                                    mapOf(
                                        "success" to false,
                                        "error_code" to "LINK_ERROR",
                                        "error_message" to (e.message ?: "generateLink failed")
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    result.success(
                        mapOf(
                            "success" to false,
                            "error_code" to "LINK_ERROR",
                            "error_message" to (e.message ?: "Unexpected error")
                        )
                    )
                }
            }

            "disableTracking" -> {
                val disabled = call.argument<Boolean>("disabled") ?: false
                TrackingPreferences.setTrackingDisabled(disabled)
                result.success(true)
            }

            "setCustomUserId", "setUserId" -> {
                val userId = call.argument<String>("user_id")
                UserIdManager.updateCustomUserId(userId, apiKey)
                result.success(true)
            }

            "logEvent" -> {
                val eventName = call.argument<String>("event_name")?.trim().orEmpty()
                val params = (call.argument<Map<String, Any?>>("parameters") ?: emptyMap()).toMutableMap()
                if (eventName.isBlank()) {
                    result.success(false)
                    return
                }
                val seq = (Prefs.of().getLong("dl_event_seq", 0L) + 1L).also {
                    Prefs.of().edit().putLong("dl_event_seq", it).apply()
                }
                params["_dl_event_seq"] = seq.toString()
                params["_dl_client_monotonic_ms"] = SystemClock.elapsedRealtime().toString()
                params["_dl_client_wall_epoch_ms"] = System.currentTimeMillis().toString()
                val offsetMin = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
                params["_dl_tz_offset_min"] = offsetMin.toString()
                SdkRuntime.ioLaunch {
                    val ok = DeeplinklyNetwork.logEvent(eventName, params, apiKey)
                    SdkRuntime.mainHandler.post { result.success(ok) }
                }
            }

            "setDebugMode" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                Logger.setDebugMode(enabled)
                result.success(true)
            }

            else -> result.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Logger.d("onAttachedToActivity")
        attachActivity(binding, isConfigChange = false)
    }

    /**
     * A configuration change hands back the *same* launch intent. Replaying the
     * startup path here would resolve and deliver that deep link a second time -
     * and fire attribution again with it - so only the listener is rewired.
     */
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        Logger.d("onReattachedToActivityForConfigChanges")
        attachActivity(binding, isConfigChange = true)
    }

    private fun attachActivity(binding: ActivityPluginBinding, isConfigChange: Boolean) {
        activity = binding.activity
        if (!sdkEnabled) {
            Logger.w("SDK disabled (missing API key). Skipping initialization.")
            return
        }

        // Drop any listener from a previous attach before adding this one, so a
        // re-attach cannot stack duplicates onto the binding.
        activityBinding?.removeOnNewIntentListener(newIntentListener)
        activityBinding = binding
        binding.addOnNewIntentListener(newIntentListener)

        if (isConfigChange) return

        val context = binding.activity.applicationContext
        try {
            // Handle initial intent (safe null check)
            binding.activity.intent
                ?.takeIf { it.data != null }
                ?.let { intent ->
                    DeepLinkHandler.handleIntent(binding.activity, intent, channel, apiKey)
                }

            InstallReferrerHandler.checkInstallReferrer(context, binding.activity, channel, apiKey)

            ClipboardHandler.checkClipboard(channel, apiKey)

            // Process retry queues
            SdkRuntime.ioLaunch {
                SdkRetryQueue.retryAll(apiKey)
                // Also process deep link queues
                com.deeplinkly.flutter_deeplinkly.queue.QueueProcessor.startProcessing(channel, apiKey)
            }
        } catch (e: Exception) {
            Logger.e("Plugin startup failure", e)
            DeeplinklyNetwork.reportError(apiKey, "Plugin startup failure", e.stackTraceToString())
        }
    }

    private fun detachActivity() {
        activityBinding?.removeOnNewIntentListener(newIntentListener)
        activityBinding = null
        activity = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        Logger.d("onDetachedFromActivityForConfigChanges"); detachActivity()
    }

    override fun onDetachedFromActivity() {
        Logger.d("onDetachedFromActivity"); detachActivity()
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        Logger.d("onDetachedFromEngine")
        channel.setMethodCallHandler(null)
        SdkRuntime.setFlutterNotReady()
        com.deeplinkly.flutter_deeplinkly.queue.QueueProcessor.stopProcessing()
        SdkRuntime.ioScope.cancel()
    }
}
