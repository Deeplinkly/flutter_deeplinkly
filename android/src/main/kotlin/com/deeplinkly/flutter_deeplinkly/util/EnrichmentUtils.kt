package com.deeplinkly.flutter_deeplinkly.util

import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import java.util.TimeZone

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
            platform, osVersion, deviceModel,
            screenWidth?.toString(), screenHeight?.toString(),
            pixelRatio?.toString(), language, timezone
        ).joinToString("|")
        return fingerprintString.hashCode().toString(16)
    }

    fun collect(): Map<String, String?> {
        val context = DeeplinklyContext.app
        val pm = context.packageManager
        val pkg = context.packageName

        val out = mutableMapOf<String, String?>()
        val prefs = Prefs.of()

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
        } catch (e: Exception) { null }

        val versionCode = versionInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString()
            else @Suppress("DEPRECATION") it.versionCode.toString()
        }

        val config = context.resources.configuration
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) config.locales[0]
            else @Suppress("DEPRECATION") config.locale

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { pm.getInstallSourceInfo(pkg).installingPackageName }.getOrNull()
        } else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)

        out += mapOf(
            "android_id" to androidId,
            "device_id" to androidId,
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (e: Exception) { null }

        val installTimeMillis = packageInfo?.firstInstallTime ?: currentTime
        base["installed_at"] = formatToIso8601(installTimeMillis)
        base["user_agent"] =
            "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"

        val language = context.resources.configuration.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) it.locales[0].language else it.locale.language
        }
        val timezone = java.util.TimeZone.getDefault().id
        val osVersion = android.os.Build.VERSION.RELEASE ?: ""
        val deviceModel = android.os.Build.MODEL ?: ""

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
