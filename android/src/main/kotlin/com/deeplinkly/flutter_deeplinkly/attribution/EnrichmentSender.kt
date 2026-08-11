// FILE: com/deeplinkly/flutter_deeplinkly/attribution/EnrichmentSender.kt
package com.deeplinkly.flutter_deeplinkly.attribution

import com.deeplinkly.flutter_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.flutter_deeplinkly.core.DeviceProfile
import com.deeplinkly.flutter_deeplinkly.core.DynamicSignals
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.flutter_deeplinkly.privacy.AttributionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The one place an enrichment payload is assembled.
 *
 * Callers pass only the link identity — which click, which campaign, which
 * source. The device description is added here, at send time, from the cached
 * static profile plus a fresh dynamic sample. Nothing device-shaped is carried
 * in from a caller or a queue, so a payload replayed from storage days later
 * still describes the device as it is now rather than as it was.
 */
object EnrichmentSender {

    /** Sources that describe a lifecycle moment rather than a link. */
    private val LIFECYCLE_SOURCES = setOf("app_start", "app_open")

    /** Any one of these makes a payload worth sending on its own. */
    private val ATTRIBUTION_KEYS = listOf(
        "click_id", "code", "utm_source", "utm_medium", "utm_campaign",
        "gclid", "fbclid", "ttclid",
    )

    /**
     * @param attributionData link identity only: click_id/code, source, the
     *   UTMs, the ad-click ids, the install referrer. Device signals passed
     *   here would be overwritten.
     */
    suspend fun sendOnce(
        attributionData: Map<String, String?>,
        source: String,
        apiKey: String,
    ) = withContext(Dispatchers.IO) {
        DeeplinklyUtils.guardTracking {
            val level = AttributionLevel.current
            if (!level.allowsEnrichment) {
                Logger.d("Attribution level is none; not sending enrichment.")
                return@guardTracking
            }

            val payload = DynamicSignals.assemble(DeviceProfile.get()).toMutableMap()
            payload["custom_user_id"] = DeeplinklyUtils.getCustomUserId()
            payload.putAll(attributionData)

            // Reported so the backend can tell a thin payload from a missing
            // one. Both must survive MINIMAL — explaining why a payload is
            // small is the one thing that stays useful at every level.
            payload["collected_at"] = isoUtc(System.currentTimeMillis())
            payload["attribution_level"] = level.wireName

            // Filter last, so what we test below is exactly what goes out.
            val filtered = level.filter(payload).toMutableMap()

            val hasAttribution = ATTRIBUTION_KEYS.any { !filtered[it].isNullOrBlank() }
            if (!hasAttribution && source !in LIFECYCLE_SOURCES) {
                Logger.d("Skipping enrichment: no attribution data")
                return@guardTracking
            }

            Logger.d("Sending enrichment for $source at level ${level.wireName}")
            DeeplinklyNetwork.sendEnrichment(filtered, apiKey)
        }
    }

    private fun isoUtc(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(millis))
    }
}
