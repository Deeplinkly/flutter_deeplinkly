package com.deeplinkly.flutter_deeplinkly.privacy

import android.content.pm.PackageManager
import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.Prefs

/**
 * How much the SDK is allowed to report about a device.
 *
 * A single on/off switch ([TrackingPreferences]) is too blunt for consent flows
 * that need a middle ground: an app under GDPR may have consent to attribute an
 * install while having none to describe the hardware it happened on.
 *
 * Ordering matters — each level is a strict subset of the one above it. Which
 * keys each level permits is not decided here: it lives in [SignalCatalogue],
 * generated from `tool/signals.json` so that this file, iOS's
 * `AttributionLevel.swift` and the backend cannot disagree about what a given
 * consent choice means. They previously did.
 */
enum class AttributionLevel(val wireName: String) {
    /** Everything the SDK collects. The default, and the pre-1.9.0 behaviour. */
    FULL("full"),

    /**
     * Drops every signal classified [SignalTier.FULL] — the advertising ID, the
     * Android ID, and the high-entropy hardware signals that only exist to
     * support probabilistic matching. Keeps the coarse context a marketer
     * actually reads.
     */
    REDUCED("reduced"),

    /**
     * Only signals classified [SignalTier.MINIMAL]: who the install is, which
     * app build, and the link being reported on. Nothing describing the device.
     */
    MINIMAL("minimal"),

    /**
     * No enrichment is sent at all. Links still resolve and still deliver —
     * this suppresses reporting, not functionality.
     */
    NONE("none");

    /** Whether any enrichment may leave the device. */
    val allowsEnrichment: Boolean get() = this != NONE

    /**
     * Whether the advertising ID may be reported. Only [FULL].
     *
     * Named for what it gates. It used to be `allowsFingerprint`, from when it
     * also decided whether a device-signal block rode along on `/resolve`; that
     * block is gone, and the cached-ad-id re-attach in `EnrichmentSender` is
     * the only caller left.
     */
    val allowsAdvertisingId: Boolean get() = this == FULL

    /**
     * Strips a collected payload down to what this level permits.
     *
     * Delegates every decision to [SignalCatalogue], which is generated from
     * `tool/signals.json` and shared with iOS and the backend. This used to be
     * a denylist for [REDUCED] and an allowlist for [MINIMAL], which meant a
     * newly added signal shipped at REDUCED unless someone remembered to
     * classify it — the wrong default for the one function whose job is to not
     * leak. The catalogue is fail-closed: an unclassified key is dropped even
     * at [FULL].
     */
    fun <V> filter(payload: Map<String, V>): Map<String, V> =
        payload.filterKeys { SignalCatalogue.allows(it, this) }

    companion object {
        private const val STORAGE_KEY = "dl_attribution_level"
        private const val MANIFEST_KEY = "com.deeplinkly.sdk.attribution_level"

        /**
         * The level in force. Tracking being disabled is absolute and collapses
         * to [NONE] regardless of what was set here.
         */
        val current: AttributionLevel
            get() {
                if (TrackingPreferences.isTrackingDisabled()) return NONE

                Prefs.of().getString(STORAGE_KEY, null)?.let { stored ->
                    fromWireName(stored)?.let { return it }
                }

                // Host apps that want to start restricted need to say so before
                // any code of theirs runs — the first enrichment can be sent
                // during plugin attachment, long before Dart could call set().
                return manifestDefault() ?: FULL
            }

        fun set(level: AttributionLevel) {
            Prefs.of().edit().putString(STORAGE_KEY, level.wireName).apply()
            Logger.d("Attribution level set to ${level.wireName}")
        }

        fun fromWireName(name: String?): AttributionLevel? =
            values().firstOrNull { it.wireName.equals(name?.trim(), ignoreCase = true) }

        private fun manifestDefault(): AttributionLevel? = try {
            val ctx = DeeplinklyContext.app
            val info = ctx.packageManager.getApplicationInfo(
                ctx.packageName, PackageManager.GET_META_DATA
            )
            fromWireName(info.metaData?.getString(MANIFEST_KEY))
        } catch (_: Exception) {
            null
        }
    }
}
