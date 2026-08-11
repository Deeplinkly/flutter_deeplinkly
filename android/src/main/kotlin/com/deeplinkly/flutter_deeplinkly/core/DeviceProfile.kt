package com.deeplinkly.flutter_deeplinkly.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.webkit.WebSettings
import com.deeplinkly.flutter_deeplinkly.handlers.InstallReferrerTimings
import com.deeplinkly.flutter_deeplinkly.privacy.SignalCatalogue
import com.google.android.gms.appset.AppSet
import com.google.android.gms.appset.AppSetIdInfo
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The part of the device description that does not change while the app is
 * installed: hardware, app build, install provenance.
 *
 * Collected once and cached in SharedPreferences. Before this existed,
 * `collectEnrichment()` was re-run in full at five separate call sites per
 * launch, each one paying for a PackageManager lookup, a `Settings.Secure`
 * content-resolver query, and an AdvertisingIdClient binder round-trip.
 *
 * Anything that can change between two sends of the same install — the
 * advertising id, connection type, locale, the clock — is *not* here. It
 * belongs in the dynamic block, collected fresh at send time.
 */
object DeviceProfile {
    private const val KEY_PROFILE = "dl_static_profile"
    private const val KEY_STAMP = "dl_static_profile_stamp"
    private const val KEY_INSTALL_INSTANCE_ID = "dl_install_instance_id"
    private const val KEY_FIRST_APP_VERSION = "dl_first_app_version"
    private const val KEY_FIRST_OPEN_AT = "dl_first_open_at"

    /**
     * Whether the App Set ID lookup has been tried for the current stamp.
     *
     * Without this, a transient Play Services failure would be cached as an
     * absent value for the life of the install: the profile would be stored
     * successfully, minus one key, and never collected again. The flag lets a
     * failed lookup retry on the next launch while everything else stays
     * cached.
     */
    private const val KEY_APPSET_ATTEMPTED = "dl_appset_attempted"

    private const val APPSET_TIMEOUT_SECONDS = 2L

    /** Emulator-only ANDROID_ID, returned by a whole generation of AVDs. */
    private const val KNOWN_BAD_ANDROID_ID = "9774d56d682e549c"

    @Volatile
    private var cached: Map<String, String?>? = null

    private val lock = ReentrantLock()

    /**
     * The cached profile, collecting it first if the cache is cold or stale.
     *
     * Single-flight: the volatile read covers the warm case without taking the
     * lock, and concurrent cold callers wait on one collection rather than each
     * doing their own.
     *
     * Blocking rather than `suspend` deliberately. One caller is the Play
     * install-referrer library's binder callback thread, which is not a
     * coroutine, and making this suspend would push a coroutine launch into a
     * callback that has to complete before the referrer client is closed. Every
     * caller is already off the main thread — the same invariant that
     * AdvertisingIdClient imposes — so blocking here is free.
     */
    fun get(): Map<String, String?> {
        cached?.let { return it }
        return lock.withLock {
            // Re-check under the lock: another caller may have collected while
            // this one waited.
            cached?.let { return@withLock it }

            val stamp = currentStamp()
            val profile = readStored(stamp) ?: collect(stamp)
            cached = profile
            profile
        }
    }

    /**
     * Drops the cache so the next [get] re-collects.
     *
     * Callers should not normally need this — a changed stamp invalidates on
     * its own. It exists for tests and for the install-referrer handler, which
     * learns `referrer_click_at` and friends after the first profile was
     * already built.
     */
    fun invalidate() {
        cached = null
        Prefs.of().edit().remove(KEY_PROFILE).remove(KEY_STAMP).apply()
    }

    /** The wire value of `static_profile_version` for the current device. */
    fun stampFor(profile: Map<String, String?>): String? =
        profile["static_profile_version"]

    // --- the stamp ---------------------------------------------------------

    /**
     * What the cached profile is keyed on. A change in any component means the
     * cached values may be wrong, so the profile is collected again.
     *
     * `deviceIdentity` (Build.FINGERPRINT) is the load-bearing one and must not
     * be "simplified" away: Android Auto Backup restores SharedPreferences onto
     * a *different physical device*. Without it, a restored install would keep
     * reporting the old phone's android_id, screen geometry and model for the
     * life of the install.
     *
     * `catalogueVersion` matters for the opposite reason: a signal added in an
     * SDK release must get collected on installs whose app version never
     * changed.
     */
    private fun currentStamp(): String {
        val ctx = DeeplinklyContext.app
        val pkgInfo = packageInfo()
        val parts = listOf(
            SdkInfo.VERSION,
            SignalCatalogue.VERSION.toString(),
            pkgInfo?.versionName ?: "",
            versionCode(pkgInfo) ?: "",
            Build.FINGERPRINT ?: "",
            Build.VERSION.RELEASE ?: "",
        )
        return hash(parts.joinToString("|"))
    }

    /** FNV-1a, 64-bit. Not a security boundary — just a short stable key. */
    private fun hash(value: String): String {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037 unsigned
        for (byte in value.toByteArray()) {
            h = h xor (byte.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }

    // --- storage -----------------------------------------------------------

    private fun readStored(stamp: String): Map<String, String?>? {
        val prefs = Prefs.of()
        if (prefs.getString(KEY_STAMP, null) != stamp) return null
        val raw = prefs.getString(KEY_PROFILE, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val out = mutableMapOf<String, String?>()
            for (key in json.keys()) {
                out[key] = if (json.isNull(key)) null else json.getString(key)
            }
            // A profile stored before the App Set ID lookup succeeded is worth
            // keeping, but the lookup itself should be retried.
            if (!prefs.getBoolean(KEY_APPSET_ATTEMPTED, false)) return null
            out
        } catch (e: Exception) {
            Logger.w("Discarding unreadable static profile: ${e.message}")
            null
        }
    }

    private fun persist(profile: Map<String, String?>, stamp: String) {
        val json = JSONObject()
        for ((key, value) in profile) {
            if (value != null) json.put(key, value)
        }
        Prefs.of().edit()
            .putString(KEY_PROFILE, json.toString())
            .putString(KEY_STAMP, stamp)
            .apply()
    }

    // --- collection --------------------------------------------------------

    private fun collect(stamp: String): Map<String, String?> {
        val ctx = DeeplinklyContext.app
        val prefs = Prefs.of()
        val out = mutableMapOf<String, String?>()

        out["deeplinkly_device_id"] = DeeplinklyUtils.getOrCreateDeviceId()
        out["install_instance_id"] = installInstanceId()
        out["platform"] = SdkInfo.PLATFORM
        out["sdk_version"] = SdkInfo.VERSION
        out["static_profile_version"] = stamp

        // --- app build ---
        val pkgInfo = packageInfo()
        val appVersion = pkgInfo?.versionName
        out["app_id"] = ctx.packageName
        out["app_version"] = appVersion
        out["app_build_number"] = versionCode(pkgInfo)
        out["installed_at"] = pkgInfo?.firstInstallTime?.let(::isoUtc)
        out["installer_package"] = installerPackage()
        out["environment"] = environment()

        // First-seen values, latched. `installed_at` is the store install;
        // `first_open_at` is the first time our code ran, which is what an
        // install cohort actually means.
        out["first_app_version"] = latch(KEY_FIRST_APP_VERSION, appVersion)
        out["first_open_at"] = latch(KEY_FIRST_OPEN_AT, isoUtc(System.currentTimeMillis()))

        // --- os ---
        out["os_version"] = Build.VERSION.RELEASE
        out["sdk_int"] = Build.VERSION.SDK_INT.toString()
        out["os_build_id"] = Build.ID

        // --- hardware ---
        out["manufacturer"] = Build.MANUFACTURER
        out["brand"] = Build.BRAND
        out["device"] = Build.DEVICE
        out["product"] = Build.PRODUCT
        out["device_model"] = Build.MODEL
        out["device_class"] = deviceClass()
        out["cpu_abi"] = Build.SUPPORTED_ABIS?.firstOrNull()
        out["hardware_concurrency"] = Runtime.getRuntime().availableProcessors().toString()
        out["is_emulator"] = isEmulator().toString()

        val metrics = screenMetrics()
        out["screen_width"] = metrics.widthPixels.toString()
        out["screen_height"] = metrics.heightPixels.toString()
        out["screen_dpi"] = metrics.densityDpi.toString()
        out["pixel_ratio"] = metrics.density.toString()

        // --- identifiers ---
        val androidId = androidId()
        out["android_id"] = androidId
        out["is_hardware_id_real"] =
            (!androidId.isNullOrBlank() && androidId != KNOWN_BAD_ANDROID_ID).toString()

        // The one lookup here that leaves the process. Marked attempted either
        // way so a failure retries next launch instead of caching a hole.
        val appSet = appSetId()
        prefs.edit().putBoolean(KEY_APPSET_ATTEMPTED, true).apply()
        if (appSet != null) {
            out["app_set_id"] = appSet.id
            out["app_set_id_scope"] = when (appSet.scope) {
                AppSetIdInfo.SCOPE_DEVELOPER -> "developer"
                else -> "app"
            }
        }

        out["webview_user_agent"] = webViewUserAgent()

        // Absent until the install-referrer path has run, which is normal: the
        // referrer arrives asynchronously and only on a Play install.
        out.putAll(InstallReferrerTimings.stored())

        persist(out, stamp)
        Logger.d("Collected static device profile (${out.size} signals, stamp $stamp)")
        return out
    }

    // --- individual signals ------------------------------------------------

    private fun packageInfo() = try {
        val ctx = DeeplinklyContext.app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getPackageInfo(
                ctx.packageName, PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }
    } catch (_: Exception) {
        null
    }

    private fun versionCode(info: android.content.pm.PackageInfo?): String? = info?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString()
        else @Suppress("DEPRECATION") it.versionCode.toString()
    }

    private fun installerPackage(): String? {
        val ctx = DeeplinklyContext.app
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { ctx.packageManager.getInstallerPackageName(ctx.packageName) }.getOrNull()
        }
    }

    private fun environment(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { DeeplinklyContext.app.packageManager.isInstantApp }.getOrDefault(false)
        ) "INSTANT_APP" else "FULL_APP"

    private fun androidId(): String? = runCatching {
        Settings.Secure.getString(
            DeeplinklyContext.app.contentResolver, Settings.Secure.ANDROID_ID
        )
    }.getOrNull()

    private fun deviceClass(): String {
        val ctx = DeeplinklyContext.app
        val mode = runCatching {
            (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
        }.getOrNull()
        return when (mode) {
            Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
            Configuration.UI_MODE_TYPE_WATCH -> "watch"
            Configuration.UI_MODE_TYPE_CAR -> "car"
            Configuration.UI_MODE_TYPE_DESK -> "desk"
            Configuration.UI_MODE_TYPE_APPLIANCE -> "appliance"
            else -> {
                val smallest = ctx.resources.configuration.smallestScreenWidthDp
                if (smallest >= 600) "tablet" else "phone"
            }
        }
    }

    /**
     * Real device geometry, not the app's window.
     *
     * This used to read `resources.displayMetrics`, which is the *window* the
     * app happens to occupy — so a split-screen or freeform launch reported a
     * different "device" than the same phone in fullscreen, and a foldable
     * reported two different devices depending on its hinge.
     */
    private fun screenMetrics(): DisplayMetrics {
        val ctx = DeeplinklyContext.app
        val metrics = DisplayMetrics()
        val fallback = ctx.resources.displayMetrics
        metrics.density = fallback.density
        metrics.densityDpi = fallback.densityDpi

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val bounds = runCatching { wm?.maximumWindowMetrics?.bounds }.getOrNull()
            if (bounds != null) {
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
                return metrics
            }
        }
        metrics.widthPixels = fallback.widthPixels
        metrics.heightPixels = fallback.heightPixels
        return metrics
    }

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val hardware = Build.HARDWARE ?: ""
        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            model.contains("google_sdk", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            hardware in setOf("goldfish", "ranchu", "vbox86") ||
            Build.PRODUCT == "sdk" ||
            Build.BRAND.startsWith("generic")
    }

    /**
     * Blocks on the Play Services task with a short timeout.
     *
     * Already on a background thread — [get] is a suspend function reached from
     * `Dispatchers.IO` — and the timeout matters because the App Set ID is not
     * worth delaying an enrichment send for on a device where Play Services is
     * wedged.
     */
    private fun appSetId(): AppSetIdInfo? = runCatching {
        Tasks.await(
            AppSet.getClient(DeeplinklyContext.app).appSetIdInfo,
            APPSET_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
    }.getOrNull()

    /**
     * The WebView user agent.
     *
     * Must run on the main thread and initialises the WebView provider, which
     * costs tens of milliseconds on first call and throws outright on devices
     * with no WebView at all. Collecting it exactly once, here, is the reason
     * it is a static signal rather than a dynamic one — it only changes when
     * the WebView or OS version does, and both are in the stamp.
     */
    private fun webViewUserAgent(): String? = runCatching {
        WebSettings.getDefaultUserAgent(DeeplinklyContext.app)
    }.getOrNull()

    // --- latches -----------------------------------------------------------

    /** Stores [value] under [key] the first time only, and returns what is stored. */
    private fun latch(key: String, value: String?): String? {
        val prefs = Prefs.of()
        prefs.getString(key, null)?.let { return it }
        if (value == null) return null
        prefs.edit().putString(key, value).apply()
        return value
    }

    private fun installInstanceId(): String {
        val prefs = Prefs.of()
        prefs.getString(KEY_INSTALL_INSTANCE_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_INSTANCE_ID, fresh).apply()
        return fresh
    }

    private fun isoUtc(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(millis))
    }
}
