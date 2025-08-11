package com.deeplinkly.flutter_deeplinkly.attribution

import android.content.Context
import com.deeplinkly.flutter_deeplinkly.core.Logger
import com.deeplinkly.flutter_deeplinkly.core.Prefs
import com.deeplinkly.flutter_deeplinkly.network.NetworkUtils
import com.deeplinkly.flutter_deeplinkly.privacy.TrackingPreferences
import com.deeplinkly.flutter_deeplinkly.util.EnrichmentUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EnrichmentSender {
    suspend fun sendOnce(
        context: Context,
        enrichmentData: MutableMap<String, String?>,
        source: String,
        apiKey: String
    ) {
        if (TrackingPreferences.isTrackingDisabled()) {
            Logger.d("Tracking is disabled. Skipping enrichment/reporting.")
            return
        }
        val prefs = Prefs.of()
        val enrichedKey = "${source}_enriched"

        if (enrichmentData["advertising_id"].isNullOrEmpty()) {
            val cachedAdId = prefs.getString("advertising_id", null)
            if (!cachedAdId.isNullOrEmpty()) {
                enrichmentData["advertising_id"] = cachedAdId
                Logger.d("Using cached advertising_id: $cachedAdId")
            }
        }

        val hasAttributionData = listOf(
            "click_id", "utm_source", "utm_medium", "utm_campaign", "gclid", "fbclid", "ttclid"
        ).any { !enrichmentData[it].isNullOrBlank() }

        if (!prefs.getBoolean(enrichedKey, false) && hasAttributionData) {
            Logger.d("Sending enrichment for source: $source")
            NetworkUtils.sendEnrichment(enrichmentData, apiKey)
            prefs.edit().putBoolean(enrichedKey, true).apply()
        } else {
            Logger.d("Skipping enrichment: already sent or no attribution data")
        }
    }
}
