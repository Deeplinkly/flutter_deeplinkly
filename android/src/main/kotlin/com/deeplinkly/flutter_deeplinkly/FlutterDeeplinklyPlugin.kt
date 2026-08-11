package com.deeplinkly.flutter_deeplinkly

import android.app.Activity
import android.os.Build
import androidx.annotation.NonNull
import com.deeplinkly.android_deeplinkly.Deeplinkly
import com.deeplinkly.android_deeplinkly.DeeplinklyDeepLinkListener
import com.deeplinkly.android_deeplinkly.core.Logger
import com.deeplinkly.android_deeplinkly.privacy.AttributionLevel
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

/**
 * Flutter bridge over [Deeplinkly].
 *
 * Translation only: this class owns the method channel and the Flutter
 * lifecycle, and everything it is asked to do it forwards. The SDK proper is
 * plain Android and knows nothing about Flutter, so a native integration and a
 * Flutter one run identical code.
 */
class FlutterDeeplinklyPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null

    // Held as a single instance so it can be removed again. Registering a fresh
    // lambda per attach leaks a listener per activity re-attach, and every extra
    // listener re-delivers the same deep link.
    private val newIntentListener = PluginRegistry.NewIntentListener { newIntent ->
        Logger.d("onNewIntent received")
        // Reads the current activity rather than one captured at registration
        // time, which would go stale as soon as the activity was recreated.
        activity?.let { act ->
            Deeplinkly.onNewIntent(act, newIntent)
        } ?: Logger.w("Activity is null, dropping intent")
        true
    }

    /**
     * Forwards a link to Dart.
     *
     * One instance, attached and detached rather than rebuilt, so a re-attach
     * cannot leave two of these delivering the same link.
     *
     * `raw` is forwarded unchanged so Dart's envelope stays exactly
     * `{click_id, params, probability}`.
     */
    private val deepLinkListener = DeeplinklyDeepLinkListener { link ->
        channel.invokeMethod("onDeepLink", link.raw)
    }

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "deeplinkly/channel")
        channel.setMethodCallHandler(this)

        // autoCapture off: ActivityAware is a more precise signal than
        // onActivityCreated here, and it is what lets a configuration change
        // rewire the listener without replaying the launch intent.
        Deeplinkly.init(binding.applicationContext, autoCaptureLaunchIntents = false)

        // Deliberately no listener yet. Dart has not registered its
        // `onDeepLink` handler at engine attach, and `invokeMethod` to a
        // channel nobody is handling succeeds silently - so delivering here
        // would drop the link from the queue and lose it. Dart says when it is
        // ready via the `flutterReady` call below.
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        // Answered before the enabled gate: the id is generated locally and
        // needs no API key, so it stays available to an app whose manifest
        // meta-data is missing.
        if (call.method == "getDeeplinklyId") {
            result.success(Deeplinkly.getDeeplinklyId())
            return
        }
        if (!Deeplinkly.isEnabled) {
            result.success(DISABLED_ENVELOPE)
            return
        }
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")

            "getInstallAttribution" -> result.success(Deeplinkly.getInstallAttribution())

            "flutterReady" -> {
                // Dart has its handler up. Attaching drains whatever queued
                // while it did not.
                Deeplinkly.setDeepLinkListener(deepLinkListener)
                result.success(true)
            }

            "onLifecycleChange" -> {
                val state = call.argument<String>("state")
                Logger.d("Flutter lifecycle changed to: $state")
                if (state == "resumed") {
                    // Re-attached rather than assumed: an engine that detached
                    // and came back cleared the listener on the way out.
                    Deeplinkly.setDeepLinkListener(deepLinkListener)
                    // Secondary trigger for the open ping. ActivityLifecycle-
                    // Callbacks is the primary one and normally beats this;
                    // both route through the same rate limit, so a double
                    // trigger is a no-op rather than a duplicate send.
                    Deeplinkly.onForeground()
                }
                result.success(true)
            }

            "generateLink" -> {
                val args = call.arguments as? Map<*, *>
                    ?: return result.success(invalid("Expected map"))
                val content = args["content"] as? Map<*, *>
                    ?: return result.success(invalid("Missing content"))
                val options = args["options"] as? Map<*, *>
                    ?: return result.success(invalid("Missing options"))

                // Flat-merged and passed straight through, so whatever Dart's
                // models produce reaches the backend unaltered.
                val payload = mutableMapOf<String, Any?>().apply {
                    putAll(content.mapKeys { it.key.toString() })
                    putAll(options.mapKeys { it.key.toString() })
                }

                Deeplinkly.generateLink(payload) { generated ->
                    // Absent rather than null, so the map Dart receives is the
                    // same shape it has always been: {success, url} on the way
                    // through, {success, error_code, error_message} on a
                    // failure.
                    result.success(
                        buildMap {
                            put("success", generated.success)
                            generated.url?.let { put("url", it) }
                            generated.errorCode?.let { put("error_code", it) }
                            generated.errorMessage?.let { put("error_message", it) }
                        }
                    )
                }
            }

            "disableTracking" -> {
                val disabled = call.argument<Boolean>("disabled") ?: false
                Deeplinkly.setTrackingEnabled(!disabled)
                result.success(true)
            }

            "setAttributionLevel" -> {
                val level = AttributionLevel.fromWireName(call.argument<String>("level"))
                if (level == null) {
                    result.success(false)
                } else {
                    result.success(Deeplinkly.setAttributionLevel(level))
                }
            }

            "getAttributionLevel" -> result.success(Deeplinkly.getAttributionLevel().wireName)

            // The pasteboard trio is iOS-only. Android's deferred channel is the
            // Play Install Referrer, which is signed by Google, needs no
            // permission and no user gesture, and works for every install -
            // the clipboard read it used to fall back to was strictly worse on
            // all three counts and is gone as of 1.9.0.
            //
            // These still answer rather than returning notImplemented, so the
            // Dart API stays total and cross-platform callers need no branch.
            // They answer honestly: nothing is enabled, so nothing can be read
            // and no banner can be shown.
            "setCheckPasteboardOnInstall" -> result.success(false)
            "willShowPasteboardBanner" -> result.success(false)
            "checkPasteboardNow" -> result.success(false)

            "setCustomUserId", "setUserId" -> {
                Deeplinkly.setUserId(call.argument<String>("user_id"))
                result.success(true)
            }

            "logEvent" -> {
                val eventName = call.argument<String>("event_name").orEmpty()
                val params = call.argument<Map<String, Any?>>("parameters") ?: emptyMap()
                Deeplinkly.logEvent(eventName, params) { ok -> result.success(ok) }
            }

            "setDebugMode" -> {
                Deeplinkly.setDebugMode(call.argument<Boolean>("enabled") ?: false)
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
        if (!Deeplinkly.isEnabled) {
            Logger.w("SDK disabled (missing API key). Skipping initialization.")
            return
        }

        // Drop any listener from a previous attach before adding this one, so a
        // re-attach cannot stack duplicates onto the binding.
        activityBinding?.removeOnNewIntentListener(newIntentListener)
        activityBinding = binding
        binding.addOnNewIntentListener(newIntentListener)

        if (isConfigChange) return

        Deeplinkly.onActivityLaunch(binding.activity)
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
        Deeplinkly.shutdown()
    }

    private companion object {
        /**
         * The answer to every call but `getDeeplinklyId` when the API key is
         * missing.
         *
         * Dart's blanket try/catch depends on this shape: methods declaring
         * other return types get a cast failure they swallow into a neutral
         * default, which is why a missing key looks like a quiet no-op rather
         * than a crash.
         */
        val DISABLED_ENVELOPE = mapOf(
            "success" to false,
            "error_code" to "SDK_DISABLED",
            "error_message" to "Deeplinkly SDK is disabled (missing API key).",
        )

        fun invalid(message: String) = mapOf(
            "success" to false,
            "error_code" to "INVALID",
            "error_message" to message,
        )
    }
}
