// FILE: com/deeplinkly/flutter_deeplinkly/core/DeeplinklyUtils.kt
package com.deeplinkly.flutter_deeplinkly.core

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.net.toUri

object DeeplinklyUtils {
    private const val PREFS_NAME = "deeplinkly_prefs"
    private val prefs: SharedPreferences
        get() = DeeplinklyContext.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markNewSession() {
        prefs.edit()
            .putString("dl_session_id", UUID.randomUUID().toString())
            .putBoolean("dl_sent_enrichment_this_session", false)
            .apply()
    }

    fun getSessionId(): String? = prefs.getString("dl_session_id", null)
    fun wasSessionEnrichmentSent(): Boolean = prefs.getBoolean("dl_sent_enrichment_this_session", false)
    fun markSessionEnrichmentSent() = prefs.edit().putBoolean("dl_sent_enrichment_this_session", true).apply()

    fun getCustomUserId(): String? = prefs.getString("dl_custom_user_id", null)
    fun setCustomUserId(id: String?) = prefs.edit().putString("dl_custom_user_id", id).apply()

    fun isTrackingDisabled(): Boolean = prefs.getBoolean("tracking_disabled", false)
    fun setTrackingDisabled(disabled: Boolean) {
        prefs.edit().putBoolean("tracking_disabled", disabled).apply()
    }
    inline fun guardTracking(block: () -> Unit) {
        if (!isTrackingDisabled()) block()
    }

    private const val DEVICE_ID_KEY = "deeplinkly_device_id"
    fun getOrCreateDeviceId(): String {
        return prefs.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(DEVICE_ID_KEY, it).apply()
        }
    }

    fun collectEnrichment(): Map<String, String?> {
        val ctx = DeeplinklyContext.app
        val out = mutableMapOf<String, String?>()
        out["deeplinkly_device_id"] = getOrCreateDeviceId()
        out["custom_user_id"] = getCustomUserId()
        out.putAll(collectFingerprint())

        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val versionInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            else
                @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
        } catch (_: Exception) {
            null
        }

        val versionCode = versionInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString()
            else @Suppress("DEPRECATION") it.versionCode.toString()
        }

        guardTracking {
            try {
                val info = com.google.android.gms.ads.identifier.AdvertisingIdClient.getAdvertisingIdInfo(ctx)
                if (!info.isLimitAdTrackingEnabled) {
                    out["advertising_id"] = info.id
                    prefs.edit().putString("advertising_id", info.id).apply()
                }
            } catch (_: Exception) {
            }
        }

        val config = ctx.resources.configuration
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            config.locales[0] else @Suppress("DEPRECATION") config.locale

        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            runCatching { pm.getInstallSourceInfo(pkg).installingPackageName }.getOrNull()
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)

        out += mapOf(
            "app_id" to pkg,
            "android_id" to androidId,
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

    private fun collectFingerprint(): Map<String, String?> {
        val ctx = DeeplinklyContext.app
        val metrics: DisplayMetrics = ctx.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val pixelRatio = metrics.density
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val base = mutableMapOf(
            "screen_width" to screenWidth.toString(),
            "screen_height" to screenHeight.toString(),
            "pixel_ratio" to pixelRatio.toString(),
            "last_opened_at" to sdf.format(Date(now)),
            "hardware_fingerprint" to makeFingerprint(screenWidth, screenHeight, pixelRatio)
        )
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            else
                @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        } catch (_: Exception) {
            null
        }
        val installTime = pkgInfo?.firstInstallTime ?: now
        base["installed_at"] = sdf.format(Date(installTime))
        return base
    }

    private fun makeFingerprint(w: Int, h: Int, pr: Float): String {
        val parts = listOf(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, "$w", "$h", "$pr")
        return parts.joinToString("|").hashCode().toString(16)
    }

    fun getAppLinkHosts(): List<String> {
        val ctx = DeeplinklyContext.app
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            data = Uri.parse("https://example.com")
        }
        val infos = pm.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
        val hosts = mutableSetOf<String>()
        for (info in infos) {
            if (info.activityInfo.packageName != ctx.packageName) continue
            val filter = info.filter ?: continue
            val isAppLink = filter.hasAction(android.content.Intent.ACTION_VIEW)
                    && filter.hasCategory(android.content.Intent.CATEGORY_DEFAULT)
                    && filter.hasCategory(android.content.Intent.CATEGORY_BROWSABLE)
            if (!isAppLink) continue
            for (i in 0 until filter.countDataSchemes()) {
                if (filter.getDataScheme(i) != "https") continue
                for (j in 0 until filter.countDataAuthorities()) {
                    val host = filter.getDataAuthority(j)?.host
                    if (!host.isNullOrBlank()) hosts.add(host)
                }
            }
        }
        return hosts.toList()
    }
}
