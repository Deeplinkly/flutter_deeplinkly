// GENERATED FILE — do not edit.
// Source: tool/signals.json
// Regenerate: dart run tool/gen_signals.dart
package com.deeplinkly.flutter_deeplinkly.privacy

/** The lowest [AttributionLevel] at which a signal still ships. */
enum class SignalTier(val rank: Int) {
    MINIMAL(0),
    REDUCED(1),
    FULL(2),
}

/** Where a signal comes from, and where the backend stores it. */
enum class SignalScope {
    /** Collected once per device and cached until the profile stamp changes. */
    STATIC,
    /** Collected fresh at send time. Never persisted in a queue. */
    DYNAMIC,
    /** Names the link or user being reported on, not the device. */
    IDENTITY,
}

data class SignalSpec(val tier: SignalTier, val scope: SignalScope)

/**
 * Every signal the SDK may send, and the level at which each is permitted.
 *
 * Fail-closed by construction: [allows] returns false for any key that is
 * not in [SPECS], at every level including FULL. A new signal that nobody
 * classified therefore never leaves the device, which is the failure mode
 * we want. The previous design was the opposite — REDUCED was a denylist,
 * so an unclassified key shipped to users who had asked us not to.
 */
object SignalCatalogue {
    /** Part of the static-profile stamp; bumping it forces a re-collect. */
    const val VERSION = 7

    val SPECS: Map<String, SignalSpec> = mapOf(
        "advertising_id" to SignalSpec(SignalTier.FULL, SignalScope.DYNAMIC),
        "android_id" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "android_reported_at" to SignalSpec(SignalTier.REDUCED, SignalScope.DYNAMIC),
        "app_build_number" to SignalSpec(SignalTier.MINIMAL, SignalScope.STATIC),
        "app_id" to SignalSpec(SignalTier.MINIMAL, SignalScope.STATIC),
        "app_set_id" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "app_set_id_scope" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "app_version" to SignalSpec(SignalTier.MINIMAL, SignalScope.STATIC),
        "attribution_level" to SignalSpec(SignalTier.MINIMAL, SignalScope.DYNAMIC),
        "brand" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "click_id" to SignalSpec(SignalTier.MINIMAL, SignalScope.IDENTITY),
        "code" to SignalSpec(SignalTier.MINIMAL, SignalScope.IDENTITY),
        "collected_at" to SignalSpec(SignalTier.MINIMAL, SignalScope.DYNAMIC),
        "connection_type" to SignalSpec(SignalTier.REDUCED, SignalScope.DYNAMIC),
        "cpu_abi" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "custom_user_id" to SignalSpec(SignalTier.MINIMAL, SignalScope.IDENTITY),
        "deeplinkly_device_id" to SignalSpec(SignalTier.MINIMAL, SignalScope.STATIC),
        "device" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "device_carrier" to SignalSpec(SignalTier.FULL, SignalScope.DYNAMIC),
        "device_class" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "device_model" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "environment" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "fbclid" to SignalSpec(SignalTier.REDUCED, SignalScope.IDENTITY),
        "first_app_version" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "first_open_at" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "gclid" to SignalSpec(SignalTier.REDUCED, SignalScope.IDENTITY),
        "google_play_instant" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "hardware_concurrency" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "install_begin_at" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "install_instance_id" to SignalSpec(SignalTier.MINIMAL, SignalScope.STATIC),
        "install_referrer" to SignalSpec(SignalTier.MINIMAL, SignalScope.IDENTITY),
        "installed_at" to SignalSpec(SignalTier.MINIMAL, SignalScope.STATIC),
        "installer_package" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "is_emulator" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "is_hardware_id_real" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "language" to SignalSpec(SignalTier.REDUCED, SignalScope.DYNAMIC),
        "last_opened_at" to SignalSpec(SignalTier.MINIMAL, SignalScope.DYNAMIC),
        "limit_ad_tracking" to SignalSpec(SignalTier.REDUCED, SignalScope.DYNAMIC),
        "local_ip" to SignalSpec(SignalTier.FULL, SignalScope.DYNAMIC),
        "locale" to SignalSpec(SignalTier.REDUCED, SignalScope.DYNAMIC),
        "manufacturer" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "os_build_id" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "os_version" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "pixel_ratio" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "platform" to SignalSpec(SignalTier.MINIMAL, SignalScope.STATIC),
        "product" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "referrer_click_at" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "referrer_install_version" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "region" to SignalSpec(SignalTier.REDUCED, SignalScope.DYNAMIC),
        "screen_dpi" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "screen_height" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "screen_width" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
        "sdk_int" to SignalSpec(SignalTier.REDUCED, SignalScope.STATIC),
        "sdk_version" to SignalSpec(SignalTier.MINIMAL, SignalScope.STATIC),
        "session_id" to SignalSpec(SignalTier.MINIMAL, SignalScope.DYNAMIC),
        "source" to SignalSpec(SignalTier.MINIMAL, SignalScope.IDENTITY),
        "static_profile_version" to SignalSpec(SignalTier.MINIMAL, SignalScope.STATIC),
        "timezone" to SignalSpec(SignalTier.REDUCED, SignalScope.DYNAMIC),
        "timezone_offset_min" to SignalSpec(SignalTier.REDUCED, SignalScope.DYNAMIC),
        "ttclid" to SignalSpec(SignalTier.REDUCED, SignalScope.IDENTITY),
        "ui_mode_night" to SignalSpec(SignalTier.REDUCED, SignalScope.DYNAMIC),
        "unidentified_device" to SignalSpec(SignalTier.REDUCED, SignalScope.DYNAMIC),
        "utm_campaign" to SignalSpec(SignalTier.REDUCED, SignalScope.IDENTITY),
        "utm_content" to SignalSpec(SignalTier.REDUCED, SignalScope.IDENTITY),
        "utm_medium" to SignalSpec(SignalTier.REDUCED, SignalScope.IDENTITY),
        "utm_source" to SignalSpec(SignalTier.REDUCED, SignalScope.IDENTITY),
        "utm_term" to SignalSpec(SignalTier.REDUCED, SignalScope.IDENTITY),
        "webview_user_agent" to SignalSpec(SignalTier.FULL, SignalScope.STATIC),
    )

    /** Whether [key] may be sent at [level]. Unknown keys are never sent. */
    fun allows(key: String, level: AttributionLevel): Boolean {
        val spec = SPECS[key] ?: return false
        return when (level) {
            AttributionLevel.FULL -> true
            AttributionLevel.REDUCED -> spec.tier.rank <= SignalTier.REDUCED.rank
            AttributionLevel.MINIMAL -> spec.tier.rank <= SignalTier.MINIMAL.rank
            AttributionLevel.NONE -> false
        }
    }

    fun keysFor(scope: SignalScope): Set<String> =
        SPECS.filterValues { it.scope == scope }.keys
}
