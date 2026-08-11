package com.deeplinkly.flutter_deeplinkly.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.deeplinkly.flutter_deeplinkly.attribution.EnrichmentSender
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reports a device sample when the app comes to the foreground.
 *
 * Registered as [Application.ActivityLifecycleCallbacks] rather than driven
 * from Dart. The plugin already exposes an `onLifecycleChange` method channel,
 * but that only fires if the host app wires a `WidgetsBindingObserver` and
 * forwards it — which makes the ping opt-in by accident, and late. This fires
 * on the 0→1 started-activity transition, before Flutter has attached.
 *
 * Rate-limited hard. `TenantUser` is rewritten on every open and is documented
 * as the hottest write path in the product; an unthrottled ping would multiply
 * that by the number of foreground transitions per session, which on a
 * chat-style app is dozens.
 */
object AppOpenReporter {
    private const val KEY_LAST_PING_AT = "dl_last_open_ping_at"

    /** Matches the session window: at most one ping per session. */
    private const val MIN_PING_INTERVAL_MS = SessionManager.SESSION_WINDOW_MS

    const val SOURCE = "app_open"

    private val startedActivities = AtomicInteger(0)
    private var registered = false

    /** Idempotent — the plugin can attach to an engine more than once. */
    fun register(application: Application, apiKey: String) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(Callbacks(apiKey))
        Logger.d("AppOpenReporter registered")
    }

    /**
     * Reports an open, unless one was already reported inside the window.
     *
     * Safe to call from several places — the lifecycle callback and Dart's
     * `onLifecycleChange` both route here, and the rate limit is what makes
     * that harmless rather than lucky.
     */
    fun report(apiKey: String, now: Long = System.currentTimeMillis()) {
        if (apiKey.isBlank()) return
        if (DeeplinklyUtils.isTrackingDisabled()) return
        if (!shouldPing(now)) return

        Prefs.of().edit().putLong(KEY_LAST_PING_AT, now).apply()
        SdkRuntime.ioLaunch {
            try {
                // No attribution: an open is a lifecycle moment, not a link.
                // EnrichmentSender lets lifecycle sources through its
                // attribution gate for exactly this reason.
                EnrichmentSender.sendOnce(emptyMap(), SOURCE, apiKey)
            } catch (t: Throwable) {
                Logger.e("AppOpenReporter: send failed", t)
            }
        }
    }

    internal fun shouldPing(now: Long = System.currentTimeMillis()): Boolean {
        val last = Prefs.of().getLong(KEY_LAST_PING_AT, 0L)
        // 0 means "never pinged", not "pinged at the epoch". Comparing it as a
        // timestamp happens to work against a real wall clock only because that
        // number is large; say what is meant instead.
        if (last == 0L) return true
        return now - last >= MIN_PING_INTERVAL_MS
    }

    private class Callbacks(private val apiKey: String) :
        Application.ActivityLifecycleCallbacks {

        override fun onActivityStarted(activity: Activity) {
            // 0→1 only. Rotating a device or moving between two activities of
            // the same app never drops the count to zero, so neither counts as
            // an open.
            if (startedActivities.getAndIncrement() == 0) {
                report(apiKey)
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities.updateAndGet { if (it > 0) it - 1 else 0 }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
