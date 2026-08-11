package com.deeplinkly.flutter_deeplinkly.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The signals that can change while the app stays installed.
 *
 * Collected fresh at send time, never cached and never persisted in a queue.
 * That is the whole point of the split: `DeepLinkQueue` can hold a pending
 * resolve for days, and replaying a snapshot of the advertising id, the
 * network, or the clock from whenever the link was first opened would report
 * stale device state as current.
 *
 * Must not be called on the main thread. `AdvertisingIdClient` throws outright
 * when it is, and the local-IP lookup does blocking network-interface I/O.
 */
object DynamicSignals {

    /**
     * The full device description: the cached static profile plus a fresh
     * dynamic sample, with the identifier rules applied.
     *
     * The one place both halves are visible at once, which is why the
     * advertising-id rule below lives here rather than in either collector.
     */
    fun assemble(staticProfile: Map<String, String?>): Map<String, String?> {
        val merged = staticProfile.toMutableMap()
        merged.putAll(collect(staticProfile))
        return applyIdentifierPolicy(merged)
    }

    /**
     * Enforces that no two conflicting device identifiers ship together.
     *
     * Google Play's Advertising ID policy: the advertising id "must not be
     * connected to persistent device identifiers (for example: SSAID, MAC
     * address, IMEI, etc.)". Both in one payload is exactly that connection,
     * so when an advertising id is present the SSAID is dropped.
     *
     * Applied here rather than at collection time — which is where Branch does
     * it — because our static profile is cached for the life of an app version
     * while the advertising id is re-read on every send. A user re-enabling ad
     * personalisation would otherwise keep shipping the SSAID the profile was
     * collected with, alongside a now-present ad id.
     *
     * Internal rather than private so the rule can be tested on its own: on a
     * test runner there is no Play Services and therefore never a real ad id.
     */
    internal fun applyIdentifierPolicy(
        signals: Map<String, String?>,
    ): Map<String, String?> {
        if (signals["advertising_id"].isNullOrBlank()) return signals
        return signals.filterKeys { it != "android_id" }
    }

    /**
     * @param staticProfile the cached profile, used only to derive
     *   `unidentified_device` — which is a statement about the payload as a
     *   whole and so cannot be decided from the dynamic half alone.
     */
    fun collect(staticProfile: Map<String, String?> = emptyMap()): Map<String, String?> {
        val ctx = DeeplinklyContext.app
        val out = mutableMapOf<String, String?>()
        val now = System.currentTimeMillis()

        // --- advertising ---
        // play-services-ads-identifier is compileOnly: the host app opts in by
        // adding it, because its manifest declares the AD_ID permission and we
        // will not add that to somebody else's app on their behalf. The class
        // check has to come first — a missing class raises NoClassDefFoundError,
        // which is an Error rather than an Exception, so the catch below would
        // not stop it from crashing the host app.
        var advertisingId: String? = null
        if (Dependencies.classExists(Dependencies.ADVERTISING_ID_CLIENT)) {
            DeeplinklyUtils.guardTracking {
                try {
                    val info = AdvertisingIdClient.getAdvertisingIdInfo(ctx)
                    out["limit_ad_tracking"] = info.isLimitAdTrackingEnabled.toString()
                    if (!info.isLimitAdTrackingEnabled) {
                        advertisingId = info.id
                    }
                } catch (_: Exception) {
                    // Play Services missing or wedged, or called from the main
                    // thread. Neither is worth failing the whole send over.
                }
            }
        }
        out["advertising_id"] = advertisingId

        // True when we have no durable identifier for this device at all, which
        // is a data-quality fact the backend would otherwise have to infer from
        // the absence of keys it cannot distinguish from a level downgrade.
        out["unidentified_device"] =
            (advertisingId.isNullOrBlank() && staticProfile["android_id"].isNullOrBlank()).toString()

        // --- locale and clock ---
        val config = ctx.resources.configuration
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            config.locales[0] else @Suppress("DEPRECATION") config.locale
        val zone = TimeZone.getDefault()

        out["locale"] = locale.toLanguageTag()
        out["language"] = locale.language
        out["region"] = locale.country
        out["timezone"] = zone.id
        out["timezone_offset_min"] = (zone.getOffset(now) / 60_000).toString()
        out["last_opened_at"] = isoUtc(now)
        // Also stamped on every event, so a sample and the events from the same
        // visit can be joined server-side.
        out["session_id"] = SessionManager.currentSessionId(now)

        // --- environment ---
        out["connection_type"] = connectionType()
        out["ui_mode_night"] = isNightMode(config).toString()
        out["device_carrier"] = carrierName()
        out["local_ip"] = localIpAddress()

        return out
    }

    /**
     * The current connection type, when the host app has the permission for it.
     *
     * The SDK does not declare ACCESS_NETWORK_STATE: it would land in every
     * host app's manifest and Play listing for one reduced-tier reporting
     * field. Checked at runtime instead, so an app that already holds the
     * permission reports this and an app that does not simply omits it.
     */
    private fun connectionType(): String? {
        val ctx = DeeplinklyContext.app
        if (ctx.checkCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return runCatching {
            // getActiveNetwork and getNetworkCapabilities are API 23, and
            // minSdk here is 21. Without this guard the call throws
            // NoSuchMethodError on 21-22 — swallowed by the runCatching, so
            // the field would simply never appear on those devices rather
            // than failing loudly.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return "none"
                val caps = cm.getNetworkCapabilities(network) ?: return "none"
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "other"
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo ?: return "none"
                @Suppress("DEPRECATION")
                when {
                    !info.isConnected -> "none"
                    info.type == ConnectivityManager.TYPE_WIFI -> "wifi"
                    info.type == ConnectivityManager.TYPE_MOBILE -> "cellular"
                    info.type == ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                    else -> "other"
                }
            }
        }.getOrNull()
    }

    private fun isNightMode(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun carrierName(): String? = runCatching {
        val tm = DeeplinklyContext.app
            .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkOperatorName?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * The first non-loopback IPv4 address on any up interface.
     *
     * Android 11+ restricts what `getNetworkInterfaces` returns, and on a
     * device with no network this is simply absent — both are fine, the field
     * is best-effort. Classified `full` because a LAN address is a strong
     * correlator and, under GDPR, personal data.
     */
    private fun localIpAddress(): String? = runCatching {
        for (nic in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (nic.isLoopback || !nic.isUp) continue
            for (address in Collections.list(nic.inetAddresses)) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return@runCatching address.hostAddress
                }
            }
        }
        null
    }.getOrNull()

    private fun isoUtc(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(millis))
    }
}
