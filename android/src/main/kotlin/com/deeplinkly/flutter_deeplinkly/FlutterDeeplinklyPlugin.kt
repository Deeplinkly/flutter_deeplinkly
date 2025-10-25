// FILE: com/deeplinkly/flutter_deeplinkly/DeeplinklyPlugin.kt
package com.deeplinkly.flutter_deeplinkly

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyCore
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.flutter_deeplinkly.session.DeeplinklySession
import com.deeplinkly.flutter_deeplinkly.storage.AttributionStore
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*

/**
 * ✅ Unified entry point: manages MethodChannel, session lifecycle, and SDK API exposure.
 */
class FlutterDeeplinklyPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    ActivityAware {

    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private lateinit var apiKey: String
    private var sdkEnabled = false
    private lateinit var session: DeeplinklySession

    private val mainHandler = Handler(Looper.getMainLooper())
    private val coroutineErrorHandler = CoroutineExceptionHandler { _, e ->
        runCatching {
            DeeplinklyCore.ioLaunch {
                DeeplinklyNetwork.reportError(apiKey, "Coroutine crash", e.stackTraceToString())
            }
        }
        DeeplinklyCore.e("Coroutine crash: ${e.message}")
    }

    // -------------------- ENGINE ATTACH --------------------
    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        DeeplinklyContext.app = binding.applicationContext

        // Create method channel
        channel = MethodChannel(binding.binaryMessenger, "deeplinkly/channel")
        channel.setMethodCallHandler(this)


        // Read API key from manifest
        sdkEnabled = try {
            val appInfo = DeeplinklyContext.app.packageManager.getApplicationInfo(
                DeeplinklyContext.app.packageName,
                android.content.pm.PackageManager.GET_META_DATA
            )
            apiKey = appInfo.metaData?.getString("com.deeplinkly.sdk.api_key").orEmpty()
            if (apiKey.isBlank()) {
                DeeplinklyCore.e("Missing API key in AndroidManifest.xml (com.deeplinkly.sdk.api_key)")
                false
            } else true
        } catch (e: Exception) {
            DeeplinklyCore.e("Failed to read API key from manifest: ${e.message}")
            false
        }

        // Initialize session handler
        session = DeeplinklySession(apiKey, channel)

        // Kick pending retry queue on cold start
        DeeplinklyCore.ioLaunch { DeeplinklyNetwork.retryAll(apiKey) }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        runCatching { DeeplinklyCore.ioScope.cancel() }
    }

    // -------------------- METHOD CHANNEL HANDLER --------------------
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
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

            "init" -> {
                session.markFlutterReady()
                DeeplinklyCore.ioLaunch { DeeplinklyNetwork.retryAll(apiKey) }
                result.success(true)
            }

            "disableTracking" -> {
                val disabled = call.argument<Boolean>("disabled") ?: false
                DeeplinklyUtils.setTrackingDisabled(disabled)
                result.success(true)
            }

            "setCustomUserId" -> {
                val userId = call.argument<String>("user_id")
                DeeplinklyUtils.setCustomUserId(userId)
                result.success(true)
            }

            "getInstallAttribution" -> result.success(AttributionStore.get())

            "generateLink" -> {
                val args = call.arguments as? Map<*, *>
                if (args == null) {
                    result.success(
                        mapOf(
                            "success" to false,
                            "error_code" to "INVALID",
                            "error_message" to "Expected map"
                        )
                    )
                    return
                }
                val content = args["content"] as? Map<*, *>
                val options = args["options"] as? Map<*, *>
                if (content == null || options == null) {
                    result.success(
                        mapOf(
                            "success" to false,
                            "error_code" to "INVALID",
                            "error_message" to "Missing content/options"
                        )
                    )
                    return
                }

                val payload = mutableMapOf<String, Any?>().apply {
                    putAll(content.mapKeys { it.key.toString() })
                    putAll(options.mapKeys { it.key.toString() })
                }

                DeeplinklyCore.ioLaunch {
                    try {
                        val response = DeeplinklyNetwork.generateLink(payload, apiKey)
                        DeeplinklyCore.mainHandler.post { result.success(response) }
                    } catch (e: Exception) {
                        DeeplinklyCore.e("generateLink failed: ${e.message}")
                        DeeplinklyCore.mainHandler.post {
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
            }

            else -> result.notImplemented()
        }
    }

    // -------------------- ACTIVITY AWARE --------------------
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity

        // Flush retry queue on every attach
        DeeplinklyCore.ioLaunch { DeeplinklyNetwork.retryAll(apiKey) }

        session.onActivityStarted(binding.activity, binding.activity.intent)
        binding.addOnNewIntentListener { intent ->
            session.onNewIntent(intent)
            true
        }
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }
}
