package com.deeplinkly.flutter_deeplinkly.handlers

import com.android.installreferrer.api.ReferrerDetails
import com.deeplinkly.flutter_deeplinkly.core.DeviceProfile
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The Play install-referrer timings, latched once per install.
 *
 * These describe the install rather than any one send — when the ad was
 * clicked, when the Play install began, whether it came through Instant Apps —
 * so they belong in the static profile rather than being re-sent with every
 * enrichment. The referrer is only readable once, early, and only on the
 * install-referrer path, so they are stored here when that path runs and folded
 * into the profile on every later collection.
 *
 * Click→install latency comes free with them, which is the thing a marketer
 * actually reads off this data.
 */
object InstallReferrerTimings {
    private const val KEY_REFERRER_CLICK_AT = "dl_referrer_click_at"
    private const val KEY_INSTALL_BEGIN_AT = "dl_install_begin_at"
    private const val KEY_GOOGLE_PLAY_INSTANT = "dl_google_play_instant"
    private const val KEY_REFERRER_INSTALL_VERSION = "dl_referrer_install_version"

    /** Stores the timings and drops the cached profile so they get picked up. */
    fun record(details: ReferrerDetails) {
        val editor = Prefs.of().edit()

        // The Play API reports seconds; everything on the wire is ISO-8601 UTC.
        details.referrerClickTimestampSeconds
            .takeIf { it > 0 }
            ?.let { editor.putString(KEY_REFERRER_CLICK_AT, isoUtc(it * 1000)) }
        details.installBeginTimestampSeconds
            .takeIf { it > 0 }
            ?.let { editor.putString(KEY_INSTALL_BEGIN_AT, isoUtc(it * 1000)) }

        editor.putBoolean(KEY_GOOGLE_PLAY_INSTANT, runCatching {
            details.googlePlayInstantParam
        }.getOrDefault(false))

        runCatching { details.installVersion }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { editor.putString(KEY_REFERRER_INSTALL_VERSION, it) }

        editor.apply()

        // The profile may already have been collected without these — the
        // referrer arrives asynchronously, often after the first enrichment.
        DeviceProfile.invalidate()
        Logger.d("Recorded install referrer timings")
    }

    /** What [DeviceProfile] folds into every collection. Empty until recorded. */
    fun stored(): Map<String, String?> {
        val prefs = Prefs.of()
        if (!prefs.contains(KEY_REFERRER_CLICK_AT) && !prefs.contains(KEY_INSTALL_BEGIN_AT)) {
            return emptyMap()
        }
        val out = mutableMapOf<String, String?>()
        prefs.getString(KEY_REFERRER_CLICK_AT, null)?.let { out["referrer_click_at"] = it }
        prefs.getString(KEY_INSTALL_BEGIN_AT, null)?.let { out["install_begin_at"] = it }
        prefs.getString(KEY_REFERRER_INSTALL_VERSION, null)
            ?.let { out["referrer_install_version"] = it }
        out["google_play_instant"] = prefs.getBoolean(KEY_GOOGLE_PLAY_INSTANT, false).toString()
        return out
    }

    private fun isoUtc(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(millis))
    }
}
